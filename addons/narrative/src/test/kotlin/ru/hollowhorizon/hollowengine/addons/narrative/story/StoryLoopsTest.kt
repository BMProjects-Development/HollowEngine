package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoryLoopsTest {
    @Test
    fun `progressions crossing the integer limits are counted without overflow`() {
        assertEquals(3, StoryLoops.count(Int.MIN_VALUE..Int.MAX_VALUE step Int.MAX_VALUE))
        assertEquals(3, StoryLoops.count(Int.MAX_VALUE downTo Int.MIN_VALUE step Int.MAX_VALUE))
        assertEquals(0, StoryLoops.count(1..0))
    }

    @Test
    fun `a progression too large for a saved index fails explicitly`() {
        assertFailsWith<IllegalArgumentException> { StoryLoops.count(Int.MIN_VALUE..Int.MAX_VALUE) }
    }
}
