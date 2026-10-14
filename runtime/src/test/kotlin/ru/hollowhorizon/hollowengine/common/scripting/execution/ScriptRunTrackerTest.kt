package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptRunTrackerTest {
    @Test
    fun `short command runs notify both transitions and share reservations with editor runs`() = runTest {
        val states = mutableListOf<ScriptExecutionStatus>()
        lateinit var tracker: ScriptRunTracker
        tracker = ScriptRunTracker(this) { states += tracker.status("script") }
        assertEquals(42, tracker.execute("script") {
            assertFalse(tracker.start("script") { error("duplicate") })
            42
        })
        assertEquals(listOf(ScriptExecutionStatus.RUNNING, ScriptExecutionStatus.IDLE), states)
        assertTrue(tracker.paths().isEmpty())
    }

    @Test
    fun `a script is reserved before compilation and double clicks cannot duplicate it`() = runTest {
        val tracker = ScriptRunTracker(this)
        var starts = 0
        assertTrue(tracker.start("script") { starts++; awaitCancellation() })
        assertFalse(tracker.start("script") { starts++ })
        assertEquals(ScriptExecutionStatus.STARTING, tracker.status("script"))
        runCurrent()
        tracker.markRunning("script")
        assertEquals(ScriptExecutionStatus.RUNNING, tracker.status("script"))
        assertEquals(1, starts)
        tracker.stop("script")
        advanceUntilIdle()
        assertEquals(ScriptExecutionStatus.IDLE, tracker.status("script"))
    }

    @Test
    fun `stopping retains the reservation until non cancellable cleanup completes`() = runTest {
        val tracker = ScriptRunTracker(this)
        tracker.start("script") {
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(100) } }
        }
        runCurrent()
        tracker.stop("script")
        runCurrent()
        assertEquals(ScriptExecutionStatus.STOPPING, tracker.status("script"))
        assertFalse(tracker.start("script") {})
        advanceUntilIdle()
        assertTrue(tracker.start("script") {})
        advanceUntilIdle()
        assertEquals(ScriptExecutionStatus.IDLE, tracker.status("script"))
    }

    @Test
    fun `completed plain scripts release their instance and other scripts can run independently`() = runTest {
        val tracker = ScriptRunTracker(this)
        assertTrue(tracker.start("first") { delay(10) })
        assertTrue(tracker.start("second") { delay(20) })
        advanceUntilIdle()
        assertEquals(ScriptExecutionStatus.IDLE, tracker.status("first"))
        assertEquals(ScriptExecutionStatus.IDLE, tracker.status("second"))
        assertTrue(tracker.start("first") {})
        advanceUntilIdle()
    }
}
