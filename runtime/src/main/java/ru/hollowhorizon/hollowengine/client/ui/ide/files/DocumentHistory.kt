package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Undo and redo for a document the editor rewrites whole: what it was before each step, newest last.
 */
class DocumentHistory<T : Any>(private val limit: Int = DEFAULT_LIMIT) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    /** The document as it was when the gesture under way started. */
    private var gestureStart: T? = null
    private var lastMergeKey: String? = null
    private var lastEditNanos = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /**
     * Called with the document as it is, right before an edit replaces it. True when the edit starts a step of
     * its own, rather than joining the gesture under way or the edit before it.
     */
    fun beforeEdit(current: T, mergeKey: String? = null): Boolean {
        if (gestureStart != null) return false
        val now = System.nanoTime()
        val merges = mergeKey != null && mergeKey == lastMergeKey && now - lastEditNanos < MERGE_WINDOW_NANOS
        if (!merges) remember(current)
        lastMergeKey = mergeKey
        lastEditNanos = now
        return !merges
    }

    fun beginGesture(current: T) {
        if (gestureStart == null) gestureStart = current
    }

    /** True when the gesture changed the document, and so became a step. */
    fun endGesture(current: T): Boolean {
        val start = gestureStart ?: return false
        gestureStart = null
        lastMergeKey = null
        if (start == current) return false
        remember(start)
        return true
    }

    /** What to go back to from [current], or null when there is nothing. */
    fun undo(current: T): T? = step(undoStack, redoStack, current)

    fun redo(current: T): T? = step(redoStack, undoStack, current)

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        gestureStart = null
        lastMergeKey = null
        refresh()
    }

    private fun step(from: ArrayDeque<T>, to: ArrayDeque<T>, current: T): T? {
        val target = from.removeLastOrNull() ?: return null
        to.addLast(current)
        lastMergeKey = null
        refresh()
        return target
    }

    private fun remember(state: T) {
        undoStack.addLast(state)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        refresh()
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MERGE_WINDOW_NANOS = 700_000_000L
    }
}
