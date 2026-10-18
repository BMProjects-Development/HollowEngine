package ru.hollowhorizon.hollowengine.common.logging

import org.apache.logging.log4j.spi.StandardLevel
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class HollowLogEntry(
    val id: Long,
    val level: StandardLevel,
    val loggerName: String?,
    val message: String,
    val timeMillis: Long,
) {
    val displayMessage: String = message.flattenLogMessage()
}

data class HollowLogSnapshot(
    val revision: Long,
    val entries: List<HollowLogEntry>,
)

class HollowLogBuffer(private val capacity: Int) {
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<HollowLogEntry>(capacity)
    private var revision = 0L
    private var nextId = 0L

    init {
        require(capacity > 0) { "Log buffer capacity must be positive" }
    }

    fun append(level: StandardLevel, loggerName: String?, message: String, timeMillis: Long) {
        lock.withLock {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(
                HollowLogEntry(
                    id = nextId++,
                    level = level,
                    loggerName = loggerName,
                    message = message.take(MaxLogMessageLength),
                    timeMillis = timeMillis,
                ),
            )
            revision++
        }
    }

    fun snapshot(): HollowLogSnapshot = lock.withLock {
        HollowLogSnapshot(revision, entries.toList())
    }

    fun snapshotAfter(knownRevision: Long): HollowLogSnapshot? = lock.withLock {
        if (knownRevision == revision) null else HollowLogSnapshot(revision, entries.toList())
    }
}

object HollowLogStore {
    private val buffer = HollowLogBuffer(DefaultLogCapacity)

    fun append(level: StandardLevel, loggerName: String?, message: String, timeMillis: Long) {
        buffer.append(level, loggerName, message, timeMillis)
    }

    fun snapshot(): HollowLogSnapshot = buffer.snapshot()

    fun snapshotAfter(knownRevision: Long): HollowLogSnapshot? = buffer.snapshotAfter(knownRevision)
}

private const val DefaultLogCapacity = 10_000
private const val MaxLogMessageLength = 32 * 1024

internal fun String.flattenLogMessage(): String = lineSequence()
    .joinToString(LogLineSeparator) { line -> line.trimEnd() }

private const val LogLineSeparator = " | "
