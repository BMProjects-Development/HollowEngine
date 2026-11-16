import kotlinx.coroutines.*
import ru.hollowhorizon.hollowengine.common.coroutines.SingleThreadDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleThreadDispatcherTests {
    @Test
    fun `timeout executes on target tick`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        var hits = 0

        dispatcher.invokeOnTimeout(50, { hits++ }, EmptyCoroutineContext)
        dispatcher.runTasks()

        assertEquals(1, hits)
    }

    @Test
    fun `shutdown drops queued and delayed work`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        var immediateRan = false
        var delayedRan = false

        dispatcher.dispatch(EmptyCoroutineContext) { immediateRan = true }
        dispatcher.invokeOnTimeout(50, { delayedRan = true }, EmptyCoroutineContext)

        dispatcher.shutdown()
        repeat(3) { dispatcher.runTasks() }

        assertFalse(immediateRan)
        assertFalse(delayedRan)
    }

    @Test
    fun `delay continuation does not resume after shutdown`() {
        val dispatcher = SingleThreadDispatcher("test", Thread())
        var completed = false

        val job = CoroutineScope(Job() + dispatcher).launch {
            delay(50)
            completed = true
        }

        dispatcher.runTasks()
        dispatcher.shutdown()
        repeat(3) { dispatcher.runTasks() }

        assertFalse(completed)
        job.cancel()
    }

    @Test
    fun `shutdown does not throw when delayed continuation is cancelled`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        val job = CoroutineScope(Job() + dispatcher).launch {
            delay(50)
        }

        dispatcher.runTasks()
        val shutdownResult = runCatching { dispatcher.shutdown() }

        assertTrue(shutdownResult.isSuccess)
        job.cancel()
    }

    @Test
    fun `cancelled scope finishes suspended cleanup before dispatcher closes`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val events = mutableListOf<String>()
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(50)
                    events += "cleanup"
                }
            }
        }
        scope.cancel()
        assertTrue(dispatcher.awaitCompletion(listOf(scope.coroutineContext[Job]!!), 2_000))
        assertEquals(listOf("cleanup"), events)
        dispatcher.shutdown()
    }

    @Test
    fun `cleanup that never finishes reaches the timeout`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    release.await()
                    cleaned = true
                }
            }
        }
        scope.cancel()
        assertFalse(dispatcher.awaitCompletion(listOf(scope.coroutineContext[Job]!!), 20))
        assertFalse(cleaned)
        release.complete(Unit)
        assertTrue(dispatcher.awaitCompletion(listOf(scope.coroutineContext[Job]!!), 2_000))
        assertTrue(cleaned)
        dispatcher.shutdown()
    }

    @Test
    fun `scope is not considered finished while a stop callback runs on a worker`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val callbackStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = scope.launch(Dispatchers.Default) { awaitCancellation() }
        worker.invokeOnCompletion {
            callbackStarted.countDown()
            release.await()
        }
        try {
            scope.cancel()
            assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))
            assertFalse(dispatcher.awaitCompletion(listOf(scope.coroutineContext[Job]!!), 20))
        } finally {
            release.countDown()
            assertTrue(dispatcher.awaitCompletion(listOf(scope.coroutineContext[Job]!!), 2_000))
            dispatcher.shutdown()
        }
    }

    @Test
    fun `shutdown waits for independently owned jobs too`() {
        val dispatcher = SingleThreadDispatcher("test", Thread.currentThread())
        val server = CoroutineScope(SupervisorJob() + dispatcher)
        val reload = CoroutineScope(SupervisorJob() + dispatcher)
        val release = CompletableDeferred<Unit>()
        reload.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        server.cancel()
        reload.cancel()
        val jobs = listOf(server.coroutineContext[Job]!!, reload.coroutineContext[Job]!!)
        assertFalse(dispatcher.awaitCompletion(jobs, 20))
        release.complete(Unit)
        assertTrue(dispatcher.awaitCompletion(jobs, 2_000))
        dispatcher.shutdown()
    }
}
