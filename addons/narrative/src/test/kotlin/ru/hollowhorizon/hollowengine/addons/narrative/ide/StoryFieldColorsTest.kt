package ru.hollowhorizon.hollowengine.addons.narrative.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoryFieldColorsTest {
    /** A constant shown without its package takes the colors of the tail of what was analyzed. */
    @Test
    fun `a field showing the tail of a qualified name is colored from where it starts`() {
        val colors = StoryFieldColors("a.b.Mood.CALM", emptyList())
        assertEquals(0, colors.lead("a.b.Mood.CALM"))
        assertEquals(4, colors.lead("Mood.CALM"))
        assertNull(colors.lead("ood.CALM"))
        assertNull(colors.lead("Mood.ANGRY"))
        assertNull(colors.lead(""))
    }
}
