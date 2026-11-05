package ru.hollowhorizon.hollowengine.addons.narrative.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryTextTest {
    private val keys = mapOf("mod.note" to "записка", "mod.line" to "Вся реплика").let { it::get }

    @Test
    fun `a whole line and inserts are translated, the rest stays as written`() {
        assertEquals("Вся реплика", StoryText.localized("mod.line", keys))
        assertEquals("Текст: записка, {mod.unknown}.", StoryText.localized("Текст: {mod.note}, {mod.unknown}.", keys))
        assertEquals("Текст с точкой. И {name}", StoryText.localized("Текст с точкой. И {name}", keys))
    }

    @Test
    fun `plain sentences do not count as keys`() {
        assertTrue(StoryText.hasKeys("mod.line"))
        assertTrue(StoryText.hasKeys("Текст {mod.note}"))
        assertFalse(StoryText.hasKeys("Конец."))
        assertFalse(StoryText.hasKeys("Hello there. Bye"))
    }
}
