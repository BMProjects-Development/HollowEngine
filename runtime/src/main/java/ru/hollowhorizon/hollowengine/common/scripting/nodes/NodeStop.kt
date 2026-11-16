package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlin.coroutines.EmptyCoroutineContext

/** Runs stop handlers on the owner dispatcher after every child of the script has finished. */
internal class NodeStop(
    scriptJob: Job,
    private val dispatcher: CoroutineDispatcher,
    private val reportFailure: (Exception) -> Unit,
) {
    val completion: CompletableJob = Job()
    private val lock = Any()
    private val handlers = mutableListOf<() -> Unit>()
    private var started = false

    init {
        scriptJob.invokeOnCompletion {
            val task = Runnable { runHandlers() }
            if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) dispatcher.dispatch(EmptyCoroutineContext, task)
            else task.run()
        }
    }

    fun add(handler: () -> Unit) {
        synchronized(lock) {
            check(!started) { "Cannot register an onStop handler after the script has stopped" }
            handlers += handler
        }
    }

    private fun runHandlers() {
        val pending = synchronized(lock) {
            started = true
            handlers.toList().also { handlers.clear() }
        }
        try {
            pending.forEach { handler ->
                try {
                    handler()
                } catch (exception: Exception) {
                    reportFailure(exception)
                }
            }
        } finally {
            completion.complete()
        }
    }
}

/** Tracks callbacks even after their script or entity has been removed from its manager. */
internal class NodeStopRegistry {
    private val jobs = mutableSetOf<Job>()

    fun track(job: Job) {
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
    }

    fun pending(): List<Job> = synchronized(jobs) { jobs.toList() }
}
