package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeOfDayValueFormatterTest {
    @Test
    fun `time of day formatter follows the minecraft clock`() {
        assertEquals("06:00", TimeOfDayValueFormatter.format(0f))
        assertEquals("12:00", TimeOfDayValueFormatter.format(6_000f))
        assertEquals("00:00", TimeOfDayValueFormatter.format(18_000f))
        assertEquals("05:59", TimeOfDayValueFormatter.format(23_999f))
        assertEquals("07:00", TimeOfDayValueFormatter.format(25_000f))
    }
}
