package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonVersions
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinSpec
import ru.hollowhorizon.hollowengine.common.addons.*
import ru.hollowhorizon.hollowengine.common.addons.project.HollowProject
import ru.hollowhorizon.hollowengine.common.scripting.MIXIN_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.source.AddonScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptArtifacts
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File
import java.util.jar.JarFile

/**
 * Finds the mixin specs to apply, while Mixin prepares its configs and before any game class is loaded.
 */
internal object MixinScriptStage {
    private const val EXTENSION = ".$MIXIN_SCRIPT_EXTENSION"

    fun collect(): Map<String, ByteArray> {
        val runtime = ScriptFingerprint.currentRuntimeIdentity
        val stored = MixinSpecStore.read(runtime)
        val addons = Addons()
        val specs = LinkedHashMap<String, ByteArray>()
        val changed = LinkedHashMap<ScriptId, File>()
        val fallbacks = HashMap<ScriptId, ByteArray>()

        (localScripts() + addons.scripts()).forEach { (id, read) ->
            if (id.qualified in specs || id in changed) return@forEach
            val artifacts = read() ?: return@forEach
            val source = artifacts.sourceFile?.takeIf(File::isFile)
            val previous = stored[id.qualified]
            when {
                previous != null && (source == null || previous.sourceHash == MixinSpecStore.sourceHash(source)) -> previous.spec?.let {
                    specs[id.qualified] = it
                }

                source == null || id.namespace != ScriptRegistry.sandboxNamespace -> specOf(
                    id,
                    artifacts,
                    runtime
                )?.let { specs[id.qualified] = it }

                else -> {
                    changed[id] = source
                    (previous?.spec ?: specOf(id, artifacts, runtime))?.let { fallbacks[id] = it }
                }
            }
        }

        if (changed.isNotEmpty()) {
            val compiled =
                EarlyMixinCompilation.compile(changed, addons.compiler(), addons.store, addons::projectDependencies)
            changed.keys.forEach { id ->
                val spec = if (id in compiled) compiled[id] else fallbacks[id]
                if (spec != null) specs[id.qualified] = spec
            }
        }
        return specs
    }

    /** The sandbox and the engine's own scripts; addons have not registered theirs yet. */
    private fun localScripts(): List<Pair<ScriptId, () -> ScriptArtifacts?>> =
        ScriptRegistry.list(EXTENSION).map { id -> id to { ScriptRegistry.artifacts(id) } }

    /** Enabled addon jars, read no further than each step needs: staging a jar is not cheap. */
    private class Addons {
        val store = HollowAddonArtifactStore(HollowAddonManager.cacheDirectory)
        private val directories = HollowAddonManager.defaultSources()
        private val disabled = HollowAddonActivationStore(directories.first().resolve(".disabled-addons")).load()
        private val jars =
            directories.mapIndexed { priority, directory -> priority to HollowAddonProbe.listAddonJars(directory) }

        fun scripts(): List<Pair<ScriptId, () -> ScriptArtifacts?>> =
            chosen(stage { hasMixinScripts(it) }).flatMap { candidate ->
                val descriptor = candidate.descriptor
                val source = AddonScriptSource(
                    namespace = descriptor.id,
                    archive = candidate.classesFile,
                    classLoader = MixinScriptStage::class.java.classLoader,
                    classpath = emptyList(),
                    dependencies = descriptor.dependencies,
                    fingerprint = descriptor.version,
                    storageKey = candidate.fingerprint,
                )
                source.list().filter { it.path.endsWith(EXTENSION) }.map { id -> id to { source.read(id) } }
            }

        private val descriptors = HashMap<File, HollowAddonDescriptor?>()

        fun compiler(): HollowAddonCandidate? =
            chosen(stage { jar -> descriptorOf(jar)?.id == EarlyMixinCompilation.COMPILER_ADDON }).firstOrNull()

        fun projectDependencies(): EarlyMixinCompilation.Dependencies {
            val project = HollowProject.properties()
            val enabled = jars.flatMap { (_, files) -> files }.mapNotNull(::descriptorOf)
                .filter { it.id !in disabled && it.environment.supports(RuntimeFlags.physicalClient) }
            val byId = enabled.associateBy { it.id }
            val wanted = LinkedHashSet<String>()
            fun visit(id: String) {
                if (wanted.add(id)) byId[id]?.dependencies?.forEach(::visit)
            }
            project.dependsOn.forEach(::visit)

            val addons = chosen(stage { jar -> descriptorOf(jar)?.id in wanted }).map { it.classesFile }
            val mods = (project.modDependencies + enabled.flatMap { it.modDependencies }).distinct()
            return EarlyMixinCompilation.Dependencies(mods, addons)
        }

        private fun descriptorOf(jar: File): HollowAddonDescriptor? = descriptors.getOrPut(jar) {
            runCatching { HollowAddonDescriptorReader.read(jar) }.getOrNull()
        }

        private fun stage(filter: (File) -> Boolean): List<Pair<Int, HollowAddonCandidate>> =
            jars.flatMap { (priority, files) ->
                files.filter(filter).mapNotNull { jar ->
                    runCatching { priority to store.stage(jar) }.onFailure {
                            HollowEngine.LOGGER.error(
                                "Skipping addon '{}' while preparing mixins",
                                jar.name,
                                it
                            )
                        }.getOrNull()
                }
            }

        private fun chosen(staged: List<Pair<Int, HollowAddonCandidate>>): List<HollowAddonCandidate> =
            staged.groupBy { (_, candidate) -> candidate.descriptor.id }.values.map { copies ->
                copies.sortedWith(
                    Comparator<Pair<Int, HollowAddonCandidate>> { left, right ->
                        AddonVersions.compare(right.second.descriptor.version, left.second.descriptor.version)
                    }.thenBy { (priority, _) -> priority },
                ).first().second
            }.filter { candidate ->
                val descriptor = candidate.descriptor
                descriptor.id !in disabled && descriptor.environment.supports(RuntimeFlags.physicalClient)
            }

        private fun hasMixinScripts(jar: File): Boolean = runCatching {
            JarFile(jar, false).use { archive ->
                archive.entries().asSequence().any { entry ->
                    entry.name.endsWith(EXTENSION) || entry.name.endsWith(EXTENSION + ScriptCache.ARTIFACT_SUFFIX)
                }
            }
        }.getOrDefault(false)
    }

    private fun specOf(id: ScriptId, artifacts: ScriptArtifacts, runtime: String): ByteArray? = listOfNotNull(
        artifacts.precompiled,
        ScriptCache.artifact(id)
    ).firstOrNull { jar -> jar.isFile && ScriptCache.stampOf(jar)?.runtime == runtime }?.let(::readSpec)

    private fun readSpec(jar: File): ByteArray? = runCatching {
        JarFile(jar).use { archive ->
            val scriptClass = archive.manifest?.mainAttributes?.getValue("Main-Class") ?: return@use null
            val entry = archive.getJarEntry(scriptClass.replace('.', '/') + ScriptMixinSpec.CLASS_SUFFIX + ".class")
                ?: return@use null
            archive.getInputStream(entry).use { it.readBytes() }
        }
    }.onFailure { HollowEngine.LOGGER.warn("Unreadable compiled script '{}'", jar, it) }.getOrNull()
}
