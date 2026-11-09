package ru.hollowhorizon.hollowengine.client.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiArithmeticTest {
    @Test
    fun `arithmetic keeps the order of operations`() {
        assertEquals(1200.0, UiArithmetic.evaluate("20 * 60"))
        assertEquals(7.0, UiArithmetic.evaluate("1 + 2 * 3"))
        assertEquals(9.0, UiArithmetic.evaluate("(1 + 2) * 3"))
        assertEquals(-2.5, UiArithmetic.evaluate("-5 / 2"))
        assertEquals(1.0, UiArithmetic.evaluate("7 % 3"))
        assertEquals(3.5, UiArithmetic.evaluate("1.5f + 2L"))
    }

    @Test
    fun `anything but arithmetic on numbers is left alone`() {
        assertNull(UiArithmetic.evaluate("speed * 2"))
        assertNull(UiArithmetic.evaluate("(1 + 2"))
        assertNull(UiArithmetic.evaluate("1 +"))
        assertNull(UiArithmetic.evaluate("1 / 0"))
        assertNull(UiArithmetic.evaluate(""))
    }
}
