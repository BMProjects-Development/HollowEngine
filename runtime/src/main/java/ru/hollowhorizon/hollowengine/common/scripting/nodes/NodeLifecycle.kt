package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Game-thread-owned replacement of a node after its old scope, including `onStop`, completes. */
internal class NodeLifecycle(private val scope: () -> CoroutineScope) {
    private val stopping = mutableMapOf<String, Job>()
    private val pending = mutableMapOf<String, Job>()

    fun pendingPaths(): Set<String> = pending.activePaths()
    fun stoppingPaths(): Set<String> = stopping.activePaths()

    fun isPending(path: String): Boolean = pending[path]?.isCompleted == false
    fun isStopping(path: String): Boolean = stopping[path]?.isCompleted == false

    fun cancelStart(path: String) {
        removeCompleted()
        pending.remove(path)?.cancel()
    }

    fun stop(path: String, job: Job) {
        cancelStart(path)
        if (!job.isCompleted) {
            stopping[path] = job
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
        next.start()
    }

    private fun MutableMap<String, Job>.activePaths(): Set<String> {
        removeCompleted()
        return keys.toSet()
    }

    private fun removeCompleted() {
        stopping.entries.removeAll { it.value.isCompleted }
        pending.entries.removeAll { it.value.isCompleted }
    }
}
