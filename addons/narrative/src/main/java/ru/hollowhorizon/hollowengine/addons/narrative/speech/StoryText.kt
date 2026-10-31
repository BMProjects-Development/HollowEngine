package ru.hollowhorizon.hollowengine.addons.narrative.speech

import net.minecraft.locale.Language

/**
 * Translations in story text: a whole line can be a translation key, and `{modid.some.key}` inside a line puts one in.
 * Keys are resolved where the text is shown, so each player reads their own language.
 */
object StoryText {
    private const val KEY = """[\w-]+(?:\.[\w-]+)+"""
    private val WHOLE_KEY = Regex(KEY)
    private val INSERTED_KEY = Regex("""\{($KEY)}""")

    /**
     * [markup] with its keys translated; a key [translation] does not know stays as written.
     *
     * @param translation the text of a key, null when there is none.
     */
    fun localized(markup: String, translation: (String) -> String? = ::known): String {
        translation(markup.trim())?.let { return it }
        return INSERTED_KEY.replace(markup) { translation(it.groupValues[1]) ?: it.value }
    }

    /** Whether [text] still holds a key: a server that lacks the language of a mod knows nothing of how long it reads. */
    fun hasKeys(text: String): Boolean = WHOLE_KEY.matches(text.trim()) || INSERTED_KEY.containsMatchIn(text)

    private fun known(key: String): String? = Language.getInstance().takeIf { it.has(key) }?.getOrDefault(key)
}
