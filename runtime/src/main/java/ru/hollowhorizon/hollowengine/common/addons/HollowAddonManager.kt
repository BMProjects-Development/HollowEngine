package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.runBlocking
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.File
import kotlin.reflect.KClass

/**
 * The addons of the running game. Everything that only reads is answered from a snapshot and may be
 * called from anywhere, an addon's own `load` included; [enable], [disable] and [reload] wait for the
 * change to finish and are refused from inside `load` and `unload`.
 */
object HollowAddonManager : AutoCloseable {
    @Volatile
    private var runtime: HollowAddonRuntime? = null

    val loaded: List<HollowAddonDescriptor>
        get() = runtime?.loadedSnapshot.orEmpty()

    /** Installed addons that are meant to run here and are not switched off, loaded or not. */
    val enabled: List<HollowAddonInstallation>
        get() = runtime?.enabledSnapshot.orEmpty()

    val restartRequired: List<HollowAddonDescriptor>
        get() = runtime?.restartRequiredSnapshot.orEmpty()

    /** Every installed addon jar with its state and, unless it simply runs, the reason for that state. */
    val statuses: List<HollowAddonStatus>
        get() = runtime?.statusSnapshot.orEmpty()

    /** Jars that carry an addon descriptor but could not be read as an addon. */
    val problems: List<HollowAddonProblem>
        get() = runtime?.problemSnapshot.orEmpty()

    /**
     * Loaded addons that carry assets or data, highest priority first. Addons in `mods` are left out:
     * their loader already serves their resources.
     */
    val resourcePacks: List<HollowAddonResourcePack>
        get() = runtime?.resourcePackSnapshot.orEmpty()

    fun initializeAll(sources: List<File> = defaultSources()) {
        val created = synchronized(this) {
            if (runtime != null) return
            HollowAddonRuntime(
                sources = sources,
                cacheDirectory = cacheDirectory,
                addonsDirectory = addonsDirectory,
                modsDirectory = modsDirectory,
            ).also { runtime = it }
        }
        runCatching { runBlocking { created.start() } }
            .onFailure {
                synchronized(this) {
                    if (runtime === created) runtime = null
                }
                runBlocking { created.close() }
            }
            .getOrThrow()
    }

    /** `hollowengine/addons`, which also keeps the list of addons switched off. */
    internal val addonsDirectory: File
        get() = DirectoryManager.HOLLOW_ENGINE.resolve("addons").toFile()

    /** Addons here are mods as well, so the mod loader serves their resources. */
    internal val modsDirectory: File
        get() = File("mods").absoluteFile

    /**
     * Where addons are looked for, highest priority first. A development run puts the addons the
     * Gradle build has just made ahead of the rest, so stale copies elsewhere never win.
     */
    internal fun defaultSources(): List<File> = listOfNotNull(
        AddonBootstrapContract.developmentAddonsDirectory(),
        addonsDirectory,
        modsDirectory,
    )

    internal val cacheDirectory: File
        get() = DirectoryManager.HOLLOW_ENGINE.resolve(".cache").resolve("addons").toFile()

    fun isLoaded(id: String): Boolean = loaded.any { it.id == id }

    /** The copy of addon [id] that runs or would run, its duplicates left out; null when it is not installed. */
    fun status(id: String): HollowAddonStatus? {
        val copies = statuses.filter { it.descriptor.id == id }
        return copies.firstOrNull { it.state != HollowAddonState.DUPLICATE } ?: copies.firstOrNull()
    }

    fun enable(id: String): HollowAddonOperationResult = change("enable", id) { it.setEnabled(id, enabled = true) }

    fun disable(id: String): HollowAddonOperationResult = change("disable", id) { it.setEnabled(id, enabled = false) }

    fun reload(id: String): HollowAddonOperationResult = change("reload", id) { it.reload(id) }

    private fun change(
        action: String,
        id: String,
        operation: suspend (HollowAddonRuntime) -> HollowAddonOperationResult,
    ): HollowAddonOperationResult {
        val addonRuntime = runtime ?: return HollowAddonOperationResult(false, "Addon runtime is not initialized.")
        if (HollowAddonLifecycleElement.isActive) {
            return HollowAddonOperationResult(
                false,
                "Cannot $action addon '$id' while an addon is loading or unloading; launch it in the addon's scope instead.",
            )
        }
        return runCatching { runBlocking { operation(addonRuntime) } }.getOrElse { error ->
            HollowEngine.LOGGER.error("Failed to $action addon '$id'", error)
            HollowAddonOperationResult(false, "Failed to $action addon '$id': ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun <T : Any> findService(type: KClass<T>): T? = runtime?.services?.findService(type)

    fun <T : Any> findServices(type: KClass<T>): List<T> = runtime?.services?.findServices(type).orEmpty()

    inline fun <reified T : Any> find(): T? = findService(T::class)

    inline fun <reified T : Any> findAll(): List<T> = findServices(T::class)

    override fun close() {
        val closing = synchronized(this) {
            runtime.also { runtime = null }
        } ?: return
        runBlocking { closing.close() }
    }
}

/** Resources of an addon that the engine serves itself, see [HollowAddonManager.resourcePacks]. */
data class HollowAddonResourcePack(
    val addonId: String,
    val name: String,
    val file: File,
    val hasAssets: Boolean,
)
