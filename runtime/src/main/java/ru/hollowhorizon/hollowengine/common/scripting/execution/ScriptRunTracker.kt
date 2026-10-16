package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable

@Serializable
enum class ScriptExecutionStatus { IDLE, STARTING, RUNNING, STOPPING }

/**
 * Owned by the scope's game thread. A reservation lasts until the job and all its children finish.
 * [changed] always runs on that thread, also when a job's last child finishes on a worker.
 */
internal class ScriptRunTracker(private val scope: CoroutineScope, private val changed: () -> Unit = {}) {
    private class Entry(val job: Job) {
        var running = false
    }
    private val entries = mutableMapOf<String, Entry>()

    fun paths(): Set<String> {
        removeCompleted()
        return entries.keys.toSet()
    }

    fun start(path: String, execute: suspend () -> Unit): Boolean {
        if (!scope.isActive) return false
        if (status(path) != ScriptExecutionStatus.IDLE) return false
        val job = scope.launch(start = CoroutineStart.LAZY) { execute() }
        val entry = Entry(job)
        entries[path] = entry
        job.invokeOnCompletion { scope.launch { changed() } }
        changed()
        job.start()
        return true
    }

    fun markRunning(path: String) { entries[path]?.let { it.running = true; changed() } }
    fun stop(path: String) { entries[path]?.let { it.job.cancel(); changed() } }

    /** Command execution is synchronous, but shares the same reservation and notifications. */
    fun <T> execute(path: String, action: () -> T): T {
        check(scope.isActive) { "Script execution scope is closed" }
        check(status(path) == ScriptExecutionStatus.IDLE) { "Script '$path' is already running" }
        val job = Job(scope.coroutineContext[Job])
        val entry = Entry(job).apply { running = true }
        entries[path] = entry
        changed()
        try {
            return action()
        } finally {
            job.complete()
            entries.remove(path, entry)
            changed()
        }
    }

    fun status(path: String): ScriptExecutionStatus {
        val entry = entries[path] ?: return ScriptExecutionStatus.IDLE
        if (entry.job.isCompleted) {
            entries.remove(path)
            return ScriptExecutionStatus.IDLE
        }
        return when {
            entry.job.isCancelled -> ScriptExecutionStatus.STOPPING
            entry.running -> ScriptExecutionStatus.RUNNING
            else -> ScriptExecutionStatus.STARTING
        }
    }

    private fun removeCompleted() {
        entries.entries.removeAll { it.value.job.isCompleted }
    }
}
