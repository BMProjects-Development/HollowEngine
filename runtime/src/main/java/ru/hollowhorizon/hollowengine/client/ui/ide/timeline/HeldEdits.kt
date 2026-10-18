package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoStep

/**
 * Edits held over a timeline at the playhead, as steps of the file's history.
 */
class HeldEdits<T : Any>(
    private val history: UndoHistory,
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val isCurrent: () -> Boolean,
) {
    /** Makes what [change] does to the hold a step of its own, which folds into the one before while edits go on. */
    fun record(change: () -> Unit) {
        val before = read()
        change()
        history.record(Step(this, before), MERGE_KEY)
    }

    /** Makes what [change] does to the hold a part of the step just recorded, the edit of the file it goes with. */
    fun attach(change: () -> Unit) {
        val before = read()
        change()
        history.attach(Step(this, before))
    }

    fun forget() = history.removeAll { it is Step<*> && it.owner === this }

    private class Step<T : Any>(val owner: HeldEdits<T>, private val before: T) : UndoStep {
        private var after: T? = null

        override val label: UndoLabel get() = LABEL

        override val isEmpty: Boolean get() = owner.read() == before

        override fun undo(): Boolean {
            if (!owner.isCurrent()) return false
            after = owner.read()
            owner.write(before)
            return true
        }

        override fun redo(): Boolean {
            if (!owner.isCurrent()) return false
            owner.write(after ?: return false)
            return true
        }

        override fun merge(next: UndoStep): UndoStep? = takeIf { next is Step<*> && next.owner === owner }
    }

    private companion object {
        const val MERGE_KEY = "held-edit"
        val LABEL = UndoLabel("${UndoLabel.LANG}.held_edit")
    }
}
