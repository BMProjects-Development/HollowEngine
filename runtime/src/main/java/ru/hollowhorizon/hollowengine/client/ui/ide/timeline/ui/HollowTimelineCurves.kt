package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import kotlin.math.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits

private const val CurveSampleStep = 3f
private const val CurvePointSize = 9f
private const val CurveHandleSize = 7f

internal data class CurveLane(
    val property: AnimProperty<*>,
    val curve: ChannelCurve,
    val color: UiColor,
    val locked: Boolean,
)

internal fun curveLanes(rows: List<TimelineRow>, controller: TimelineController): List<CurveLane> {
    val lanes = rows.filter { it.kind == TimelineRowKind.PROPERTY && it.visible }
        .mapNotNull { row -> row.property?.let { row to it } }
        .flatMap { (row, property) ->
            property.curves.filter { it.isVisible && it.spec.supportsCurveEditor }.map { curve ->
                CurveLane(property, curve, curve.color.toUiColor(), row.locked)
            }
        }
    if (controller.focusedCurves.isEmpty()) return lanes
    return lanes.filter { controller.isFocused(it.curve) }
}

@Composable
internal fun TimelineCurveGraph(
    controller: TimelineController,
    lanes: List<CurveLane>,
    pxPerSec: Float,
    contentWidth: Float,
    height: Float,
    scrollX: Float,
    visibleTimes: ClosedFloatingPointRange<Float>,
    scroll: UiScrollHandle,
    refresh: () -> Unit,
) {
    var menu by remember { mutableStateOf<TimelineMenuState?>(null) }
    var marquee by remember { mutableStateOf<TimelineMarquee?>(null) }
    val pan = remember { TimelinePanGesture() }
    val revision = LocalTimelineRevision.current
    val center = controller.curveValueCenter
    val span = controller.curveValueSpan
    val pxPerValue = curvePixelsPerValue(span, height)
    val fromX = max(0f, TimelineLeftPadding + visibleTimes.start * pxPerSec)
    val toX = min(contentWidth, TimelineLeftPadding + visibleTimes.endInclusive * pxPerSec)

    val grid = remember(revision, center, span, height, fromX, toX) {
        buildCurveGrid(center, span, height, fromX, toX)
    }
    val curves = remember(revision, center, span, height, fromX, toX, pxPerSec, lanes) {
        lanes.map { lane -> lane.color to buildCurveShape(lane, pxPerSec, fromX, toX, center, span, height) }
    }
    val guides = remember(revision, center, span, height, pxPerSec, lanes, controller.selectedKeyframes.toList()) {
        buildHandleGuides(controller, lanes, pxPerSec, center, span, height, visibleTimes)
    }

    Box(
        id = "timeline-curve-content",
        modifier = Modifier.size(contentWidth.px, height.coerceAtLeast(1f).px)
            .drawBehind(key = listOf(revision, center, span, height, fromX, toX, pxPerSec)) {
                drawShape(grid, UiPaint.Color(TimelineColors.Grid), UiDrawStyle.Stroke(1f))
                guides.forEach { drawShape(it, UiPaint.Color(TimelineColors.Handle), UiDrawStyle.Stroke(1f)) }
                curves.forEach { (color, shape) ->
                    drawShape(shape, UiPaint.Color(color), UiDrawStyle.Stroke(1.6f))
                }
            }.panCurveOnDrag(controller, scroll, pxPerValue, pan, refresh).onPress { event ->
                if (!event.isLeftClick()) {
                    marquee = null
                    return@onPress
                }
                marquee =
                    TimelineMarquee(event.localX, event.localY, event.localX, event.localY, event.isAdditiveSelection())
                if (!event.isAdditiveSelection()) controller.clearSelection()
                refresh()
            }.onDrag { event ->
                if (!event.isLeftClick()) return@onDrag
                val band = marquee ?: return@onDrag
                marquee = band.copy(toX = event.localX, toY = event.localY)
                event.consume()
                refresh()
            }.onRelease { event ->
                if (event.isRightClick()) {
                    if (!pan.moved) {
                        val time = timelineTimeAt(pan.pressLocalX, pxPerSec, controller.workAreaEnd, event.modifiers)
                        menu = TimelineMenuState(pan.pressX, pan.pressY, null, time, locked = false)
                    }
                    pan.moved = false
                    refresh()
                    return@onRelease
                }
                val band = marquee ?: return@onRelease
                marquee = null
                if (band.isDrag) {
                    controller.select(
                        keysInCurveMarquee(lanes, pxPerSec, center, span, height, band),
                        additive = band.additive,
                    )
                }
                refresh()
            },
    ) {
        lanes.forEach { lane ->
            lane.curve.keyframes.forEach { keyframe ->
                if (keyframe.time !in visibleTimes) return@forEach
                key(lane.curve, keyframe) {
                    CurveKeyPoint(
                        controller, lanes, lane, keyframe, pxPerSec, pxPerValue, center, span, height,
                        onContextMenu = { event, curve, time ->
                            menu = TimelineMenuState(event.x, event.y, curve, time, lane.locked)
                        },
                        refresh = refresh,
                    )
                }
            }
        }
        WorkAreaShade(controller, pxPerSec, contentWidth, height)
        MarqueeOverlay(marquee)
        CurveValueLabels(center, span, height, scrollX, commonValueFormatter(lanes))
        Playhead(controller, pxPerSec, height)
    }

    menu?.let { state ->
        TimelineContextMenu(controller, state, refresh) { menu = null }
    }
}

