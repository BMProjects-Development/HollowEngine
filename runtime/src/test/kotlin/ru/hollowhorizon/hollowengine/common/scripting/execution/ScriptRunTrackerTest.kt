package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptRunTrackerTest {
    @Test
    fun `worker child completion cannot publish game state from its worker or release the instance early`() = runTest {
        val owner = Thread.currentThread()
        val notifications = mutableListOf<Thread>()
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        lateinit var running: Job
        val tracker = ScriptRunTracker(this) { notifications += Thread.currentThread() }
        try {
            tracker.start("script") {
                val context = currentCoroutineContext()
                running = context.job
                CoroutineScope(context).launch(Dispatchers.Default) { release.await() }
            }
            runCurrent()
            running.invokeOnCompletion { completed.countDown() }
            tracker.markRunning("script")
            assertFalse(tracker.start("script") { error("duplicate") })
            assertEquals(ScriptExecutionStatus.RUNNING, tracker.status("script"))
            val before = notifications.size
            release.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(before, notifications.size, "Worker completion must wait for the owner to observe it")
            assertTrue(notifications.all { it === owner })
            assertEquals(ScriptExecutionStatus.IDLE, tracker.status("script"))
            assertTrue(tracker.paths().isEmpty())
            assertTrue(tracker.start("script") {})
            advanceUntilIdle()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a closed owner cannot start either asynchronous or command execution`() = runTest {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val tracker = ScriptRunTracker(owner)
        owner.cancel()
        assertFalse(tracker.start("script") { error("closed owner") })
        var executed = false
        assertTrue(runCatching { tracker.execute("script") { executed = true } }.isFailure)
        assertFalse(executed)
        assertTrue(tracker.paths().isEmpty())
    }

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
