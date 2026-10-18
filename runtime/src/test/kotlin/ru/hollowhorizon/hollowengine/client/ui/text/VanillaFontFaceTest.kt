package ru.hollowhorizon.hollowengine.client.ui.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VanillaFontFaceTest {
    private val face = assertNotNull(
        UiVanillaFont.face("vanilla"),
        "the vanilla font must be readable from the classpath",
    )

    @Test
    fun `a space is four pixels, from the space provider rather than the blank ascii cell`() {
        assertEquals(4f / UiVanillaFont.EmPixels, face.advance(' '.code))
    }

    @Test
    fun `ascii advances match vanilla's own widths`() {
        assertEquals(6f / UiVanillaFont.EmPixels, face.advance('a'.code))
        assertEquals(2f / UiVanillaFont.EmPixels, face.advance('i'.code))
        assertEquals(6f / UiVanillaFont.EmPixels, face.advance('A'.code))
    }

    @Test
    fun `an uncovered codepoint still advances, so text never collapses`() {
        assertTrue(face.advance('漢'.code) > 0f, "an unknown glyph falls back rather than measuring zero")
    }

    /**
     * Emoji are surrogate pairs in a Kotlin string. Measured a `Char` at a time they would count as
     * two unknown characters, so the text would reserve twice the width of the one glyph drawn.
     */
    @Test
    fun `an emoji measures as one character, not as two surrogate halves`() {
        val emoji = "😀"
        assertEquals(2, emoji.length, "it really is a surrogate pair")

        // Uncovered, so it falls back - and one fallback glyph, not one per half.
        assertEquals(
            face.advance(emoji.codePointAt(0)) * UiVanillaFont.EmPixels,
            face.width(emoji, UiVanillaFont.EmPixels),
            "one replacement glyph wide, not two",
        )
    }

    @Test
    fun `a font that does not exist resolves to nothing rather than throwing`() {
        assertEquals(null, UiVanillaFont.face("vanilla:does_not_exist"))
    }
}
