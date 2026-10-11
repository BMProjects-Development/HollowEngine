package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.utils.Color

/**
 * A stretch of time on a [SegmentLane], such as a clip or how long something hangs on a model, with the time
 * it takes to fade in and out at its ends. A lane of markers holds segments of no length: named moments.
 */
class TimelineSegment(
    val id: String,
    start: Float,
    length: Float,
    label: String,
    val color: Color,
    fadeIn: Float = 0f,
    fadeOut: Float = 0f,
    val fades: Boolean = false,
    row: Int = 0,
) {
    var start by mutableStateOf(start)
    var length by mutableStateOf(length)
    var label by mutableStateOf(label)
    var fadeIn by mutableStateOf(fadeIn)
    var fadeOut by mutableStateOf(fadeOut)

    /** Which row of its lane the segment sits on, in a lane whose rows the author arranges. */
    var row by mutableStateOf(row)

    val end: Float get() = start + length
}

/**
 * One row of [TimelineSegment]s. In a lane of [arranged] rows the author puts each segment on the row they
 * want, dragging it up or down; otherwise segments that overlap stack on rows of their own.
 */
class SegmentLane(
    val id: String,
    name: String,
    /** Whether the lane holds moments rather than stretches: its segments have no length and draw as flags. */
    val markers: Boolean = false,
    val arranged: Boolean = false,
    /** Whether segments on one row may not overlap: a drag stops at the neighbor, as clips on a track do. */
    val exclusive: Boolean = false,
) {
    var nameState by mutableStateOf(name)
    val segments = mutableStateListOf<TimelineSegment>()

    /** Whether the lane has a row in the track list; the owner decides, and the author can close it. */
    var isListed by mutableStateOf(true)

    /** Whether a segment is being dragged across rows, which gives the lane a free row to drop it on. */
    var isRearranging by mutableStateOf(false)

    /** Told once a gesture on [TimelineSegment] is over, with the segment as it was left. */
    var onEdit: ((TimelineSegment) -> Unit)? = null

    /** Told when the author picks a segment, or picks none. */
    var onSelect: ((TimelineSegment?) -> Unit)? = null

    /** What a right click on the lane offers at a time, on a segment or on none. */
    var menu: ((time: Float, segment: TimelineSegment?) -> List<UiDropdownItem>)? = null

    /** The segments that share a row with [segment], it aside: in an arranged lane its row, otherwise all of them. */
    fun neighbors(segment: TimelineSegment, row: Int = segment.row): List<TimelineSegment> =
        segments.filter { it !== segment && (!arranged || it.row == row) }

    /** Whether [segment] would fit on [row] from [start] for [length] seconds without covering a neighbor. */
    fun fits(segment: TimelineSegment, row: Int, start: Float, length: Float = segment.length): Boolean =
        neighbors(segment, row).none { it.start < start + length - OVERLAP_EPSILON && it.end > start + OVERLAP_EPSILON }

    /** The first row [segment] fits on from [start], counting from [from]; a new row below all others if none. */
    fun freeRow(segment: TimelineSegment, start: Float, from: Int = 0): Int {
        var row = from.coerceAtLeast(0)
        while (!fits(segment, row, start)) row++
        return row
    }

    /** Which row of the lane each segment goes on. */
    fun levels(): Map<TimelineSegment, Int> {
        if (arranged) return segments.associateWith { it.row.coerceAtLeast(0) }
        val ends = ArrayList<Float>()
        val levels = HashMap<TimelineSegment, Int>()
        segments.sortedBy { it.start }.forEach { segment ->
            val free = ends.indexOfFirst { it <= segment.start + OVERLAP_EPSILON }
            val level = if (free >= 0) free else ends.size.also { ends += 0f }
            ends[level] = if (markers) segment.start + MARKER_SPAN else segment.end
            levels[segment] = level
        }
        return levels
    }

    /** How many rows the lane needs to show its segments, with a free one below while one is dragged across. */
    val rowCount: Int
        get() {
            val used = if (segments.isEmpty()) 1 else (levels().values.maxOrNull() ?: 0) + 1
            return if (isRearranging) used + 1 else used
        }

    private companion object {
        const val OVERLAP_EPSILON = 1e-4f

        /** Markers closer than this go on rows of their own, so their flags do not cover each other. */
        const val MARKER_SPAN = 0.15f
    }
}
