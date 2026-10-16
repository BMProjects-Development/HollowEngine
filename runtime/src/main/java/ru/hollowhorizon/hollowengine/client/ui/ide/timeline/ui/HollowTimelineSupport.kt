package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.AnimProperty
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelCurve
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.Keyframe
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.SegmentLane
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TrackGroup
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.common.utils.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

internal const val TimelineRulerHeight = 18f

internal const val TimelineGroupRowHeight = 17f
internal const val TimelinePropertyRowHeight = 16f
internal const val TimelineChannelRowHeight = 14f

/** The height of one row of segments within a lane; overlapping segments add rows. */
internal const val TimelineSegmentRowHeight = 18f
internal const val TimelineLeftPadding = 40f
internal const val CurveValueGutter = 40f
internal const val TimelineMinContentWidth = 600f
internal const val TimelineMaxZoom = 500f
internal const val TimelineMinZoom = 10f
internal const val TimelineMinHeaderWidth = 120f
internal const val TimelineMaxHeaderWidth = 480f
private const val TimelineAutoPanEdge = 48f
internal const val TimelineScrollbarClearance = 12f
internal const val TimelineCullMargin = 120f

internal val TimelineDiamondShape: Shape = GenericShape { size ->
    moveTo(size.width * 0.5f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.5f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}

internal val PlayheadHeadShape: Shape = GenericShape { size ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height * 0.55f)
    lineTo(size.width * 0.5f, size.height)
    lineTo(0f, size.height * 0.55f)
    close()
}

internal enum class TimelineRowKind {
    GROUP,
    PROPERTY,
    CHANNEL,
    SEGMENTS,
}

internal data class TimelineRow(
    val id: String,
    val label: String,
    val depth: Int,
    val y: Float,
    val height: Float,
    val kind: TimelineRowKind,
    val group: TrackGroup? = null,
    val property: AnimProperty<*>? = null,
    val curve: ChannelCurve? = null,
    val lane: SegmentLane? = null,
    val locked: Boolean = false,
    val visible: Boolean = true,
    val color: Color? = null,
) {
    val curves: List<ChannelCurve>
        get() = when {
            lane != null -> emptyList()
            curve != null -> listOf(curve)
            property != null -> property.curves
            group != null -> group.allProperties().flatMap { it.curves }
            else -> emptyList()
        }
}

internal fun timelineRows(controller: TimelineController, curveEditorOnly: Boolean = false): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var y = TimelineRulerHeight

    fun appendLane(lane: SegmentLane, depth: Int, locked: Boolean, visible: Boolean) {
        if (curveEditorOnly || !lane.isListed) return
        val height = lane.rowCount * TimelineSegmentRowHeight
        rows += TimelineRow(
            id = "timeline-lane-${System.identityHashCode(lane)}",
            label = lane.nameState,
            depth = depth,
            y = y,
            height = height,
            kind = TimelineRowKind.SEGMENTS,
            lane = lane,
            locked = locked,
            visible = visible,
        )
        y += height
    }

    controller.lanes.forEach { appendLane(it, 0, locked = false, visible = true) }

    fun appendGroup(group: TrackGroup, depth: Int, parentLocked: Boolean, parentVisible: Boolean) {
        if (!group.hasListedContent) return
        if (curveEditorOnly && group.allProperties().none { property ->
                property.channels.any { it.supportsCurveEditor }
            }) return
        val locked = parentLocked || group.isLocked
        val visible = parentVisible && group.isVisible
        rows += TimelineRow(
            id = "timeline-group-${System.identityHashCode(group)}",
            label = group.nameState,
            depth = depth,
            y = y,
            height = TimelineGroupRowHeight,
            kind = TimelineRowKind.GROUP,
            group = group,
            locked = locked,
            visible = visible,
        )
        y += TimelineGroupRowHeight
        if (group.isCollapsed) return

        group.lanes.forEach { appendLane(it, depth + 1, locked, visible) }
        group.children.forEach { appendGroup(it, depth + 1, locked, visible) }
        group.properties.forEach { property ->
            if (!property.isListed) return@forEach
            if (curveEditorOnly && property.channels.none { it.supportsCurveEditor }) return@forEach
            val propertyVisible = visible && property.isVisible
            val propertyLocked = locked || property.isLocked

            val singleCurve = property.curves.singleOrNull()
            rows += TimelineRow(
                id = "timeline-property-${System.identityHashCode(property)}",
                label = property.nameState,
                depth = depth + 1,
                y = y,
                height = TimelinePropertyRowHeight,
                kind = TimelineRowKind.PROPERTY,
                property = property,
                curve = singleCurve,
                locked = propertyLocked,
                visible = propertyVisible && (singleCurve?.isVisible ?: true),
                color = singleCurve?.color,
            )
            y += TimelinePropertyRowHeight
            if (!property.isExpanded) return@forEach

            if (property.curves.size <= 1) return@forEach

            property.curves.filter { !curveEditorOnly || it.spec.supportsCurveEditor }.forEach { curve ->
                rows += TimelineRow(
                    id = "timeline-channel-${System.identityHashCode(curve)}",
                    label = curve.name,
                    depth = depth + 2,
                    y = y,
                    height = TimelineChannelRowHeight,
                    kind = TimelineRowKind.CHANNEL,
                    property = property,
                    curve = curve,
                    locked = propertyLocked,
                    visible = propertyVisible && curve.isVisible,
                    color = curve.color,
                )
                y += TimelineChannelRowHeight
            }
        }
    }

    controller.groups.forEach { appendGroup(it, 0, parentLocked = false, parentVisible = true) }
    return rows
}