@Composable
private fun CurveValueLabels(
    center: Float,
    span: Float,
    height: Float,
    scrollX: Float,
    formatter: ChannelValueFormatter?,
) {
    val step = curveValueStep(span)
    val top = center + span * 0.5f
    val bottom = center - span * 0.5f
    var value = floor(bottom / step) * step
    Box(
        modifier = Modifier.position(scrollX.px, 0.px).size(CurveValueGutter.px, height.coerceAtLeast(1f).px)
            .background(UiColor(0.07f, 0.08f, 0.1f, 0.72f)).inputTransparent(),
    ) {
        while (value <= top) {
            val y = curveValueToY(value, center, span, height)
            if (y in 0f..height) {
                Text(
                    formatter?.format(value) ?: formatCurveValue(value),
                    modifier = Modifier.position(2.px, (y - 6f).px).size((CurveValueGutter - 6f).px, 12.px).fontSize(9f)
                        .foreground(TimelineColors.Muted).textAlign(UiTextAlign.RIGHT),
                )
            }
            value += step
        }
    }
}

private fun commonValueFormatter(lanes: List<CurveLane>): ChannelValueFormatter? =
    lanes.map { lane -> lane.curve.spec.graphValueFormatter }.distinct().singleOrNull()

@Composable
private fun CurveKeyPoint(
    controller: TimelineController,
    lanes: List<CurveLane>,
    lane: CurveLane,
    keyframe: Keyframe,
    pxPerSec: Float,
    pxPerValue: Float,
    center: Float,
    span: Float,
    height: Float,
    onContextMenu: (UiEvent, ChannelCurve?, Float) -> Unit,
    refresh: () -> Unit,
) {
    val selected = controller.isSelected(keyframe)
    val x = TimelineLeftPadding + keyframe.time * pxPerSec
    val y = curveValueToY(keyframe.value, center, span, height)
    val half = CurvePointSize * 0.5f
    val press = remember { KeyPressGesture() }

    if (selected && !lane.locked) {
        TangentSide.entries.forEach { side ->
            if (lane.curve.hasNeighbour(keyframe, side)) {
                CurveHandle(controller, lane, keyframe, side, x, y, pxPerSec, pxPerValue, refresh)
            }
        }
    }

    Box(
        id = "curve-key-${System.identityHashCode(lane.curve)}-${System.identityHashCode(keyframe)}",
        tags = buildList {
            add("timeline-curve-key")
            if (selected) add("selected")
        },
        modifier = Modifier.position((x - half).px, (y - half).px).size(CurvePointSize.px, CurvePointSize.px)
            .cursor(if (lane.locked) UiCursorShape.DEFAULT else UiCursorShape.MOVE).shape(TimelineDiamondShape)
            .shapeFill(keyframeColor(lane.color, selected, visible = true)).onPress { event ->
                if (lane.locked) return@onPress
                if (event.isRightClick()) {
                    if (!selected) controller.select(listOf(keyframe), additive = false)
                    onContextMenu(event, lane.curve, keyframe.time)
                    event.consume()
                    refresh()
                    return@onPress
                }
                if (!event.isLeftClick()) return@onPress
                event.consume()
                press.begin(keyframe, event.modifiers, controller) {
                    stackedInGraph(lanes, keyframe, pxPerSec, center, span, height)
                }
                refresh()
            }.onDrag { event ->
                if (lane.locked) return@onDrag
                press.drag(event, controller, keyframe, curve = true)
                if (!controller.isDragDriver(keyframe)) return@onDrag
                val focus = controller.dragFocusKeyframe ?: return@onDrag
                val origin = controller.dragStartTimes?.get(focus) ?: focus.time
                val delta = timelineTimeDelta(event.dragTotalX, pxPerSec)
                val time = snapTimelineTime(origin + delta, event.modifiers)
                controller.applyCurveDrag(time - origin, -event.dragTotalY / pxPerValue)
                event.consume()
                refresh()
            }.onRelease {
                if (lane.locked) return@onRelease
                press.release(controller, keyframe)
                refresh()
            },
    )
}

