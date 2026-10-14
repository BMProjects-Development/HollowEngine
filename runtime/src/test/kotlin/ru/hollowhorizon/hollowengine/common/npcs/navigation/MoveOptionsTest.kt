package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MoveOptionsTest {
    @Test
    fun `invalid movement values fail immediately`() {
        assertFailsWith<IllegalArgumentException> { MoveOptions(speed = 0.0) }
        assertFailsWith<IllegalArgumentException> { MoveOptions(arrivalDistance = -1.0) }
        assertFailsWith<IllegalArgumentException> { MoveOptions(repathIntervalTicks = 0) }
        assertFailsWith<IllegalArgumentException> { MoveOptions(stuckTimeoutTicks = 0) }
        assertFailsWith<IllegalArgumentException> { MoveOptions(unreachableTimeoutTicks = 0) }
    }
}