internal fun visibleKeys(row: TimelineRow, from: Float, to: Float): List<Keyframe> {
    val curve = row.curve ?: return emptyList()
    return curve.keyframes.filter { it.time in from..to }
}

internal fun timelineContentWidth(
    workAreaEnd: Float,
    maxKeyTime: Float,
    pixelsPerSecond: Float,
    viewportWidth: Float,
): Float {
    val endWidth = (workAreaEnd + 5f) * pixelsPerSecond + TimelineLeftPadding
    val keyWidth = (maxKeyTime + 5f) * pixelsPerSecond + TimelineLeftPadding
    return max(TimelineMinContentWidth, max(max(endWidth, keyWidth), viewportWidth))
}

internal fun timelineTimeAt(localX: Float, pixelsPerSecond: Float, workAreaEnd: Float, modifiers: Int = 0): Float =
    snapTimelineTime((localX - TimelineLeftPadding) / pixelsPerSecond, modifiers).coerceIn(0f, workAreaEnd)

internal fun snapTimelineTime(time: Float, modifiers: Int): Float {
    val step = when {
        modifiers and GLFW.GLFW_MOD_ALT != 0 -> 1f
        modifiers and GLFW.GLFW_MOD_SHIFT != 0 -> 0.5f
        else -> return time
    }
    return round(time / step) * step
}

internal fun timelineTimeDelta(deltaX: Float, pixelsPerSecond: Float): Float {
    return deltaX / pixelsPerSecond.coerceAtLeast(1f)
}

internal fun visibleTimeRange(scrollX: Float, viewportWidth: Float, pixelsPerSecond: Float): ClosedFloatingPointRange<Float> {
    if (viewportWidth <= 0f) return -Float.MAX_VALUE..Float.MAX_VALUE
    val start = (scrollX - TimelineCullMargin - TimelineLeftPadding) / pixelsPerSecond
    val end = (scrollX + viewportWidth + TimelineCullMargin - TimelineLeftPadding) / pixelsPerSecond
    return start..max(start, end)
}

internal fun curveValueToY(value: Float, center: Float, span: Float, height: Float): Float {
    if (span <= 0f || height <= 0f) return height * 0.5f
    return height * 0.5f - (value - center) * (height / span)
}

internal fun curveYToValue(y: Float, center: Float, span: Float, height: Float): Float {
    if (span <= 0f || height <= 0f) return center
    return center + (height * 0.5f - y) * (span / height)
}

internal fun curvePixelsPerValue(span: Float, height: Float): Float {
    if (span <= 0f) return 1f
    return height / span
}

internal fun curveValueStep(span: Float): Float {
    if (span <= 0f) return 1f
    val rough = span / 6f
    val magnitude = 10f.pow(floor(log10(rough)))
    val normalized = rough / magnitude
    val step = when {
        normalized < 1.5f -> 1f
        normalized < 3.5f -> 2f
        normalized < 7.5f -> 5f
        else -> 10f
    }
    return step * magnitude
}

