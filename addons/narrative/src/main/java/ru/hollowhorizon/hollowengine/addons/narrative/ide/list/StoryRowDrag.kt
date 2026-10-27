package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryPlacement
import kotlin.math.roundToInt

/** How far right the pointer goes for a dragged row to go into the row above rather than beside it. */
private const val NestOffset = 24f

/** Where dragged rows land: [placement] of the statement on [line]. */
internal data class StoryDrop(val line: Int, val placement: StoryPlacement)

/**
 * Rows being dragged: [count] rows of the list from [from], which are the statement of [selection] with all that
 * goes with it. They follow the pointer and the rest make room at [slot]; the text changes once, on release.
 */
internal class StoryRowDrag(
    val selection: StorySelection,
    val whole: Boolean,
    val from: Int,
    val count: Int,
    private val pitch: Float,
) {
    var dy by mutableStateOf(0f)
        private set
    var slot by mutableStateOf(from)
        private set
    var target by mutableStateOf<StoryDrop?>(null)
        private set

    /** Whether the rows were taken somewhere they cannot go. */
    val rejected: Boolean
        get() = target == null && slot != from

    fun holds(index: Int) = index in from until from + count

    /** How far the row at [index] of the list is moved: with the pointer, or out of the way of the dragged rows. */
    fun offsetOf(index: Int): Float? = when {
        holds(index) -> dy
        index < from && index >= slot -> count * pitch
        index >= from + count && index - count < slot -> -count * pitch
        else -> null
    }

    /** Follows the pointer, [dx] and [dy] from where the drag started, and finds where the rows would land. */
    fun moveTo(model: StoryListModel, rows: List<StoryRow>, dx: Float, dy: Float) {
        this.dy = dy
        slot = (from + (dy / pitch).roundToInt()).coerceIn(0, rows.size - count)
        val nest = dx > NestOffset
        target = if (slot == from && !nest) null else landing(model, rows, nest)
    }

    /**
     * The first allowed place for the gap at [slot]: before the row under it, or, at the end, after the card of
     * the row above; with [nest], into or after the row above first.
     */
    private fun landing(model: StoryListModel, rows: List<StoryRow>, nest: Boolean): StoryDrop? {
        val others = rows.filterIndexed { index, _ -> !holds(index) }
        val above = others.getOrNull(slot - 1)?.takeIf { it.role != StoryRowRole.MENU }
        val below = others.getOrNull(slot)
        val into = above?.takeIf { it.block.line.opensBlock }?.let { StoryDrop(it.line, StoryPlacement.INTO) }
        val after = above?.let { StoryDrop(it.line, StoryPlacement.AFTER) }
        val before = below?.let { StoryDrop(it.line, StoryPlacement.BEFORE) }
        val end = above?.takeIf { below == null }?.let {
            val root = generateSequence(it.block) { block -> block.parent }.last()
            StoryDrop(model.outline.groupOf(root).last().line.index, StoryPlacement.AFTER)
        }
        val candidates = if (nest) listOf(into, after, before) else listOf(before, end, after, into)
        return candidates.filterNotNull().firstOrNull {
            StoryEdits.canRelocate(model.outline, selection.line, whole, it.line, it.placement)
        }
    }

    companion object {
        /** A drag of [row] with everything that moves with it: the whole card, or an option with its branch. */
        fun of(state: StoryListState, model: StoryListModel, rows: List<StoryRow>, row: StoryRow): StoryRowDrag? {
            val selection = row.selection
            val whole = state.whole(model, selection)
            val lines = if (whole) {
                val card = model.outline.groupOf(row.block)
                card.first().line.index..card.last().last
            } else {
                row.block.line.index..row.block.last
            }
            val moves = { it: StoryRow -> it.line in lines && (whole || it.role != StoryRowRole.MENU) }
            val from = rows.indexOfFirst(moves).takeIf { it >= 0 } ?: return null
            val count = rows.subList(from, rows.size).takeWhile(moves).size
            return StoryRowDrag(selection, whole, from, count, state.list.averageItemSize)
        }
    }
}
