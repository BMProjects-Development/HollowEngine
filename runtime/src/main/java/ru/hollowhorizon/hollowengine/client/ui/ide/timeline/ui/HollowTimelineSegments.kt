package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.SegmentLane
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineSegment
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineSnap
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** How wide the grips at the ends of a segment are, where a drag trims it instead of moving it. */
private const val EdgeGrip = 5f

/** The size of the dot on the top edge that drags where a fade ends. */
private const val FadeDot = 7f

/** The shortest a segment can be dragged to. */
private const val MinLength = 0.05f

/** How far, in pixels, a segment is dragged up or down before it leaves its row. */
private const val RowDragThreshold = 4f

/** How quickly a dropped segment settles into its row, per second. */
private const val SettleRate = 18f

private const val MarkerWidth = 9f

/** What a press on a segment does as it is dragged. */
private enum class SegmentGrip { MOVE, START, END, FADE_IN, FADE_OUT }

/** Where a segment was when the press began, which every drag event measures from. */
private class SegmentDrag {
    var grip = SegmentGrip.MOVE
    var start = 0f
    var length = 0f
    var fadeIn = 0f
    var fadeOut = 0f
    var row = 0
    var moved = false

    /** Whether the drag has left the row it started on, and the segment follows the pointer up and down. */
    var acrossRows by mutableStateOf(false)

    fun begin(segment: TimelineSegment, grip: SegmentGrip) {
        this.grip = grip
        start = segment.start
        length = segment.length
        fadeIn = segment.fadeIn
        fadeOut = segment.fadeOut
        row = segment.row
        moved = false
        acrossRows = false
    }
}

/** The segments of [lane], on the rows the lane gives them, inside the lane's own row. */
@Composable
internal fun SegmentLaneContent(
    row: TimelineRow,
    lane: SegmentLane,
    controller: TimelineController,
    pxPerSec: Float,
    refresh: () -> Unit,
    onMenu: (UiEvent, TimelineSegment) -> Unit,
) {
    val levels = lane.levels()
    lane.segments.forEach { segment ->
        key(segment.id) {
            val level = levels[segment] ?: 0
            if (lane.markers) Marker(row, lane, segment, level, controller, pxPerSec, refresh, onMenu)
            else Block(row, lane, segment, level, controller, pxPerSec, refresh, onMenu)
        }
    }
}

@Composable
private fun Block(
    row: TimelineRow,
    lane: SegmentLane,
    segment: TimelineSegment,
    level: Int,
    controller: TimelineController,
    pxPerSec: Float,
    refresh: () -> Unit,
    onMenu: (UiEvent, TimelineSegment) -> Unit,
) {
    val drag = remember { SegmentDrag() }
    val selected = controller.selectedSegment === segment
    val x = TimelineLeftPadding + segment.start * pxPerSec
    val width = (segment.length * pxPerSec).coerceAtLeast(2f)
    val height = TimelineSegmentRowHeight - 4f
    val color = segment.color.toUiColor(if (row.visible) 1f else 0.4f)
    val slot = level * TimelineSegmentRowHeight + 2f

    var shownY by remember { mutableStateOf(slot) }
    var dragY by remember { mutableStateOf(0f) }
    LaunchedEffect(slot, drag.acrossRows) {
        if (drag.acrossRows) return@LaunchedEffect
        var last = 0L
        while (abs(shownY - slot) > 0.25f) {
            withFrameNanos { now ->
                val step = if (last == 0L) 1f / 60f else (now - last) / 1e9f
                last = now
                shownY += (slot - shownY) * min(1f, step * SettleRate)
            }
        }
        shownY = slot
    }
    val y = if (drag.acrossRows) drag.row * TimelineSegmentRowHeight + 2f + dragY else shownY

    fun press(event: UiEvent, grip: SegmentGrip): Boolean {
        if (row.locked) return false
        controller.selectSegment(lane, segment)
        if (event.isRightClick()) {
            onMenu(event, segment)
            event.consume()
            refresh()
            return false
        }
        if (!event.isLeftClick()) return false
        drag.begin(segment, grip)
        event.consume()
        refresh()
        return true
    }

    fun move(event: UiEvent) {
        if (row.locked || !event.isLeftClick()) return
        var wanted: Int? = null
        if (lane.arranged && drag.grip == SegmentGrip.MOVE && (drag.acrossRows || abs(event.dragTotalY) > RowDragThreshold)) {
            drag.acrossRows = true
            lane.isRearranging = true
            dragY = event.dragTotalY
            val lowest = lane.segments.filter { it !== segment }.maxOfOrNull { it.row } ?: -1
            wanted = (drag.row + (event.dragTotalY / TimelineSegmentRowHeight).roundToInt()).coerceIn(0, lowest + 1)
        }
        drag(
            drag,
            segment,
            timelineTimeDelta(event.dragTotalX, pxPerSec),
            lane,
            controller,
            pxPerSec,
            wanted
        )
        event.consume()
        refresh()
    }

    fun release() {
        if (drag.acrossRows) {
            shownY = drag.row * TimelineSegmentRowHeight + 2f + dragY
            drag.acrossRows = false
            lane.isRearranging = false
        }
        if (row.locked || !drag.moved) return
        drag.moved = false
        lane.onEdit?.invoke(segment)
        refresh()
    }

    Box(
        id = "segment-${lane.id}-${segment.id}",
        mode = UiBoxMode.STACK,
        tags = buildList {
            add("timeline-segment")
            if (selected) add("selected")
        },
        modifier = Modifier.position(x.px, y.px).size(width.px, height.px)
            .background(color.withAlpha(if (selected) 0.85f else 0.55f))
            .border(1.px, if (selected) TimelineColors.Text else color, radius = 3f).clip()
            .input(hoverable = true, clickable = true, draggable = true).cursor(UiCursorShape.MOVE)
            .onPress { event -> press(event, SegmentGrip.MOVE) }.onDrag(::move).onRelease { release() },
    ) {
        if (segment.fadeIn > 0f) Fade(0f, segment.fadeIn * pxPerSec, height, rising = true)
        if (segment.fadeOut > 0f) Fade(
            width - segment.fadeOut * pxPerSec,
            segment.fadeOut * pxPerSec,
            height,
            rising = false
        )
        Text(
            segment.label,
            tags = listOf("timeline-segment-label"),
            modifier = Modifier.position(4.px, 2.px).inputTransparent(),
        )
        Grip("start", 0f, EdgeGrip, height, ::press, ::move, ::release, SegmentGrip.START, lane, segment)
        Grip("end", width - EdgeGrip, EdgeGrip, height, ::press, ::move, ::release, SegmentGrip.END, lane, segment)
        if (segment.fades) {
            FadeHandle(
                "fade-in",
                segment.fadeIn * pxPerSec,
                ::press,
                ::move,
                ::release,
                SegmentGrip.FADE_IN,
                lane,
                segment
            )
            FadeHandle(
                "fade-out",
                width - segment.fadeOut * pxPerSec,
                ::press,
                ::move,
                ::release,
                SegmentGrip.FADE_OUT,
                lane,
                segment
            )
        }
    }
}

