package ru.hollowhorizon.hollowengine.common.coroutines

import kotlinx.coroutines.*
import ru.hollowhorizon.hollowengine.HollowEngine
import java.lang.Runnable
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparable
import kotlin.Int
import kotlin.Long
import kotlin.OptIn
import kotlin.String
import kotlin.Throwable
import kotlin.Unit
import kotlin.collections.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.synchronized
import kotlin.with

/**
 * Runs coroutines on [thread], one batch per [runTasks] call. The thread may be bound later with [bind]:
 * until then everything dispatched simply waits in the queue.
 */
@OptIn(InternalCoroutinesApi::class)
class SingleThreadDispatcher(private val name: String, thread: Thread?) : CoroutineDispatcher(), Delay {
    private val lock = Any()
    private val queue = ArrayDeque<Runnable>()
    private val delayedQueue = PriorityQueue<ScheduledTask>()
    private var currentTick = 0L
    private var isShutdown = false

    @Volatile
    private var thread: Thread? = thread

    fun bind(thread: Thread) {
        this.thread = thread
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(lock) {
            if (isShutdown) return
            queue.addLast(block)
        }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return Thread.currentThread() !== thread
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val ticks = (timeMillis + 49) / 50
        val scheduledTask: ScheduledTask

        synchronized(lock) {
            if (isShutdown) {
                cancelOnShutdown(continuation)
                return
            }

            scheduledTask = ScheduledTask(
                targetTick = currentTick + ticks,
                task = { with(continuation) { resumeUndispatched(Unit) } }
            )
            delayedQueue.add(scheduledTask)
        }

        continuation.invokeOnCancellation {
            synchronized(lock) {
                delayedQueue.remove(scheduledTask)
            }
        }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val ticks = (timeMillis + 49) / 50
        val scheduledTask: ScheduledTask

        synchronized(lock) {
            if (isShutdown) return DisposableHandle {}

            scheduledTask = ScheduledTask(targetTick = currentTick + ticks, task = block)
            delayedQueue.add(scheduledTask)
        }

        return DisposableHandle {
            synchronized(lock) {
                delayedQueue.remove(scheduledTask)
            }
        }
    }

    fun runTasks() {
        synchronized(lock) {
            if (isShutdown) return

            currentTick++
            while (delayedQueue.isNotEmpty()) {
                val head = delayedQueue.peek()
                if (head.targetTick <= currentTick) {
                    delayedQueue.poll()
                    queue.addLast(head.task)
                } else {
                    break
                }
            }
        }

        var tasksToRun = synchronized(lock) { queue.size }
        while (tasksToRun > 0) {
            val task = synchronized(lock) { queue.removeFirstOrNull() } ?: break
            try {
                task.run()
            } catch (e: CancellationException) {
                // Scope shutdown is expected during server stop.
            } catch (e: Throwable) {
                HollowEngine.LOGGER.error("Exception while running coroutine!", e)
            }
            tasksToRun--
        }
    }

    /** Keeps the owner dispatcher alive while its cancelled scope finishes cooperative cleanup. */
    internal fun awaitCompletion(jobs: Collection<Job>, timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "Timeout must not be negative" }
        val waiter = Thread.currentThread()
        val completions = jobs.map { job -> job.invokeOnCompletion { LockSupport.unpark(waiter) } }
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val started = System.nanoTime()
        var nextTick = started
        try {
            while (true) {
                if (jobs.all { it.isCompleted }) return true
                if (waiter.isInterrupted) return false
                val now = System.nanoTime()
                val remaining = timeoutNanos - (now - started)
                if (remaining <= 0L) return false
                if (now - nextTick >= 0L) {
                    runTasks()
                    nextTick = now + TICK_NANOS
                } else {
                    LockSupport.parkNanos(minOf(remaining, nextTick - now))
                }
            }
        } finally {
            completions.forEach(DisposableHandle::dispose)
        }
    }

    fun shutdown() {
        synchronized(lock) {
            if (isShutdown) return
            isShutdown = true
            queue.clear()
            delayedQueue.clear()
        }
    }

    override fun toString(): String {
        return name
    }

    private fun cancelOnShutdown(continuation: CancellableContinuation<Unit>) {
        try {
            continuation.cancel(CancellationException("Dispatcher $name is shutdown"))
        } catch (_: CancellationException) {
            // The continuation may already be completing when the dispatcher shuts down.
        }
    }

    private data class ScheduledTask(
        val targetTick: Long,
        val task: Runnable,
        val onShutdown: (() -> Unit)? = null,
    ) : Comparable<ScheduledTask> {
        override fun compareTo(other: ScheduledTask): Int = targetTick.compareTo(other.targetTick)
    }

    private companion object {
        const val TICK_NANOS = 50_000_000L
    }
}

