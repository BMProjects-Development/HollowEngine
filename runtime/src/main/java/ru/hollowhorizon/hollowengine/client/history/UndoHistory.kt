package ru.hollowhorizon.hollowengine.client.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Undo and redo for one thing being edited: a file, the world, a text field.
 */
class UndoHistory(
    private val limit: Int = DEFAULT_LIMIT,
    private val budget: Int = Int.MAX_VALUE,
    private val mergeWindowNanos: Long = MERGE_WINDOW_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** One value, so a reader on the composition thread never sees steps and position disagree. */
    private var state by mutableStateOf(State(emptyList(), 0, 0))

    val steps: List<UndoStep> get() = state.steps

    /** How many of [steps] are done. */
    val position: Int get() = state.position

    val canUndo: Boolean get() = state.position > 0
    val canRedo: Boolean get() = state.position < state.steps.size

    /** Whether everything is as it was at the last [markSaved]. */
    val isAtSaved: Boolean get() = state.position == state.saved

    private var transaction: Transaction? = null
    private var lastMergeKey: String? = null
    private var lastRecordNanos = 0L

    /**
     * Remembers [step], done just now. Inside a transaction it waits for the [commit]; otherwise it folds into
     * the step before when both carry [mergeKey] and came close enough together.
     */
    fun record(step: UndoStep, mergeKey: String? = null) {
        transaction?.let {
            it.steps += step
            return
        }

        val now = nanoTime()
        val current = state
        val last = current.steps.lastOrNull()?.takeIf { current.position == current.steps.size }
        val merges =
            last != null && mergeKey != null && mergeKey == lastMergeKey && now - lastRecordNanos <= mergeWindowNanos
        lastMergeKey = mergeKey
        lastRecordNanos = now

        val merged = if (merges) last.merge(step) else null
        if (merged != null) {
            state = current.copy(steps = current.steps.dropLast(1) + merged)
            return
        }
        push(step)
    }

    /** Starts a step that everything recorded until the matching [commit] belongs to. Transactions nest. */
    fun begin(label: UndoLabel? = null) {
        val open = transaction
        if (open != null) open.depth++ else transaction = Transaction(label)
    }

    fun commit() {
        val open = transaction ?: return
        if (--open.depth > 0) return
        transaction = null
        lastMergeKey = null
        val step = open.build() ?: return
        if (!step.isEmpty) push(step)
    }

    inline fun <R> transaction(label: UndoLabel? = null, block: () -> R): R {
        begin(label)
        try {
            return block()
        } finally {
            commit()
        }
    }

    /** Takes back the newest done step; one that can no longer be taken is dropped and the one before tried. */
    fun undo(): Boolean {
        if (transaction != null) return false
        lastMergeKey = null
        while (state.position > 0) {
            val index = state.position - 1
            if (state.steps[index].undo()) {
                state = state.copy(position = index)
                return true
            }
            drop(index)
        }
        return false
    }

    fun redo(): Boolean {
        if (transaction != null) return false
        lastMergeKey = null
        while (state.position < state.steps.size) {
            val index = state.position
            if (state.steps[index].redo()) {
                state = state.copy(position = index + 1)
                return true
            }
            drop(index)
        }
        return false
    }

    /** Undoes or redoes until [target] steps are done, as a click in the history window asks. */
    fun moveTo(target: Int) {
        while (state.position > target) if (!undo()) break
        while (state.position < target) if (!redo()) break
    }

    /** Keeps the next edit from folding into the last one, as when the caret moves between two words. */
    fun breakMerge() {
        lastMergeKey = null
    }

    fun markSaved() {
        state = state.copy(saved = state.position)
    }

    fun clear() {
        transaction = null
        lastMergeKey = null
        state = State(emptyList(), 0, 0)
    }

    /** Forgets every step [predicate] picks, done or not, as when what they change was changed elsewhere. */
    fun removeAll(predicate: (UndoStep) -> Boolean) {
        for (index in state.steps.indices.reversed()) {
            if (predicate(state.steps[index])) drop(index)
        }
    }

    private fun push(step: UndoStep) {
        val current = state
        var steps = current.steps.take(current.position) + step
        var saved = if (current.saved > current.position) UNKNOWN else current.saved
        var cost = steps.sumOf { it.cost }
        while (steps.size > 1 && (steps.size > limit || cost > budget)) {
            cost -= steps.first().cost
            steps = steps.drop(1)
            saved = if (saved <= 0) UNKNOWN else saved - 1
        }
        state = State(steps, steps.size, saved)
    }

    private fun drop(index: Int) {
        val current = state
        fun shift(at: Int) = if (at > index) at - 1 else at
        val saved = if (current.saved == UNKNOWN) UNKNOWN else shift(current.saved)
        state = State(current.steps.filterIndexed { i, _ -> i != index }, shift(current.position), saved)
    }

    private data class State(val steps: List<UndoStep>, val position: Int, val saved: Int)

    private class Transaction(val label: UndoLabel?) {
        var depth = 1
        val steps = ArrayList<UndoStep>()

        fun build(): UndoStep? {
            val folded = ArrayList<UndoStep>()
            for (step in steps) {
                val merged = folded.lastOrNull()?.merge(step)
                if (merged != null) folded[folded.lastIndex] = merged else folded += step
            }
            return when (folded.size) {
                0 -> null
                1 if label == null -> folded.single()
                else -> CompositeStep(label ?: folded.first().label, folded)
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MERGE_WINDOW_NANOS = 700_000_000L
        private const val UNKNOWN = -1
    }
}