@Composable
private fun CurveHandle(
    controller: TimelineController,
    lane: CurveLane,
    keyframe: Keyframe,
    side: TangentSide,
    keyX: Float,
    keyY: Float,
    pxPerSec: Float,
    pxPerValue: Float,
    refresh: () -> Unit,
) {
    val tangent = lane.curve.effectiveTangents(keyframe).tangent(side)
    val active = lane.curve.isTangentUsed(keyframe, side)
    val x = keyX + tangent.time * pxPerSec
    val y = keyY - tangent.value * pxPerValue
    val half = CurveHandleSize * 0.5f
    var grabbed by remember { mutableStateOf(KeyTangent.ZERO) }

    Box(
        id = "curve-handle-${System.identityHashCode(lane.curve)}-${side.name}-${System.identityHashCode(keyframe)}",
        tags = listOf("timeline-curve-handle"),
        modifier = Modifier.position((x - half).px, (y - half).px).size(CurveHandleSize.px, CurveHandleSize.px)
            .background(TimelineColors.Handle.withAlpha(if (active) 1f else 0.4f)).borderRadius(half)
            .cursor(UiCursorShape.MOVE).onPress { event ->
                if (!event.isLeftClick()) return@onPress
                grabbed = lane.curve.effectiveTangents(keyframe).tangent(side)
                controller.beginHistoryTransaction(TimelineEdits.EDIT_HANDLES)
                event.consume()
            }.onDrag { event ->
                val next = KeyTangent(
                    time = grabbed.time + event.dragTotalX / pxPerSec.coerceAtLeast(1f),
                    value = grabbed.value - event.dragTotalY / pxPerValue.coerceAtLeast(1e-6f),
                )
                controller.setTangent(
                    keyframe = keyframe,
                    side = side,
                    tangent = next,
                    mode = handleModeFor(event.modifiers),
                    timeScale = pxPerSec,
                    valueScale = pxPerValue,
                )
                event.consume()
                refresh()
            }.onRelease {
                controller.commitHistoryTransaction()
                refresh()
            },
    )
}

private fun handleModeFor(modifiers: Int): HandleMode = when {
    modifiers and GLFW.GLFW_MOD_CONTROL != 0 -> HandleMode.FREE
    modifiers and GLFW.GLFW_MOD_SHIFT != 0 -> HandleMode.ALIGNED
    else -> HandleMode.MIRRORED
}

private fun ChannelCurve.hasNeighbour(keyframe: Keyframe, side: TangentSide): Boolean {
    val index = keyframes.indexOfFirst { it === keyframe }
    if (index < 0) return false
    return if (side == TangentSide.OUTGOING) index < keyframes.lastIndex else index > 0
}

private fun buildCurveGrid(center: Float, span: Float, height: Float, fromX: Float, toX: Float): Shape {
    val step = curveValueStep(span)
    val start = floor((center - span * 0.5f) / step) * step
    val end = ceil((center + span * 0.5f) / step) * step
    return GenericShape {
        var value = start
        while (value <= end) {
            val y = curveValueToY(value, center, span, height)
            if (y in 0f..height) {
                moveTo(fromX, y)
                lineTo(toX, y)
            }
            value += step
        }
    }
}

private fun buildCurveShape(
    lane: CurveLane,
    pxPerSec: Float,
    fromX: Float,
    toX: Float,
    center: Float,
    span: Float,
    height: Float,
): Shape {
    val keys = lane.curve.keyframes
    val first = keys.minOfOrNull { it.time } ?: 0f
    val last = keys.maxOfOrNull { it.time } ?: 0f
    val startX = max(fromX, TimelineLeftPadding + first * pxPerSec)
    val endX = min(toX, TimelineLeftPadding + last * pxPerSec)
    return GenericShape {
        if (keys.isEmpty() || endX < startX) return@GenericShape
        var x = startX
        var started = false
        while (x <= endX) {
            val time = (x - TimelineLeftPadding) / pxPerSec
            val y = curveValueToY(lane.curve.valueAt(time, 0f), center, span, height)
            if (started) lineTo(x, y) else moveTo(x, y)
            started = true
            x += CurveSampleStep
        }
        if (started) {
            val time = (endX - TimelineLeftPadding) / pxPerSec
            lineTo(endX, curveValueToY(lane.curve.valueAt(time, 0f), center, span, height))
        }
    }
}

