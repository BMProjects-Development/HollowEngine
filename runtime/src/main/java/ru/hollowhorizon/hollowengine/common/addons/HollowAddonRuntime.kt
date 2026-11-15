package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonVersions
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinScripts
import ru.hollowhorizon.hollowengine.common.scripting.source.AddonScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptSourceLifecycle
import ru.hollowhorizon.hollowengine.common.scripting.startup.StartupScripts
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import ru.hollowhorizon.hollowengine.network.HollowAddonPacketRegistry
import java.io.File
import java.net.URLClassLoader

/**
 * Keeps the running addons equal to what a restart would give: of every installed addon the copy chosen
 * by [select] runs, and whatever does not run has a reason an operator can read in [statusSnapshot].
 */
internal class HollowAddonRuntime(
    private val sources: List<File>,
    cacheDirectory: File,
    private val addonsDirectory: File,
    private val modsDirectory: File,
    private val isModLoaded: (String) -> Boolean = ModList::isLoaded,
    private val modVersion: (String) -> String? = { id -> ModList.mods.firstOrNull { it.id == id }?.version },
    private val notifier: HollowAddonNotifier = HollowAddonNotifications,
    private val embeddedMods: () -> List<Pair<String, File>> = HollowAddonProbe::listEmbeddedModJars,
    private val watchSources: Boolean = true,
) {
    private val mutex = Mutex()
    private val runtimeJob = SupervisorJob()
    private val runtimeScope = CoroutineScope(Dispatchers.IO + runtimeJob + CoroutineName("HollowEngine addons"))
    private val artifactStore = HollowAddonArtifactStore(cacheDirectory)
    private val activationStore = HollowAddonActivationStore(addonsDirectory)
    private val sourceDirectories = sources.map(File::getCanonicalFile)
    private val loadedAddons = LinkedHashMap<String, LoadedHollowAddon>()
    private val pendingAddons = LinkedHashMap<String, HollowAddonCandidate>()

    /** Every installed addon jar by [HollowAddonCandidate.key], whether it runs or not. */
    private val knownCandidates = LinkedHashMap<String, HollowAddonCandidate>()

    /** Why the chosen copy of an addon is not the one running, by [HollowAddonCandidate.key]. */
    private val obstacles = HashMap<String, Obstacle>()
    private val unreadableJars = LinkedHashMap<String, HollowAddonProblem>()
    private val disabledAddonIds = LinkedHashSet<String>()
    private val restartNotices = HashSet<String>()
    private var watcherJobs: List<Job> = emptyList()

    val services = HollowAddonServiceRegistry()

    @Volatile
    var loadedSnapshot: List<HollowAddonDescriptor> = emptyList()
        private set

    @Volatile
    var restartRequiredSnapshot: List<HollowAddonDescriptor> = emptyList()
        private set

    @Volatile
    var resourcePackSnapshot: List<HollowAddonResourcePack> = emptyList()
        private set

    /**
     * Addons that are meant to run here and are not switched off, loaded or not. Like the other
     * snapshots it is readable from inside an addon's own `load`, which runs under the lock.
     */
    @Volatile
    var enabledSnapshot: List<HollowAddonInstallation> = emptyList()
        private set

    @Volatile
    var statusSnapshot: List<HollowAddonStatus> = emptyList()
        private set

    @Volatile
    var problemSnapshot: List<HollowAddonProblem> = emptyList()
        private set

    @Volatile
    private var started = false

    /** Set while everything is being unloaded for good, when no status is worth reporting any more. */
    private var closing = false

    suspend fun start() {
        withContext(Dispatchers.IO) {
            addonsDirectory.mkdirs()
            disabledAddonIds += activationStore.load()
        }
        val stagedBySource = withContext(Dispatchers.IO) {
            sources.map { source ->
                HollowAddonProbe.listAddonJars(source).map { file ->
                    file to runCatching { artifactStore.stage(file) }
                        .onFailure { HollowEngine.LOGGER.error("Skipping addon jar '{}'", file.name, it) }
                }
            }
        }
        val embedded = withContext(Dispatchers.IO) {
            embeddedMods().map { (modId, file) ->
                file to runCatching { artifactStore.stageEmbedded(file, modId) }
                    .onFailure { HollowEngine.LOGGER.error("Skipping the addon embedded in mod '{}'", modId, it) }
            }
        }
        val candidatesBySource = stagedBySource.map { staged -> staged.mapNotNull { (_, result) -> result.getOrNull() } }
        val candidates = embedded.mapNotNull { (_, result) -> result.getOrNull() } + candidatesBySource.flatten()
        withContext(Dispatchers.IO) { AddonCaches.retainStaged(artifactStore, candidates) }

        locked {
            (embedded + stagedBySource.flatten()).forEach { (file, result) ->
                result.exceptionOrNull()?.let { error -> unreadableJars[file.canonicalPath] = problemOf(file, error) }
            }
            candidates.forEach { candidate -> knownCandidates[candidate.key] = candidate }
            candidates.map { it.descriptor.id }.distinct().forEach { id -> select(id)?.let(::queue) }
            drainPending()
        }
        withContext(Dispatchers.IO) { AddonCaches.retainCompiledScripts(candidates) }
        if (watchSources) watcherJobs = sources.mapIndexed { index, source ->
            val unreadable = stagedBySource[index].filter { (_, result) -> result.isFailure }.map { (file, _) -> file }
            HollowAddonWatcher(source, artifactStore, this, runtimeScope).start(candidatesBySource[index], unreadable)
        }
        started = true
    }

    /** A jar appeared in a watched directory, or the one already there has changed. */
    suspend fun install(candidate: HollowAddonCandidate): Unit = locked {
        unreadableJars.remove(candidate.key)
        obstacles.remove(candidate.key)
        val previous = knownCandidates.put(candidate.key, candidate)
        if (previous != null && previous.descriptor.id != candidate.descriptor.id) reconcile(previous.descriptor.id)
        reconcile(candidate.descriptor.id)
        Unit
    }

    suspend fun remove(candidate: HollowAddonCandidate): Unit = locked {
        unreadableJars.remove(candidate.key)
        obstacles.remove(candidate.key)
        val removed = knownCandidates.remove(candidate.key) ?: return@locked
        reconcile(removed.descriptor.id)
        Unit
    }

    /** [file] carries an addon descriptor, but cannot be read as an addon; null [error] takes that back. */
    suspend fun reportUnreadable(file: File, error: Throwable?): Unit = locked {
        val key = file.canonicalPath
        if (error == null) unreadableJars.remove(key) else unreadableJars[key] = problemOf(file, error)
    }

    suspend fun setEnabled(id: String, enabled: Boolean): HollowAddonOperationResult = locked {
        val copies = copiesOf(id)
        if (copies.isEmpty()) return@locked notInstalled(id)
        copies.firstNotNullOfOrNull { it.embeddedModId }?.let { modId ->
            return@locked HollowAddonOperationResult(false, "Addon '$id' belongs to mod '$modId' and follows its lifecycle.")
        }

        val disabled = if (enabled) disabledAddonIds - id else disabledAddonIds + id
        withContext(Dispatchers.IO) { activationStore.save(disabled) }
        val runningBefore = loadedAddons.keys.toSet()
        disabledAddonIds.clear()
        disabledAddonIds += disabled
        reconcile(id)

        if (!enabled) {
            val stopped = runningBefore - loadedAddons.keys - id
            val dependants = if (stopped.isEmpty()) "" else " Stopped with it, as they depend on it: ${stopped.joinToString()}."
            return@locked HollowAddonOperationResult(true, "Addon '$id' is disabled.$dependants")
        }
        val status = statusOf(representative(id))
        HollowAddonOperationResult(
            status.state == HollowAddonState.LOADED || status.state == HollowAddonState.WAITING_FOR_DEPENDENCIES,
            "Addon '$id' is ${status.state.displayName}." + status.details?.let { " $it" }.orEmpty(),
        )
    }

    suspend fun reload(id: String): HollowAddonOperationResult = locked {
        val copies = copiesOf(id)
        if (copies.isEmpty()) return@locked notInstalled(id)
        copies.firstNotNullOfOrNull { it.embeddedModId }?.let { modId ->
            return@locked HollowAddonOperationResult(false, "Addon '$id' belongs to mod '$modId'; restart Minecraft to reload it.")
        }
        if (id in disabledAddonIds) {
            return@locked HollowAddonOperationResult(false, "Addon '$id' is disabled; enable it first.")
        }
        val chosen = select(id) ?: return@locked statusOf(representative(id)).let { status ->
            HollowAddonOperationResult(false, "Addon '$id' is ${status.state.displayName}." + status.details?.let { " $it" }.orEmpty())
        }

        val refreshed = runCatching { withContext(Dispatchers.IO) { artifactStore.stage(chosen.sourceFile) } }
            .getOrElse { error ->
                unreadableJars[chosen.key] = problemOf(chosen.sourceFile, error)
                return@locked HollowAddonOperationResult(
                    false, "Cannot read '${chosen.sourceFile.name}': ${describe(error)}. Addon '$id' was left as it is."
                )
            }
        unreadableJars.remove(refreshed.key)
        obstacles.remove(refreshed.key)
        knownCandidates[refreshed.key] = refreshed
        if (refreshed.descriptor.id != id) {
            reconcile(id)
            reconcile(refreshed.descriptor.id)
            return@locked HollowAddonOperationResult(
                true, "'${refreshed.sourceFile.name}' now holds addon '${refreshed.descriptor.id}'; '$id' is no longer in it."
            )
        }

        val refusal = reconcile(id, restart = true)
        val running = loadedAddons[id]?.candidate
        when {
            running != null && refusal == null ->
                HollowAddonOperationResult(true, "Addon '$id' ${running.descriptor.version} was reloaded.")

            running != null -> HollowAddonOperationResult(
                false,
                "Addon '$id' was not reloaded and ${running.descriptor.version} keeps running: ${refusal?.details}",
            )

            else -> statusOf(representative(id)).let { status ->
                HollowAddonOperationResult(
                    false, "Addon '$id' is ${status.state.displayName}." + status.details?.let { " $it" }.orEmpty()
                )
            }
        }
    }

    suspend fun close() {
        watcherJobs.forEach { job -> job.cancelAndJoin() }
        watcherJobs = emptyList()
        locked {
            closing = true
            pendingAddons.clear()
            loadedAddons.keys.toList().forEach { id ->
                loadedAddons[id]?.let { collectUnloadOrder(id).forEach { addon -> unloadSingle(addon) } }
            }
        }
        runtimeJob.cancelAndJoin()
    }

    /**
     * The copy of addon [id] to run: among those made for this loader and side and not switched off, an
     * addon embedded in an installed mod, then the newest version, then the one from the source searched first.
     */
    private fun select(id: String): HollowAddonCandidate? =
        copiesOf(id).filter { candidate -> !isDisabled(candidate) && runsHere(candidate) }.minWithOrNull(copyOrder)

    /** The copy that speaks for addon [id] when none of them is to run. */
    private fun representative(id: String): HollowAddonCandidate = select(id) ?: copiesOf(id).minWith(copyOrder)

    private val copyOrder = Comparator<HollowAddonCandidate> { left, right ->
        val byEmbedding = compareValues(left.embeddedModId == null, right.embeddedModId == null)
        if (byEmbedding != 0) byEmbedding else AddonVersions.compareCopies(
            left.descriptor.version, priorityOf(left), right.descriptor.version, priorityOf(right),
        )
    }.thenBy { it.sourceFile.name }

    private fun priorityOf(candidate: HollowAddonCandidate): Int =
        sourceDirectories.indexOf(candidate.sourceFile.parentFile).takeIf { it >= 0 } ?: Int.MAX_VALUE

    private fun copiesOf(id: String): List<HollowAddonCandidate> =
        knownCandidates.values.filter { candidate -> candidate.descriptor.id == id }

    private fun runsHere(candidate: HollowAddonCandidate): Boolean =
        candidate.descriptor.platform.supports(HollowAddonRuntimeEnvironment.platform) &&
            candidate.descriptor.environment.supports(HollowAddonRuntimeEnvironment.isClient)

    /** An embedded addon cannot be switched off on its own, so an id disabled earlier does not apply to it. */
    private fun isDisabled(candidate: HollowAddonCandidate): Boolean =
        candidate.embeddedModId == null && candidate.descriptor.id in disabledAddonIds

    /**
     * Brings addon [id] to the copy [select] names, or stops it when there is none. A copy that cannot
     * start leaves the running one alone, and one that fails while starting gives way to it again.
     * [restart] stops and starts the addon even when the right copy already runs.
     *
     * @return why the chosen copy is not the one running afterwards, or null when it is or there is none.
     */
    private suspend fun reconcile(id: String, restart: Boolean = false): Obstacle? {
        val desired = select(id)
        val current = loadedAddons[id]
        if (current == null) {
            pendingAddons.remove(id)
            if (desired != null) queue(desired)
            drainPending()
            return desired?.let(::notRunningReason)
        }

        val unloadOrder = collectUnloadOrder(id)
        val previous = unloadOrder.map(LoadedHollowAddon::candidate)
        val dependants = previous.dropLast(1).asReversed()
        val currentStays = knownCandidates[current.candidate.key]?.descriptor?.id == id && !isDisabled(current.candidate)
        if (desired != null && currentStays) {
            if (!restart && desired.isSameArtifact(current.candidate)) return null
            val obstacle = obstacleOf(desired) ?: dependants.firstNotNullOfOrNull { dependant ->
                obstacleOf(dependant)?.let { reason ->
                    Obstacle(
                        HollowAddonState.RESTART_REQUIRED,
                        "Addon '${dependant.descriptor.id}' depends on it and could not be started again: ${reason.details}",
                    )
                }
            }
            if (obstacle != null) {
                // Refusing to restart the copy that runs says nothing about the file, so nothing is recorded.
                if (!desired.isSameArtifact(current.candidate)) block(desired, obstacle)
                return obstacle
            }
        }

        unloadOrder.forEach { addon -> unloadSingle(addon) }
        if (desired != null) queue(desired)
        dependants.forEach(::queue)
        drainPending()
        if (desired == null || id in loadedAddons) return null
        if (!currentStays) return notRunningReason(desired)

        HollowEngine.LOGGER.error(
            "Addon '{}' could not start from '{}'; the version that was running is restored",
            id,
            desired.sourceFile.name,
        )
        val failure = notRunningReason(desired) ?: Obstacle(HollowAddonState.FAILED, "It did not start.")
        previous.forEach { candidate -> pendingAddons.remove(candidate.descriptor.id) }
        previous.asReversed().forEach { candidate -> pendingAddons[candidate.descriptor.id] = candidate }
        drainPending()
        obstacles[desired.key] = failure
        return failure
    }

    private fun notRunningReason(candidate: HollowAddonCandidate): Obstacle? = when {
        loadedAddons[candidate.descriptor.id]?.candidate?.isSameArtifact(candidate) == true -> null
        pendingAddons[candidate.descriptor.id]?.key == candidate.key ->
            Obstacle(HollowAddonState.WAITING_FOR_DEPENDENCIES, waitingDetails(candidate))

        else -> obstacles[candidate.key]
    }

    private fun queue(candidate: HollowAddonCandidate): Boolean {
        val obstacle = obstacleOf(candidate)
        if (obstacle != null) {
            block(candidate, obstacle)
            return false
        }
        obstacles.remove(candidate.key)
        pendingAddons[candidate.descriptor.id] = candidate
        return true
    }

    private fun block(candidate: HollowAddonCandidate, obstacle: Obstacle) {
        obstacles[candidate.key] = obstacle
        if (obstacle.state == HollowAddonState.RESTART_REQUIRED && restartNotices.add(candidate.fingerprint)) {
            notifier.restartRequired(candidate.descriptor)
        }
    }

    /** What keeps [candidate] from starting in this session, or null when nothing does. */
    private fun obstacleOf(candidate: HollowAddonCandidate): Obstacle? {
        val descriptor = candidate.descriptor
        val missingMods = descriptor.modRequirements.filterNot { it.optional || isModLoaded(it.id) }
        if (missingMods.isNotEmpty()) {
            return Obstacle(HollowAddonState.MISSING_MODS, "Requires mods that are not installed: ${missingMods.joinToString()}.")
        }
        // An optional mod may be absent, but one that is installed has to be of a version the addon knows.
        val unfitMods = descriptor.modRequirements.filter { isModLoaded(it.id) }.mapNotNull { requirement ->
            val installed = modVersion(requirement.id) ?: return@mapNotNull null
            if (requirement.accepts(installed)) null else "$requirement ($installed is installed)"
        }
        if (unfitMods.isNotEmpty()) {
            return Obstacle(HollowAddonState.MISSING_MODS, "Requires other versions of mods: ${unfitMods.joinToString()}.")
        }
        if (wasBootstrapAddonRejected(candidate)) {
            return Obstacle(
                HollowAddonState.REJECTED,
                "Its bundled libraries conflict with Minecraft or another addon; see the bootstrap validation error in the log.",
            )
        }
        if (candidate.requiresBootstrapLibraries && !areBootstrapLibrariesLoaded(candidate)) {
            return Obstacle(
                HollowAddonState.RESTART_REQUIRED,
                "Its bootstrap/native libraries can only be loaded while the game starts; restart Minecraft.",
            )
        }
        val lateScripts = when {
            candidate.hasMixinScripts && MixinScripts.hasRun -> "mixin"
            candidate.hasStartupScripts && StartupScripts.hasRun -> "startup"
            else -> null
        }
        if (lateScripts != null) {
            return Obstacle(
                HollowAddonState.RESTART_REQUIRED,
                "Its $lateScripts scripts run once, while the game starts; restart Minecraft.",
            )
        }
        return null
    }

    private fun areBootstrapLibrariesLoaded(candidate: HollowAddonCandidate): Boolean =
        System.getProperty(AddonBootstrapContract.LOADED_ADDON_FINGERPRINTS_PROPERTY, "").split(',')
            .any { fingerprint -> fingerprint == candidate.fingerprint }

    private fun wasBootstrapAddonRejected(candidate: HollowAddonCandidate): Boolean =
        System.getProperty(AddonBootstrapContract.REJECTED_ADDON_FINGERPRINTS_PROPERTY, "").split(',')
            .any { fingerprint -> fingerprint == candidate.fingerprint }

    private fun statusOf(candidate: HollowAddonCandidate): HollowAddonStatus {
        val running = loadedAddons[candidate.descriptor.id]?.candidate?.takeIf { it.key == candidate.key }
        val state = stateOf(candidate)
        val details = when (state) {
            HollowAddonState.LOADED -> obstacles[candidate.key]?.takeIf { running?.fingerprint != candidate.fingerprint }
                ?.let { obstacle ->
                    "Version ${running?.descriptor?.version} keeps running; the file now holds " +
                        "${candidate.descriptor.version}, which did not start: ${obstacle.details}"
                }

            HollowAddonState.DISABLED -> null
            HollowAddonState.INCOMPATIBLE ->
                "Made for ${candidate.descriptor.platform.id}; this game runs ${HollowAddonRuntimeEnvironment.platform.id()}."

            HollowAddonState.INACTIVE -> if (HollowAddonRuntimeEnvironment.isClient) {
                "Made for dedicated servers; not loaded on a client."
            } else {
                "Made for clients; not loaded on a dedicated server."
            }

            HollowAddonState.DUPLICATE -> select(candidate.descriptor.id)?.let { used ->
                "Another copy is used: ${displayPath(used.sourceFile)} (${used.descriptor.version})."
            }

            HollowAddonState.WAITING_FOR_DEPENDENCIES -> waitingDetails(candidate)
            else -> obstacles[candidate.key]?.details
        }
        // What runs is described by the version that runs, not by what the file was replaced with.
        return HollowAddonStatus(running?.descriptor ?: candidate.descriptor, state, displayPath(candidate.sourceFile), details)
    }

    private fun stateOf(candidate: HollowAddonCandidate): HollowAddonState {
        val id = candidate.descriptor.id
        return when {
            loadedAddons[id]?.candidate?.key == candidate.key -> HollowAddonState.LOADED
            isDisabled(candidate) -> HollowAddonState.DISABLED
            !candidate.descriptor.platform.supports(HollowAddonRuntimeEnvironment.platform) -> HollowAddonState.INCOMPATIBLE
            !candidate.descriptor.environment.supports(HollowAddonRuntimeEnvironment.isClient) -> HollowAddonState.INACTIVE
            select(id)?.key != candidate.key -> HollowAddonState.DUPLICATE
            pendingAddons[id]?.key == candidate.key -> HollowAddonState.WAITING_FOR_DEPENDENCIES
            else -> obstacles[candidate.key]?.state ?: HollowAddonState.FAILED
        }
    }

    private fun waitingDetails(candidate: HollowAddonCandidate): String {
        val id = candidate.descriptor.id
        val waiting = candidate.descriptor.addonRequirements.filterNot(::isSatisfied).map { requirement ->
            val dependency = requirement.id
            val running = loadedAddons[dependency]?.candidate?.descriptor?.version
            val reason = when {
                running != null -> "$running is running"
                copiesOf(dependency).isEmpty() -> "not installed"
                dependsOn(dependency, id) -> "depends back on '$id'"
                else -> stateOf(representative(dependency)).displayName
            }
            "$requirement ($reason)"
        }
        return "Needs: ${waiting.joinToString()}."
    }

    /** Whether the addon [requirement] names is running in a version that fits it. */
    private fun isSatisfied(requirement: HollowAddonDependency): Boolean =
        loadedAddons[requirement.id]?.candidate?.descriptor?.version?.let(requirement::accepts) == true

    /** Whether addon [id] needs [target], directly or through the addons it depends on. */
    private fun dependsOn(id: String, target: String): Boolean {
        val visited = HashSet<String>()
        fun visit(current: String): Boolean {
            if (!visited.add(current)) return false
            val dependencies = copiesOf(current).takeIf { it.isNotEmpty() }?.let { representative(current) }
                ?.descriptor?.dependencies.orEmpty()
            return target in dependencies || dependencies.any(::visit)
        }
        return visit(id)
    }

    private fun displayPath(file: File): String {
        val parent = file.parentFile?.name
        return if (parent.isNullOrEmpty()) file.name else "$parent/${file.name}"
    }

    private fun problemOf(file: File, error: Throwable) = HollowAddonProblem(displayPath(file), describe(error))

    private fun describe(error: Throwable): String {
        fun text(failure: Throwable) = failure.javaClass.simpleName + failure.message?.let { ": $it" }.orEmpty()
        val root = generateSequence(error) { it.cause }.last()
        return if (root === error) text(error) else "${text(error)} (caused by ${text(root)})"
    }

    private fun notInstalled(id: String) = HollowAddonOperationResult(false, "Addon '$id' is not installed.")

    private suspend fun drainPending() {
        while (true) {
            val ready = pendingAddons.values.firstOrNull { candidate ->
                candidate.descriptor.addonRequirements.all(::isSatisfied)
            } ?: return
            pendingAddons.remove(ready.descriptor.id)
            load(ready)
        }
    }

    private suspend fun load(candidate: HollowAddonCandidate): Boolean {
        val descriptor = candidate.descriptor
        val dependencyLoaders = descriptor.dependencies.map { dependencyId ->
            loadedAddons.getValue(dependencyId).classLoader
        }
        val embeddedPackage = candidate.embeddedModId?.let {
            requireNotNull(descriptor.entrypoint) { "Embedded addon '${descriptor.id}' has no entrypoint" }
                .substringBeforeLast('.') + "."
        }
        val libraries = if (embeddedPackage != null) emptyList() else {
            withContext(Dispatchers.IO) { artifactStore.extractLibraries(candidate) }
        }
        val classLoader = if (embeddedPackage != null) {
            EmbeddedAddonClassLoader(
                modJar = candidate.artifactFile,
                addonPackage = embeddedPackage,
                parent = HollowAddonEntrypoint::class.java.classLoader,
                dependencies = dependencyLoaders,
            )
        } else {
            val urls = (listOf(candidate.classesFile, candidate.artifactFile) + libraries).distinct()
                .map { it.toURI().toURL() }.toTypedArray()
            HollowAddonClassLoader(
                urls = urls,
                parent = HollowAddonEntrypoint::class.java.classLoader,
                dependencies = dependencyLoaders,
            )
        }
        val hostServices = services.ownedBy(descriptor.id)
        val extensions = OwnedHollowAddonExtensions(descriptor.id, classLoader)
        var koinApplication: KoinApplication? = null
        var addonJob: Job? = null
        var loadedEntrypoint: Pair<HollowAddonEntrypoint, HollowAddonContext>? = null
        return runCatching {
            descriptor.requiredClasses.forEach { className -> Class.forName(className, false, classLoader) }
            val entrypoint = descriptor.entrypoint?.let { className ->
                Class.forName(className, true, classLoader).asSubclass(HollowAddonEntrypoint::class.java)
                    .getDeclaredConstructor().newInstance()
            } ?: NoEntrypoint
            val createdJob = SupervisorJob(runtimeJob)
            addonJob = createdJob
            val addonScope = CoroutineScope(
                Dispatchers.Default + createdJob + CoroutineName("Addon ${descriptor.id}") + ClassLoaderContextElement(
                    classLoader
                ),
            )
            val minecraftApi = OwnedHollowAddonMinecraftApi(descriptor.id, addonScope, classLoader)
            val bridgeModule = module {
                single<HollowAddonHostServices> { hostServices }
                single<HollowAddonExtensions> { extensions }
                single<HollowAddonMinecraftApi> { minecraftApi }
                single { descriptor }
            }
            val createdKoinApplication = koinApplication {
                modules(listOf(bridgeModule) + entrypoint.koinModules)
            }
            koinApplication = createdKoinApplication
            val context = HollowAddonContext(
                hostServices = hostServices,
                descriptor = descriptor,
                addonFile = candidate.artifactFile,
                classLoader = classLoader,
                koin = createdKoinApplication.koin,
            )
            withContext(Dispatchers.Default + ClassLoaderContextElement(classLoader) + HollowAddonLifecycleElement()) {
                entrypoint.load(context, addonScope)
                loadedEntrypoint = entrypoint to context
                HollowAddonEventRegistrar.register(
                    candidate.classesFile, classLoader, descriptor.id, entrypoint, addonScope, embeddedPackage
                )
                HollowAddonLoadedEvent.post(
                    HollowAddonLoadedEvent(descriptor.id, candidate.classesFile, classLoader, addonScope, embeddedPackage)
                )
            }
            registerScripts(candidate, classLoader, libraries)
            loadedAddons[descriptor.id] = LoadedHollowAddon(
                candidate = candidate,
                classLoader = classLoader,
                entrypoint = entrypoint,
                context = context,
                job = createdJob,
                koinApplication = createdKoinApplication,
                hostServices = hostServices,
                extensions = extensions,
            )
            obstacles.remove(candidate.key)
            refreshSnapshot()
            HollowEngine.LOGGER.info("Loaded addon {} {}", descriptor.id, descriptor.version)
            true
        }.onFailure { error ->
            HollowEngine.LOGGER.error("Failed to load addon '${descriptor.id}'", error)
            obstacles[candidate.key] = Obstacle(HollowAddonState.FAILED, describe(error))
            ScriptRegistry.unregister(descriptor.id)
            addonJob?.cancelAndJoin()
            HollowAddonPacketRegistry.unregister(descriptor.id)
            // Its `load` returned, so it holds whatever it set up there and has to let go of it.
            loadedEntrypoint?.let { (entrypoint, context) -> runUnloadHook(descriptor.id, entrypoint, context, classLoader) }
            extensions.cleanup()
            koinApplication?.close()
            hostServices.cleanup()
            classLoader.close()
        }.getOrDefault(false)
    }

    /**
     * Publishes the addon as a namespace of its own, with its `scripts/` directory if it has one. The addon jar
     * and its bundled libraries go on the compilation classpath and its classloader becomes the parent of the
     * compiled scripts, so an addon can move part of its own logic into scripts. An addon without scripts is
     * published too: projects that list it in `dependsOn` reach its classes through it.
     */
    private fun registerScripts(
        candidate: HollowAddonCandidate,
        classLoader: ClassLoader,
        libraries: List<File>,
    ) {
        val descriptor = candidate.descriptor
        val source = AddonScriptSource(
            namespace = descriptor.id,
            archive = candidate.classesFile,
            classLoader = classLoader,
            classpath = listOf(candidate.classesFile) + libraries,
            dependencies = descriptor.dependencies,
            fingerprint = descriptor.version,
            storageKey = candidate.fingerprint,
        )
        ScriptSourceLifecycle.install()
        ScriptRegistry.register(source)
    }

    private suspend fun unloadSingle(addon: LoadedHollowAddon) {
        val id = addon.candidate.descriptor.id
        ScriptRegistry.unregister(id)
        addon.job.cancelAndJoin()
        HollowAddonPacketRegistry.unregister(id)
        runUnloadHook(id, addon.entrypoint, addon.context, addon.classLoader)
        addon.extensions.cleanup()
        runCatching { addon.koinApplication.close() }.onFailure {
            HollowEngine.LOGGER.error(
                "Failed to close Koin for addon '$id'", it
            )
        }
        addon.hostServices.cleanup()
        runCatching { addon.classLoader.close() }.onFailure {
            HollowEngine.LOGGER.error(
                "Failed to close classloader for addon '$id'", it
            )
        }
        loadedAddons.remove(id)
        refreshSnapshot()
        HollowEngine.LOGGER.info("Unloaded addon {}", id)
    }

    private suspend fun runUnloadHook(
        id: String,
        entrypoint: HollowAddonEntrypoint,
        context: HollowAddonContext,
        classLoader: ClassLoader,
    ) {
        runCatching {
            withContext(Dispatchers.Default + ClassLoaderContextElement(classLoader) + HollowAddonLifecycleElement()) {
                entrypoint.unload(context)
            }
        }.onFailure { HollowEngine.LOGGER.error("Failed to run unload hook for addon '$id'", it) }
    }

    private fun collectUnloadOrder(rootId: String): List<LoadedHollowAddon> {
        val result = ArrayList<LoadedHollowAddon>()
        val visited = HashSet<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            loadedAddons.values.filter { id in it.candidate.descriptor.dependencies }
                .forEach { dependant -> visit(dependant.candidate.descriptor.id) }
            loadedAddons[id]?.let(result::add)
        }
        visit(rootId)
        return result
    }

    private fun refreshSnapshot() {
        loadedSnapshot = loadedAddons.values.map { it.candidate.descriptor }
        val modsFolder = modsDirectory.canonicalFile
        resourcePackSnapshot = loadedAddons.values.toList().asReversed()
            .map(LoadedHollowAddon::candidate)
            .filter { candidate ->
                // The mod loader already serves mods and the addons embedded in them.
                (candidate.hasAssets || candidate.hasData) && candidate.embeddedModId == null &&
                    candidate.sourceFile.parentFile?.canonicalFile != modsFolder
            }
            .map { candidate ->
                HollowAddonResourcePack(
                    addonId = candidate.descriptor.id,
                    name = candidate.descriptor.name,
                    file = candidate.artifactFile,
                    hasAssets = candidate.hasAssets,
                )
            }
    }

    private fun refreshStatuses() {
        val ids = knownCandidates.values.map { it.descriptor.id }.distinct()
        enabledSnapshot = ids.mapNotNull(::select).map { candidate ->
            HollowAddonInstallation(candidate.descriptor, candidate.classesFile)
        }
        val statuses = knownCandidates.values
            .sortedWith(compareBy({ it.descriptor.id }, { it.sourceFile.name }))
            .map(::statusOf)
        logChanges(statusSnapshot, statuses)
        notifyProblems(statusSnapshot, statuses)
        statusSnapshot = statuses
        restartRequiredSnapshot = ids.mapNotNull(::select)
            .filter { candidate -> obstacles[candidate.key]?.state == HollowAddonState.RESTART_REQUIRED }
            .map(HollowAddonCandidate::descriptor)
        problemSnapshot = unreadableJars.values.toList()
    }

    /** Every addon that is not simply running is named in the log once, when that becomes or stops being so. */
    private fun logChanges(before: List<HollowAddonStatus>, after: List<HollowAddonStatus>) {
        val previous = before.associateBy(HollowAddonStatus::fileName)
        after.forEach { status ->
            val old = previous[status.fileName]
            if (old?.state == status.state && old.details == status.details) return@forEach
            if (status.state == HollowAddonState.LOADED && status.details == null) return@forEach
            val message = "Addon '${status.descriptor.id}' ${status.descriptor.version} [${status.fileName}] is " +
                status.state.displayName + status.details?.let { ": $it" }.orEmpty()
            when (status.state) {
                HollowAddonState.DISABLED, HollowAddonState.INACTIVE -> HollowEngine.LOGGER.info(message)
                HollowAddonState.LOADED, HollowAddonState.DUPLICATE, HollowAddonState.RESTART_REQUIRED,
                HollowAddonState.WAITING_FOR_DEPENDENCIES, HollowAddonState.INCOMPATIBLE -> HollowEngine.LOGGER.warn(message)

                else -> HollowEngine.LOGGER.error(message)
            }
        }
    }

    /**
     * An addon that is meant to run and does not is told to the player once, when that becomes so. One
     * waiting for a restart has a notification of its own, see [block].
     */
    private fun notifyProblems(before: List<HollowAddonStatus>, after: List<HollowAddonStatus>) {
        val previous = before.associateBy(HollowAddonStatus::fileName)
        after.forEach { status ->
            if (status.state !in PROBLEM_STATES) return@forEach
            val old = previous[status.fileName]
            if (old?.state == status.state && old.details == status.details) return@forEach
            notifier.notRunning(status)
        }
    }

    /**
     * Tells the player which addons [block] started and stopped. The addons found while the game starts
     * are not announced: nobody is looking yet, and there is nothing unexpected about them.
     */
    private fun notifyLifecycle(before: Map<String, LoadedHollowAddon>) {
        if (!started || closing) return
        val after = loadedAddons
        val (reloaded, loaded) = after.filter { (id, addon) -> before[id] !== addon }.values
            .map { it.candidate.descriptor }.partition { it.id in before }
        val unloaded = before.filterKeys { it !in after }.values.map { it.candidate.descriptor }
        if (loaded.isNotEmpty()) notifier.loaded(loaded)
        if (reloaded.isNotEmpty()) notifier.reloaded(reloaded)
        if (unloaded.isNotEmpty()) notifier.unloaded(unloaded)
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        val loadedBefore = loadedSnapshot
        val runningBefore = loadedAddons.toMap()
        val packsBefore = resourcePackSnapshot
        return try {
            block()
        } finally {
            if (!closing) refreshStatuses()
            runCatching { notifyLifecycle(runningBefore) }
                .onFailure { HollowEngine.LOGGER.error("Failed to notify about the changed addons", it) }
            mutex.unlock()
            if (loadedSnapshot !== loadedBefore) {
                runCatching { HollowAddonsChangedEvent.post(HollowAddonsChangedEvent()) }
                    .onFailure { HollowEngine.LOGGER.error("Failed to apply the changed set of addons", it) }
                reloadRunningServers()
            }
            if (started && assetsOf(packsBefore) != assetsOf(resourcePackSnapshot)) reloadClientResources()
        }
    }

    private fun assetsOf(packs: List<HollowAddonResourcePack>): List<File> =
        packs.filter(HollowAddonResourcePack::hasAssets).map(HollowAddonResourcePack::file)

    private fun reloadRunningServers() {
        ServerRuntimeState.servers().forEach { server ->
            server.execute {
                server.packRepository.reload()
                server.reloadResources(server.packRepository.selectedIds).exceptionally { error ->
                    HollowEngine.LOGGER.error("Failed to reload datapacks after the set of addons changed", error)
                    null
                }
            }
        }
    }

    private fun reloadClientResources() {
        if (isPhysicalClient) ClientResources.reload()
    }

    /** Stands in for the entrypoint of an addon made only of scripts and resources. */
    private object NoEntrypoint : HollowAddonEntrypoint {
        override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) = Unit
    }

    private data class Obstacle(val state: HollowAddonState, val details: String)

    private companion object {
        /** States of an addon that should be running and is not, for a reason the player can act on. */
        val PROBLEM_STATES = setOf(
            HollowAddonState.FAILED,
            HollowAddonState.MISSING_MODS,
            HollowAddonState.WAITING_FOR_DEPENDENCIES,
            HollowAddonState.REJECTED,
        )
    }

    private data class LoadedHollowAddon(
        val candidate: HollowAddonCandidate,
        val classLoader: URLClassLoader,
        val entrypoint: HollowAddonEntrypoint,
        val context: HollowAddonContext,
        val job: Job,
        val koinApplication: KoinApplication,
        val hostServices: OwnedHollowAddonHostServices,
        val extensions: OwnedHollowAddonExtensions,
    )
}

/** The state as an operator reads it: `waiting for dependencies` rather than the constant's name. */
internal val HollowAddonState.displayName: String
    get() = name.lowercase().replace('_', ' ')
