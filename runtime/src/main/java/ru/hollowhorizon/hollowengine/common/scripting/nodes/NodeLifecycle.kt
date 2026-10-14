package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Serializes replacement of a node with completion of its old scope, including `onStop`. */
internal class NodeLifecycle(private val scope: () -> CoroutineScope) {
    private val stopping = ConcurrentHashMap<String, Job>()
    private val pending = ConcurrentHashMap<String, Job>()

    fun pendingPaths(): Set<String> = pending.keys.toSet()
    fun stoppingPaths(): Set<String> = stopping.keys.toSet()

    fun isPending(path: String): Boolean = pending.containsKey(path)
    fun isStopping(path: String): Boolean = stopping[path]?.isCompleted == false

    fun cancelStart(path: String) {
        pending.remove(path)?.cancel()
    }

    fun stop(path: String, job: Job) {
        cancelStart(path)
        if (!job.isCompleted) {
            stopping[path] = job
            job.invokeOnCompletion { stopping.remove(path, job) }
        }
        job.cancel()
    }

    fun start(path: String, start: () -> Unit) {
        cancelStart(path)
        if (!scope().isActive) return
        val previous = stopping[path]?.takeUnless { it.isCompleted }
        if (previous == null) {
            stopping.remove(path)
            start()
            return
        }

        val next = scope().launch(start = CoroutineStart.LAZY) {
            previous.join()
            ensureActive()
            stopping.remove(path, previous)
            pending.remove(path)
            start()
        }
        pending[path] = next
        next.invokeOnCompletion { pending.remove(path, next) }
        next.start()
    }
}
