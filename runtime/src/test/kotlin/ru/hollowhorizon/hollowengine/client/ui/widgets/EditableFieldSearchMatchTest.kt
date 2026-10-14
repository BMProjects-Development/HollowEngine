package ru.hollowhorizon.hollowengine.client.ui.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditableFieldSearchMatchTest {
    private val text = "one two\nthree\nfour two"
    private val lines = editableFieldLines(text)

    @Test
    fun `a match lands on its own row in that row's columns`() {
        val buckets = bucketSearchMatchesByLine(lines, listOf(4..6), active = null)
        assertEquals(listOf(EditableFieldSearchMatch(4, 7, active = false)), buckets[0].toList())
        assertTrue(buckets[1].isEmpty())
        assertTrue(buckets[2].isEmpty())
    }

    @Test
    fun `only the active match is marked`() {
        val second = text.indexOf("two", startIndex = 5)
        val buckets = bucketSearchMatchesByLine(
            lines,
            listOf(4..6, second until second + 3),
            active = second until second + 3,
        )
        assertEquals(false, buckets[0].single().active)
        assertEquals(true, buckets[2].single().active)
    }

    @Test
    fun `a match spanning a line break is split across both rows`() {
        // "two\nthree" — one range covering the newline.
        val buckets = bucketSearchMatchesByLine(lines, listOf(4..12), active = null)
        assertEquals(EditableFieldSearchMatch(4, 7, active = false), buckets[0].single())
        assertEquals(EditableFieldSearchMatch(0, 5, active = false), buckets[1].single())
    }

}
