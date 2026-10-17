package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.text.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `text-effects` used to accept bare keywords only. The parameterized forms have to keep the bare
 * ones working, every existing stylesheet writes `bold`, not `bold(0.0625)`.
 */
class TextEffectParsingTest {
    @Test
    fun `a bare keyword still yields the default-weighted effect`() {
        assertEquals(Bold(DefaultBoldWeight), parseTextEffect("bold"))
        assertEquals(Italic(DefaultItalicSkew), parseTextEffect("italic"))
        assertEquals(Underline(), parseTextEffect("underline"))
        assertEquals(Strikethrough(), parseTextEffect("strikethrough"))
    }

    @Test
    fun `arguments override the defaults`() {
        assertEquals(Bold(0.14f), parseTextEffect("bold(0.14)"))
        assertEquals(Italic(22f), parseTextEffect("italic(22)"))
    }

    @Test
    fun `a rule takes thickness, offset and color in that order`() {
        val underline = parseTextEffect("underline(0.09, 0.04, #FF5555)") as Underline
        assertEquals(0.09f, underline.thickness)
        assertEquals(0.04f, underline.offset)
        assertEquals(1f, underline.color?.red)
        assertEquals(0.33333334f, underline.color?.green)
    }

    @Test
    fun `omitted rule arguments fall back to the font's own metrics`() {
        // Zero is the sentinel the renderer reads as "ask the font", not a zero-thickness rule.
        val strikethrough = parseTextEffect("strikethrough()") as Strikethrough
        assertEquals(0f, strikethrough.thickness)
        assertEquals(0f, strikethrough.offset)
        assertNull(strikethrough.color)
    }

    @Test
    fun `a list of effects keeps every entry`() {
        val effects = parseTextEffects("bold(0.1), italic, underline(0.05)")
        assertEquals(3, effects.size)
        assertEquals(Bold(0.1f), effects[0])
        assertEquals(Italic(DefaultItalicSkew), effects[1])
        assertEquals(Underline(0.05f), effects[2])
    }
}
