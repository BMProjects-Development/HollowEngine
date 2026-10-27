package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryCompileResult
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryFunctionCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryProgram
import java.util.concurrent.ConcurrentHashMap

/** Where story text comes from. Files, addon jars, an in-memory string, or a future node graph. */
fun interface StorySourceProvider {
    /** Text of [address], or null when this provider does not have it. */
    fun read(address: String): String?
}

class StoryLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Loads and compiles stories, and keeps the results. Compilation happens once per address and text:
 * playback never parses.
 */
class SceneLibrary(
    private val catalog: () -> StoryFunctionCatalog = { StoryFunctionCatalog.PERMISSIVE },
) {
    private val providers = mutableListOf<StorySourceProvider>()
    private val cache = ConcurrentHashMap<String, StoryProgram>()

    fun addProvider(provider: StorySourceProvider) {
        providers += provider
        cache.clear()
    }

    fun removeProvider(provider: StorySourceProvider) {
        providers -= provider
        cache.clear()
    }

    /** Drops compiled programs; the next load re-reads and re-compiles. */
    fun invalidate() = cache.clear()

    fun invalidate(address: String) {
        cache.keys.removeIf { it == address || it.startsWith("$address\u0000") }
    }

    /**
     * The compiled program for [address] in [locale], falling back to the base file. Returns the
     * localized address alongside it, because that is what a checkpoint must record.
     */
    fun load(address: String, locale: String? = null): LoadedScene {
        val localized = locale?.let { localizedAddress(address, it) }
        if (localized != null) {
            readSource(localized)?.let { source ->
                return LoadedScene(localized, locale, compile(localized, source))
            }
        }
        val source = readSource(address) ?: throw StoryLoadException("Story '$address' not found")
        return LoadedScene(address, null, compile(address, source))
    }

    /** Loads a story that a checkpoint already resolved, without redoing locale fallback. */
    fun loadExact(address: String): StoryProgram {
        val source = readSource(address) ?: throw StoryLoadException("Story '$address' not found")
        return compile(address, source)
    }

    private fun readSource(address: String): String? = providers.asReversed().firstNotNullOfOrNull { it.read(address) }

    /** The `@define`s of the story [address], read without compiling it; what a file importing it checks against. */
    fun definitions(address: String): Map<String, List<String>>? = readSource(address)?.let(StoryCompiler::definitionsOf)

    private fun compile(address: String, source: String): StoryProgram {
        val hash = StoryCompiler.hash(source)
        cache["$address\u0000$hash"]?.let { return it }

        val result: StoryCompileResult = StoryCompiler.compile(address, source, catalog(), ::definitions)
        val program = result.program ?: throw StoryLoadException(
            "Story '$address' has errors:\n" + result.diagnostics.joinToString("\n") { "  $it" },
        )
        cache["$address\u0000$hash"] = program
        return program
    }

    companion object {
        /**
         * `project:stories/example.story` + `ru_ru` -> `project:stories/example.ru_ru.story`.
         * An address without an extension gets the locale appended as-is.
         */
        fun localizedAddress(address: String, locale: String): String {
            val dot = address.lastIndexOf('.')
            val slash = address.lastIndexOf('/')
            return if (dot > slash) address.substring(0, dot) + ".$locale" + address.substring(dot)
            else "$address.$locale"
        }
    }
}

data class LoadedScene(
    val address: String,
    val locale: String?,
    val program: StoryProgram,
)