/** A strip at an end of a block that trims it. */
@Composable
private fun Grip(
    name: String,
    x: Float,
    width: Float,
    height: Float,
    press: (UiEvent, SegmentGrip) -> Boolean,
    move: (UiEvent) -> Unit,
    release: () -> Unit,
    grip: SegmentGrip,
    lane: SegmentLane,
    segment: TimelineSegment,
) {
    Box(
        id = "segment-${lane.id}-${segment.id}-$name",
        tags = listOf("timeline-segment-grip"),
        modifier = Modifier.position(x.px, 0.px).size(width.px, height.px)
            .input(hoverable = true, clickable = true, draggable = true).cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { event -> press(event, grip) }.onDrag(move).onRelease { release() },
    )
}

/** The dot on the top edge of a block where a fade ends, dragged along to make the fade longer or shorter. */
@Composable
private fun FadeHandle(
    name: String,
    x: Float,
    press: (UiEvent, SegmentGrip) -> Boolean,
    move: (UiEvent) -> Unit,
    release: () -> Unit,
    grip: SegmentGrip,
    lane: SegmentLane,
    segment: TimelineSegment,
) {
    Box(
        id = "segment-${lane.id}-${segment.id}-$name",
        tags = listOf("timeline-fade-handle"),
        modifier = Modifier.position((x - FadeDot / 2f).px, 0.px).size(FadeDot.px, FadeDot.px)
            .input(hoverable = true, clickable = true, draggable = true).cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { event -> press(event, grip) }.onDrag(move).onRelease { release() },
    )
}

/** The ramp of a fade over the part of the block it covers, drawn as the darker side of a diagonal. */
@Composable
private fun Fade(x: Float, width: Float, height: Float, rising: Boolean) {
    Box(
        modifier = Modifier.position(x.px, 0.px).size(width.coerceAtLeast(1f).px, height.px).inputTransparent()
            .drawBehind {
                drawShape(if (rising) FadeInShape else FadeOutShape, UiPaint.Color(FadeShade))
            },
    )
}

