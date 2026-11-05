package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinSyntax

/**
 * The Kotlin written into a story as the Kotlin parser reads it. Fields ask about the same few lines every time they are
 * built, so what it said is kept. While scripting starts up there is no parser, and every answer is null.
 */
object StoryKotlin {
    private const val KEPT = 1024
    private val known = object : LinkedHashMap<String, KotlinSyntax>(KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KotlinSyntax>) = size > KEPT
    }

    val isAvailable: Boolean get() = syntax("") != null

    fun syntax(text: String): KotlinSyntax? = synchronized(known) {
        known[text] ?: ScriptingEnvironment.currentOrNull()?.analyzer?.kotlinSyntax(text)?.also { known[text] = it }
    }

    /** [text] split at the commas between arguments of a call, or null when it does not read as those. */
    fun arguments(text: String): List<String>? = syntax("list($text)")?.arguments

    /** [text] with what calls the variable [old] calling it [name]. */
    fun renamed(text: String, old: String, name: String): String {
        val uses = syntax(text)?.names.orEmpty().filter { it.name == old }
        return uses.foldRight(text) { use, code -> code.replaceRange(use.start, use.end, name) }
    }
}
