package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.*
import ru.hollowhorizon.hollowengine.common.coroutines.SingleThreadDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeStopTest {
    @Test
    fun `worker cleanup dispatches stop to owner before replacement`() {
        val owner = Thread.currentThread()
        val dispatcher = SingleThreadDispatcher("node stop", owner)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val node = SupervisorJob()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val events = mutableListOf<String>()
        val stop = NodeStop(node, dispatcher) { throw it }
        val registry = NodeStopRegistry()
        registry.track(stop.completion)
        stop.add {
            assertEquals(owner, Thread.currentThread())
            events += "stop"
        }
        node.invokeOnCompletion { finished.countDown() }
        CoroutineScope(node + Dispatchers.Default).launch {
            try {
                started.countDown()
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        val lifecycle = NodeLifecycle { scope }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            lifecycle.stop("script", node, stop.completion)
            lifecycle.start("script") { events += "start" }
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse(stop.completion.isCompleted)
            assertEquals(listOf(stop.completion), registry.pending())
            assertTrue(events.isEmpty())
            dispatcher.runTasks()
            assertTrue(stop.completion.isCompleted)
            assertTrue(registry.pending().isEmpty())
            assertEquals(listOf("stop", "start"), events)
        } finally {
            release.countDown()
            node.cancel()
            scope.coroutineContext[Job]?.cancel()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `failed stop handler is reported and remaining handlers still run`() {
        val dispatcher = SingleThreadDispatcher("node stop", Thread.currentThread())
        val node = SupervisorJob()
        val failures = mutableListOf<Exception>()
        val events = mutableListOf<String>()
        val stop = NodeStop(node, dispatcher, failures::add)
        stop.add { error("failed") }
        stop.add { events += "second" }
        node.cancel()
        assertTrue(stop.completion.isCompleted)
        assertEquals(listOf("second"), events)
        assertEquals("failed", failures.single().message)
        dispatcher.shutdown()
    }

    @Test
    fun `server shutdown waits for dispatched stop callbacks`() {
        val dispatcher = SingleThreadDispatcher("node stop", Thread.currentThread())
        val node = SupervisorJob()
        val finished = CountDownLatch(1)
        val events = mutableListOf<String>()
        val stop = NodeStop(node, dispatcher) { throw it }
        stop.add { events += "stop" }
        Thread {
            node.cancel()
            finished.countDown()
        }.start()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(stop.completion.isCompleted)
        assertTrue(dispatcher.awaitCompletion(listOf(stop.completion), 2_000))
        assertEquals(listOf("stop"), events)
        dispatcher.shutdown()
    }
}
