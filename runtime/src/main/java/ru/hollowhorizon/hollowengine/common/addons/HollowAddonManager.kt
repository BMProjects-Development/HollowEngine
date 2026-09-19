package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.runBlocking
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.File
import kotlin.reflect.KClass

object HollowAddonManager : AutoCloseable {
    @Volatile
    private var runtime: HollowAddonRuntime? = null

    val loaded: List<HollowAddonDescriptor>
        get() = runtime?.loadedSnapshot.orEmpty()

    /** Installed addons that are not switched off. Safe to read from inside an addon's `load`. */
    val enabled: List<HollowAddonInstallation>
        get() = runtime?.enabledSnapshot.orEmpty()

    val restartRequired: List<HollowAddonDescriptor>
        get() = runtime?.restartRequiredSnapshot.orEmpty()

    val statuses: List<HollowAddonStatus>
        get() = runtime?.let { addonRuntime -> runBlocking { addonRuntime.statuses() } }.orEmpty()

    /**
     * Loaded addons from `hollowengine/addons` that carry assets or data, highest priority first. Addons
     * in `mods` are left out: their loader already serves their resources.
     */
    val resourcePacks: List<HollowAddonResourcePack>
        get() = runtime?.resourcePackSnapshot.orEmpty()

    fun initializeAll(sources: List<File> = defaultSources()) {
        val created = synchronized(this) {
            if (runtime != null) return
            HollowAddonRuntime(
                sources = sources,
                cacheDirectory = cacheDirectory,
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

    /** Where addons are looked for, highest priority first. */
    internal fun defaultSources(): List<File> = listOf(
        DirectoryManager.HOLLOW_ENGINE.resolve("addons").toFile(),
        File("mods").absoluteFile,
    )

    internal val cacheDirectory: File
        get() = DirectoryManager.HOLLOW_ENGINE.resolve(".cache").resolve("addons").toFile()

    fun isLoaded(id: String): Boolean = loaded.any { it.id == id }

    fun enable(id: String): HollowAddonOperationResult = runtime
        ?.let { addonRuntime -> runBlocking { addonRuntime.setEnabled(id, enabled = true) } }
        ?: HollowAddonOperationResult(false, "Addon runtime is not initialized.")

    fun disable(id: String): HollowAddonOperationResult = runtime
        ?.let { addonRuntime -> runBlocking { addonRuntime.setEnabled(id, enabled = false) } }
        ?: HollowAddonOperationResult(false, "Addon runtime is not initialized.")

    fun reload(id: String): HollowAddonOperationResult = runtime
        ?.let { addonRuntime -> runBlocking { addonRuntime.reload(id) } }
        ?: HollowAddonOperationResult(false, "Addon runtime is not initialized.")

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