internal fun autoPanToContentX(scroll: UiScrollHandle, viewport: UiRect, contentX: Float) {
    if (viewport.width <= 0f) return
    val edge = min(TimelineAutoPanEdge, viewport.width * 0.25f)
    val visibleStart = scroll.offsetX
    val visibleEnd = visibleStart + viewport.width
    val target = when {
        contentX < visibleStart + edge -> contentX - edge
        contentX > visibleEnd - edge -> contentX - viewport.width + edge
        else -> return
    }
    if (abs(target - scroll.offsetX) > 0.5f) scroll.scrollTo(x = target.coerceAtLeast(0f))
}

internal fun timelineRulerSeconds(scrollX: Float, viewWidth: Float, pixelsPerSecond: Float): IntRange {
    val start = floor((scrollX - TimelineLeftPadding).coerceAtLeast(0f) / pixelsPerSecond).toInt()
    val end = ceil((scrollX + viewWidth) / pixelsPerSecond).toInt()
    return start..end.coerceAtLeast(start)
}

internal fun timelineMaxKeyTime(controller: TimelineController): Float =
    controller.allCurves().flatMap { it.keyframes }.maxOfOrNull { it.time } ?: 0f

internal fun Color.toUiColor(alphaMultiplier: Float = 1f): UiColor {
    return UiColor(r, g, b, (a * alphaMultiplier).coerceIn(0f, 1f))
}

internal fun UiColor.withAlpha(multiplier: Float) =
    UiColor(red, green, blue, (alpha * multiplier).coerceIn(0f, 1f))

/**
 * What the lanes, keys and curves are drawn with: the IDE palette of docking.hss, so the timeline
 * reads as part of the IDE. The chrome around them takes the same colors from timeline.hss.
 */
internal object TimelineColors {
    val Background = UiColor(0.110f, 0.118f, 0.133f, 1f)
    val Panel = UiColor(0.110f, 0.118f, 0.133f, 1f)
    val Row = UiColor(0.118f, 0.125f, 0.141f, 1f)
    val Group = UiColor(0.129f, 0.137f, 0.157f, 1f)
    val Muted = UiColor(0.561f, 0.608f, 0.678f, 1f)
    val Text = UiColor(0.769f, 0.796f, 0.855f, 1f)
    val Accent = UiColor(0.431f, 0.608f, 0.863f, 1f)
    val Blue = UiColor(0.682f, 0.722f, 0.792f, 1f)
    val Border = UiColor(0.149f, 0.157f, 0.180f, 1f)
    val Recording = UiColor(0.878f, 0.478f, 0.478f, 1f)
    val Grid = UiColor(0.176f, 0.188f, 0.216f, 1f)
    val Handle = UiColor(0.72f, 0.74f, 0.8f, 1f)
}

/** The playhead turns red while auto-keying is on, so it is clear that edits land on it as keys. */
internal val TimelineController.playheadColor: UiColor
    get() = if (isRecording) TimelineColors.Recording else TimelineColors.Accent

/** How close, in pixels, the playhead comes to a key or the edge of a segment before it sticks there. */
private const val PlayheadMagnetPixels = 6f

/**
 * [time] moved onto the nearest key of a listed track, or edge of a segment, within a few pixels, so a key can be
 * changed in place by recording over it; Ctrl lets the playhead go anywhere.
 */
internal fun TimelineController.snapPlayhead(time: Float, pxPerSec: Float, modifiers: Int): Float {
    if (modifiers and GLFW.GLFW_MOD_CONTROL != 0) return time
    val reach = PlayheadMagnetPixels / pxPerSec
    val keys = allProperties().filter { it.isListed }.flatMap { property -> property.curves.flatMap { curve -> curve.keyframes.map { it.time } } }
    val lanes = lanes.filter { it.isListed } + groups.flatMap(::listedLanes)
    val edges = lanes.flatMap { lane -> lane.segments.flatMap { listOf(it.start, it.end) } }
    return (keys + edges).minByOrNull { abs(it - time) }?.takeIf { abs(it - time) <= reach } ?: time
}

private fun listedLanes(group: TrackGroup): List<SegmentLane> =
    group.lanes.filter { it.isListed } + group.children.flatMap(::listedLanes)
