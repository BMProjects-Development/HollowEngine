package ru.hollowhorizon.hollowengine.common.scripting.source

import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import java.io.File

/** Turns the source of a script that is not written in Kotlin into Kotlin. */
fun interface ScriptTranslator {
    /** The Kotlin of the script [id], whose source is [source]; throws when there is none to give. */
    fun translate(id: ScriptId, source: File): String
}

/**
 * The sources an addon teaches the engine to compile, by the extension of their files. What a translator gives is
 * compiled as an ordinary script but stays the script [id]: what it reports and saves names the source.
 */
object ScriptTranslators {
    private val translators = HashMap<String, ScriptTranslator>()

    fun register(extension: String, translator: ScriptTranslator) {
        translators[extension] = translator
    }

    fun unregister(extension: String) {
        translators -= extension
    }

    /** The Kotlin file to compile for the script [id]: [source] itself unless a translator takes it. */
    internal fun kotlinSource(id: ScriptId, source: File): File {
        val translator = translatorOf(id) ?: return source
        val generated = ScriptCache.generatedSource(id)
        generated.parentFile.mkdirs()
        generated.writeText(translator.translate(id, source))
        return generated
    }

    /** Makes the Kotlin of [id] belong to it, which a compiled artifact names as its source even when it is cached. */
    internal fun bind(id: ScriptId) {
        if (translatorOf(id) != null) ScriptRegistry.bind(ScriptCache.generatedSource(id), id)
    }

    private fun translatorOf(id: ScriptId) = translators.entries.firstOrNull { id.path.endsWith(it.key) }?.value

    /** Every script the engine can compile. */
    internal fun compilable(): List<ScriptId> = ScriptRegistry.list().filter { id ->
        id.path.endsWith(KOTLIN_SCRIPT) || translators.keys.any(id.path::endsWith)
    }

    private const val KOTLIN_SCRIPT = ".kts"
}
