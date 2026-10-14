package ru.hollowhorizon.hollowengine.common.scripting.execution

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class ScriptExecutionStatus { IDLE, STARTING, RUNNING, STOPPING }

/** Reserves a script before compilation starts and retains that reservation until cancellation finishes. */
internal class ScriptRunTracker(private val scope: CoroutineScope, private val changed: () -> Unit = {}) {
    private class Entry(val job: Job) {
        @Volatile var running = false
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    fun paths(): Set<String> = entries.keys.toSet()

    fun start(path: String, execute: suspend () -> Unit): Boolean {
        val job = scope.launch(start = CoroutineStart.LAZY) { execute() }
        val entry = Entry(job)
        if (entries.putIfAbsent(path, entry) != null) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion { if (entries.remove(path, entry)) changed() }
        changed()
        job.start()
        return true
    }

    fun markRunning(path: String) { entries[path]?.let { it.running = true; changed() } }
    fun stop(path: String) { entries[path]?.let { it.job.cancel(); changed() } }

    /** Command execution is synchronous, but shares the same reservation and notifications. */
    fun <T> execute(path: String, action: () -> T): T {
        val job = Job(scope.coroutineContext[Job])
        val entry = Entry(job).apply { running = true }
        if (entries.putIfAbsent(path, entry) != null) {
            job.complete()
            error("Script '$path' is already running")
        }
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
        return when {
            entry.job.isCompleted -> ScriptExecutionStatus.IDLE
            entry.job.isCancelled -> ScriptExecutionStatus.STOPPING
            entry.running -> ScriptExecutionStatus.RUNNING
            else -> ScriptExecutionStatus.STARTING
        }
    }
}
