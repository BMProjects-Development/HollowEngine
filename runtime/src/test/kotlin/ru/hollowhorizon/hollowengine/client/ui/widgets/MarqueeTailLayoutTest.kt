package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarqueeTailLayoutTest {
    @Test
    fun `tail starts at the right edge before the first measurement`() {
        assertTrue(marqueeTailFitsApart(viewportWidth = 0f, leadWidth = 0f, tailWidth = 0f))
    }

    @Test
    fun `tail stays separate when leading content and gap fit`() {
        assertTrue(marqueeTailFitsApart(viewportWidth = 200f, leadWidth = 140f, tailWidth = 54f))
    }

    @Test
    fun `tail joins marquee when combined content does not fit`() {
        assertFalse(marqueeTailFitsApart(viewportWidth = 199f, leadWidth = 140f, tailWidth = 54f))
    }
}