@Composable
private fun Marker(
    row: TimelineRow,
    lane: SegmentLane,
    segment: TimelineSegment,
    level: Int,
    controller: TimelineController,
    pxPerSec: Float,
    refresh: () -> Unit,
    onMenu: (UiEvent, TimelineSegment) -> Unit,
) {
    val drag = remember { SegmentDrag() }
    val selected = controller.selectedSegment === segment
    val x = TimelineLeftPadding + segment.start * pxPerSec - MarkerWidth / 2f
    val color = segment.color.toUiColor(if (row.visible) 1f else 0.4f)

    Row(
        id = "marker-${lane.id}-${segment.id}",
        tags = buildList {
            add("timeline-marker")
            if (selected) add("selected")
        },
        modifier = Modifier.position(x.px, (level * TimelineSegmentRowHeight + 2f).px)
            .input(hoverable = true, clickable = true, draggable = true).cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { event ->
                if (row.locked) return@onPress
                controller.selectSegment(lane, segment)
                if (event.isRightClick()) {
                    onMenu(event, segment)
                    event.consume()
                    refresh()
                    return@onPress
                }
                if (!event.isLeftClick()) return@onPress
                drag.begin(segment, SegmentGrip.MOVE)
                event.consume()
                refresh()
            }.onDrag { event ->
                if (row.locked || !event.isLeftClick()) return@onDrag
                drag(
                    drag,
                    segment,
                    timelineTimeDelta(event.dragTotalX, pxPerSec),
                    lane,
                    controller,
                    pxPerSec
                )
                event.consume()
                refresh()
            }.onRelease {
                if (row.locked || !drag.moved) return@onRelease
                drag.moved = false
                lane.onEdit?.invoke(segment)
                refresh()
            },
    ) {
        Box(
            modifier = Modifier.size(MarkerWidth.px, MarkerWidth.px).shape(TimelineDiamondShape)
                .shapeFill(if (selected) TimelineColors.Text else color).inputTransparent(),
        )
        Text(segment.label, tags = listOf("timeline-segment-label"), modifier = Modifier.inputTransparent())
    }
}

/** How close, in pixels, an edge of a dragged segment comes to another edge or the playhead before it sticks there. */
private const val MagnetPixels = 6f

private fun drag(
    drag: SegmentDrag,
    segment: TimelineSegment,
    delta: Float,
    lane: SegmentLane,
    controller: TimelineController,
    pxPerSec: Float,
    row: Int? = null,
) {
    val magnets = if (TimelineSnap.isFree) emptyList()
    else lane.segments.filter { it !== segment }.flatMap { listOf(it.start, it.end) } + controller.currentTime + 0f
    val reach = MagnetPixels / pxPerSec
    fun stick(time: Float): Float? = magnets.minByOrNull { abs(it - time) }?.takeIf { abs(it - time) <= reach }

    val end = drag.start + drag.length
    when (drag.grip) {
        SegmentGrip.MOVE -> {
            var start = snapTimelineTime(drag.start + delta)
            val byStart = stick(start)
            val byEnd = stick(start + segment.length)
            start = when {
                byStart != null && (byEnd == null || abs(byStart - start) <= abs(byEnd - start - segment.length)) -> byStart
                byEnd != null -> byEnd - segment.length
                else -> start
            }.coerceAtLeast(0f)
            val wanted = row ?: segment.row
            when {
                !lane.exclusive -> segment.row = wanted
                lane.fits(segment, wanted, start) -> segment.row = wanted
                lane.fits(segment, segment.row, start) -> Unit
                else -> start = lane.gap(segment).let { (left, right) ->
                    if (right - left < segment.length) segment.start else start.coerceIn(left, right - segment.length)
                }
            }
            segment.start = start
        }

        SegmentGrip.START -> {
            val left = if (lane.exclusive) lane.gap(segment).first else 0f
            val wanted = snapTimelineTime(drag.start + delta).let { stick(it) ?: it }
            val start = wanted.coerceIn(left, end - MinLength)
            segment.start = start
            segment.length = end - start
            segment.fadeIn = drag.fadeIn.coerceAtMost(segment.length - segment.fadeOut)
        }

        SegmentGrip.END -> {
            val right = if (lane.exclusive) lane.gap(segment).second else Float.MAX_VALUE
            val wanted = snapTimelineTime(end + delta).let { stick(it) ?: it }
            segment.length = (wanted.coerceAtMost(right) - drag.start).coerceAtLeast(MinLength)
            segment.fadeOut = drag.fadeOut.coerceAtMost(segment.length - segment.fadeIn)
        }

        SegmentGrip.FADE_IN -> segment.fadeIn = (drag.fadeIn + delta).coerceIn(0f, segment.length - segment.fadeOut)
        SegmentGrip.FADE_OUT -> segment.fadeOut = (drag.fadeOut - delta).coerceIn(0f, segment.length - segment.fadeIn)
    }
    drag.moved = true
}

/** The free stretch around [segment] on its row: from the end of the neighbor before it to the start of the one after. */
private fun SegmentLane.gap(segment: TimelineSegment): Pair<Float, Float> {
    val others = neighbors(segment)
    val left = others.filter { it.end <= segment.start + GapEpsilon }.maxOfOrNull { it.end } ?: 0f
    val right = others.filter { it.start >= segment.end - GapEpsilon }.minOfOrNull { it.start } ?: Float.MAX_VALUE
    return left to right
}

private const val GapEpsilon = 1e-4f

private val FadeShade = UiColor(0f, 0f, 0f, 0.35f)

/** The shaded half of a fade in: what the block does not yet cover, above the line from its start up to full. */
private val FadeInShape: Shape = GenericShape { size ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(0f, size.height)
    close()
}

private val FadeOutShape: Shape = GenericShape { size ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    close()
}
