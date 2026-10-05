package ru.hollowhorizon.hollowengine.client.history

import ru.hollowhorizon.hollowengine.client.utils.lang

/** What a step is called in the history window: a lang key and what it names, read when shown. */
class UndoLabel(val key: String, vararg val args: String) {
    val text: String get() = if (args.isEmpty()) key.lang else key.lang(*args)

    companion object {
        const val LANG = "hollowengine.gui.history"

        /** A change nothing more is known about. */
        val EDIT = UndoLabel("$LANG.edit")
    }
}

/** One thing done that can be taken back and done again. */
interface UndoStep {
    val label: UndoLabel

    /** How much it weighs against [UndoHistory]'s budget, such as the pixels a brush stroke keeps. */
    val cost: Int get() = 1

    /** Whether it changed nothing after all, as a drag let go where it started. Asked only of the newest step. */
    val isEmpty: Boolean get() = false

    /** Takes it back. False when it no longer can, as for an entity gone since; the history then drops it. */
    fun undo(): Boolean

    fun redo(): Boolean

    /** One step standing for this and [next], done right after it, or null when they stay apart. */
    fun merge(next: UndoStep): UndoStep? = null
}

/**
 * A value rewritten whole: what it was before the step, and what it was when the step was taken back, so
 * it can be done again. Steps of one [owner] fold into the first, which holds the value from before them all.
 */
class SnapshotStep<T : Any>(
    private val owner: Any,
    override val label: UndoLabel,
    private val before: T,
    private val read: () -> T,
    private val write: (T) -> Unit,
) : UndoStep {
    private var after: T? = null

    override val isEmpty: Boolean get() = read() == before

    override fun undo(): Boolean {
        after = read()
        write(before)
        return true
    }

    override fun redo(): Boolean {
        write(after ?: return false)
        return true
    }

    override fun merge(next: UndoStep): UndoStep? = takeIf { next is SnapshotStep<*> && next.owner === owner }
}

/** Steps taken as one, such as everything a gesture changed across several owners. */
internal class CompositeStep(override val label: UndoLabel, private val steps: List<UndoStep>) : UndoStep {
    override val cost: Int get() = steps.sumOf { it.cost }

    override val isEmpty: Boolean get() = steps.all { it.isEmpty }

    override fun undo(): Boolean = steps.asReversed().fold(false) { taken, step -> step.undo() || taken }

    override fun redo(): Boolean = steps.fold(false) { taken, step -> step.redo() || taken }
}