private fun stackedInGraph(
    lanes: List<CurveLane>,
    pressed: Keyframe,
    pxPerSec: Float,
    center: Float,
    span: Float,
    height: Float,
): List<Keyframe> {
    val x = TimelineLeftPadding + pressed.time * pxPerSec
    val y = curveValueToY(pressed.value, center, span, height)
    val radius = CurvePointSize * 0.5f
    return lanes.filterNot { it.locked }.flatMap { lane ->
        lane.curve.keyframes.filter { key ->
            abs(TimelineLeftPadding + key.time * pxPerSec - x) <= radius && abs(
                curveValueToY(
                    key.value, center, span, height
                ) - y
            ) <= radius
        }
    }
}

private fun buildHandleGuides(
    controller: TimelineController,
    lanes: List<CurveLane>,
    pxPerSec: Float,
    center: Float,
    span: Float,
    height: Float,
    visibleTimes: ClosedFloatingPointRange<Float>,
): List<Shape> {
    val pxPerValue = curvePixelsPerValue(span, height)
    return lanes.filterNot { it.locked }.map { lane ->
        val segments = mutableListOf<FloatArray>()
        lane.curve.keyframes.forEach { keyframe ->
            if (keyframe.time !in visibleTimes) return@forEach
            if (!controller.isSelected(keyframe)) return@forEach
            val keyX = TimelineLeftPadding + keyframe.time * pxPerSec
            val keyY = curveValueToY(keyframe.value, center, span, height)
            val tangents = lane.curve.effectiveTangents(keyframe)
            listOf(
                TangentSide.INCOMING to tangents.incoming, TangentSide.OUTGOING to tangents.outgoing
            ).forEach { (side, tangent) ->
                if (!lane.curve.hasNeighbour(keyframe, side)) return@forEach
                segments += floatArrayOf(
                    keyX, keyY,
                    keyX + tangent.time * pxPerSec,
                    keyY - tangent.value * pxPerValue,
                )
            }
        }
        GenericShape {
            segments.forEach { segment ->
                moveTo(segment[0], segment[1])
                lineTo(segment[2], segment[3])
            }
        }
    }
}

private fun formatCurveValue(value: Float): String {
    val rounded = when {
        abs(value) >= 100f -> "%.0f".format(value)
        abs(value) >= 1f -> "%.2f".format(value)
        else -> "%.3f".format(value)
    }
    return rounded.replace(',', '.')
}

@Composable
private fun Modifier.panCurveOnDrag(
    controller: TimelineController,
    scroll: UiScrollHandle,
    pixelsPerValue: Float,
    pan: TimelinePanGesture,
    refresh: () -> Unit,
): Modifier {
    return this.onPress { event ->
        if (!event.isMiddleClick() && !event.isRightClick()) return@onPress
        pan.begin(event, scroll.offsetX, scroll.offsetY, controller.curveAxis.targetCenter)
        event.consume()
    }.onDrag { event ->
        if (!event.isMiddleClick() && !event.isRightClick()) return@onDrag
        if (!pan.advance(event)) return@onDrag
        scroll.scrollTo(x = (pan.scrollX - event.dragTotalX).coerceAtLeast(0f))
        controller.curveAxis.snapTo(
            center = pan.center + event.dragTotalY / pixelsPerValue.coerceAtLeast(1e-6f),
            span = controller.curveAxis.targetSpan,
        )
        event.consume()
        refresh()
    }
}

private fun keysInCurveMarquee(
    lanes: List<CurveLane>,
    pxPerSec: Float,
    center: Float,
    span: Float,
    height: Float,
    marquee: TimelineMarquee,
): List<Keyframe> = lanes.filterNot { it.locked }.flatMap { lane ->
    lane.curve.keyframes.filter { key ->
        marquee.contains(
            TimelineLeftPadding + key.time * pxPerSec,
            curveValueToY(key.value, center, span, height),
        )
    }
}
