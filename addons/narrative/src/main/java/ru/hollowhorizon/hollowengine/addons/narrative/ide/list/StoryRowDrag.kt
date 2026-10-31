package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPlace
import kotlin.math.roundToInt

/**
 * A card being dragged: [count] rows of the list from [from], the card with all that is nested in it. They follow the
 * pointer and the rest make room at [slot]; the file changes once, on release.
 */
internal class StoryRowDrag(val row: StoryRow, val from: Int, val count: Int, private val pitch: Float) {
    var dy by mutableStateOf(0f)
        private set
    var slot by mutableStateOf(from)
        private set
    var target by mutableStateOf<StoryPlace?>(null)
        private set

    /** Whether the card was taken somewhere it cannot go. */
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

    /** Follows the pointer, [dy] from where the drag started, and finds where the card would land. */
    fun moveTo(rows: List<StoryRow>, dy: Float, allowed: (StoryPlace) -> Boolean) {
        this.dy = dy
        slot = (from + (dy / pitch).roundToInt()).coerceIn(0, rows.size - count)
        target = if (slot == from) null else landing(rows, allowed)
    }

    /** The first allowed place for the gap at [slot]: before the row under it, or first on the branch above it. */
    private fun landing(rows: List<StoryRow>, allowed: (StoryPlace) -> Boolean): StoryPlace? {
        val others = rows.filterIndexed { index, _ -> !holds(index) }
        val above = others.getOrNull(slot - 1)
        val below = others.getOrNull(slot)
        val candidates = listOfNotNull(
            above?.takeIf { it.role == StoryRowRole.PIN }?.let { StoryPlace.Into(it.node.id, it.pin.orEmpty()) },
            below?.takeIf { it.moves }?.let { StoryPlace.Before(it.node.id) },
            above?.takeIf { it.moves || it.role == StoryRowRole.START }?.let { StoryPlace.After(it.node.id) },
        )
        return candidates.firstOrNull(allowed)
    }

    companion object {
        /** A drag of [row] with the rows nested in it. */
        fun of(rows: List<StoryRow>, row: StoryRow, pitch: Float): StoryRowDrag? {
            val from = rows.indexOfFirst { it.key == row.key }.takeIf { it >= 0 && row.moves } ?: return null
            val depth = row.guides.size
            val count = 1 + rows.subList(from + 1, rows.size).takeWhile { it.guides.size > depth }.size
            return StoryRowDrag(row, from, count, pitch)
        }
    }
}
