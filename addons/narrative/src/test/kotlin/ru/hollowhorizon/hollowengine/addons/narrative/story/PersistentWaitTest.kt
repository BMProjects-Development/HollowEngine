package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PersistentWaitTest {
    @Test
    fun `game-time wait resumes its stored deadline`() = runTest {
        val tag = CompoundTag()
        var tick = 100L
        val interrupted = runCatching {
            waitPersistent(tag, "quest", 5, { tick }) {
                tick += 2
                throw CancellationException("restart")
            }
        }.exceptionOrNull()

        assertIs<CancellationException>(interrupted)

        var resumedTicks = 0
        waitPersistent(tag, "quest", 50, { tick }) {
            tick++
            resumedTicks++
        }

        assertEquals(3, resumedTicks)
        assertTrue(tag.isEmpty)
    }

    @Test
    fun `realtime wait includes elapsed offline time`() = runTest {
        val tag = CompoundTag()
        var now = 1_000L
        val interrupted = runCatching {
            waitUntil(tag, "daily", 2_000L, { now }) { requestedDelay ->
                assertEquals(1_000L, requestedDelay)
                now += 250L
                throw CancellationException("shutdown")
            }
        }.exceptionOrNull()

        assertIs<CancellationException>(interrupted)

        var resumedDelay = 0L
        waitUntil(tag, "daily", 10_000L, { now }) { requestedDelay ->
            resumedDelay += requestedDelay
            now += requestedDelay
        }

        assertEquals(750L, resumedDelay)
        assertTrue(tag.isEmpty)
    }
}
