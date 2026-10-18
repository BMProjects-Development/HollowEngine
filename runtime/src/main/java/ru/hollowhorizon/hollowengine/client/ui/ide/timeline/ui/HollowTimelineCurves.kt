package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import kotlin.math.*

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
    val lanes = rows.filter { (it.kind == TimelineRowKind.PROPERTY || it.kind == TimelineRowKind.CHANNEL) && it.visible }
        .mapNotNull { row ->
            val property = row.property ?: return@mapNotNull null
            val curve = row.curve?.takeIf { it.isVisible && it.spec.supportsCurveEditor } ?: return@mapNotNull null
            CurveLane(property, curve, curve.color.toUiColor(), row.locked)
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
    transforms: CurveTransformHost,
    refresh: () -> Unit,
) {
    var menu by remember { mutableStateOf<TimelineMenuState?>(null) }
    var marquee by remember { mutableStateOf<TimelineMarquee?>(null) }
    val pan = remember { TimelinePanGesture() }
    val revision = LocalTimelineRevision.current to controller.keysRevision
    val center = controller.curveValueCenter
    val span = controller.curveValueSpan
    val pxPerValue = curvePixelsPerValue(span, height)
    transforms.view = CurveView(pxPerSec, controller.viewStart, center, span, height)
    transforms.viewport = scroll.viewport
    TrackPointer(transforms)
    CurvePointerWrap(transforms, scroll)
    val fromX = max(0f, controller.timeX(visibleTimes.start, pxPerSec))
    val toX = min(contentWidth, controller.timeX(visibleTimes.endInclusive, pxPerSec))

    val grid = remember(revision, center, span, height, fromX, toX) {
        buildCurveGrid(center, span, height, fromX, toX)
    }
    val curves = remember(revision, center, span, height, fromX, toX, pxPerSec, lanes) {
        lanes.map { lane -> lane.color to buildCurveShape(controller, lane, pxPerSec, fromX, toX, center, span, height) }
    }
    val guides = remember(
        revision, center, span, height, pxPerSec, lanes, controller.selectedKeyframes.toList(), controller.selectedHandles.toList(),
        CurveHandleDisplay.all,
    ) {
        buildHandleGuides(controller, lanes, pxPerSec, center, span, height, visibleTimes)
    }

    Box(
        id = "timeline-curve-content",
        modifier = Modifier.size(contentWidth.px, height.coerceAtLeast(1f).px)
            .onPlaced { transforms.content = it }
            .drawBehind(key = listOf(revision, center, span, height, fromX, toX, pxPerSec)) {
                drawShape(grid, UiPaint.Color(TimelineColors.Grid), UiDrawStyle.Stroke(1f))
                curves.forEach { (color, shape) ->
                    drawShape(shape, UiPaint.Color(color), UiDrawStyle.Stroke(1.6f))
                }
                guides.forEach { drawShape(it, UiPaint.Color(TimelineColors.Handle), UiDrawStyle.Stroke(1f)) }
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
                    if (pan.release()) {
                        val time = controller.timelineTimeAt(pan.pressLocalX, pxPerSec)
                        menu = TimelineMenuState(pan.pressX, pan.pressY, null, time, locked = false)
                    }
                    refresh()
                    return@onRelease
                }
                val band = marquee ?: return@onRelease
                marquee = null
                if (band.isDrag) {
                    controller.select(
                        keysInCurveMarquee(controller, lanes, pxPerSec, center, span, height, band),
                        additive = band.additive,
                    )
                    controller.selectHandles(handlesInCurveMarquee(controller, lanes, pxPerSec, center, span, height, band))
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
        transforms.active?.let { transform -> CurveTransformLayer(transform, transforms, contentWidth, height, refresh) }
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
    controller.keysRevision
    val selected = controller.isSelected(keyframe)
    val x = controller.timeX(keyframe.time, pxPerSec)
    val y = curveValueToY(keyframe.value, center, span, height)
    val half = CurvePointSize * 0.5f
    val press = remember { KeyPressGesture() }

    if ((CurveHandleDisplay.all || controller.showsHandles(keyframe)) && !lane.locked) {
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
                    stackedInGraph(controller, lanes, keyframe, pxPerSec, center, span, height)
                }
                refresh()
            }.onDrag { event ->
                if (lane.locked) return@onDrag
                press.drag(event, controller, keyframe, curve = true)
                if (!controller.isDragDriver(keyframe)) return@onDrag
                val focus = controller.dragFocusKeyframe ?: return@onDrag
                val origin = controller.dragStartTimes?.get(focus) ?: focus.time
                val delta = timelineTimeDelta(event.dragTotalX, pxPerSec)
                val time = snapTimelineTime(origin + delta)
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
    controller.keysRevision
    val tangent = lane.curve.effectiveTangents(keyframe).tangent(side)
    val active = lane.curve.isTangentUsed(keyframe, side)
    val x = keyX + tangent.time * pxPerSec
    val y = keyY - tangent.value * pxPerValue
    val half = CurveHandleSize * 0.5f
    val grabbed = remember { ArrayList<HandleGrab>() }
    val picked = controller.isSelected(keyframe, side)
    val color = if (picked) TimelineColors.Accent else TimelineColors.Handle

    Box(
        id = "curve-handle-${System.identityHashCode(lane.curve)}-${side.name}-${System.identityHashCode(keyframe)}",
        tags = if (picked) listOf("timeline-curve-handle", "selected") else listOf("timeline-curve-handle"),
        modifier = Modifier.position((x - half).px, (y - half).px).size(CurveHandleSize.px, CurveHandleSize.px)
            .background(color.withAlpha(if (active || picked) 1f else 0.4f)).borderRadius(half)
            .cursor(UiCursorShape.MOVE).onPress { event ->
                if (!event.isLeftClick()) return@onPress
                val inSelection = picked || controller.isSelected(keyframe)
                if (!inSelection || event.isAdditiveSelection()) {
                    controller.selectHandle(keyframe, side, event.isAdditiveSelection())
                }
                grabbed.clear()
                grabbed += handlesDragged(controller, keyframe, side)
                controller.beginHistoryTransaction(TimelineEdits.EDIT_HANDLES)
                event.consume()
            }.onDrag { event ->
                val time = event.dragTotalX / pxPerSec.coerceAtLeast(1f)
                val value = -event.dragTotalY / pxPerValue.coerceAtLeast(1e-6f)
                grabbed.forEach { grab ->
                    controller.setTangent(
                        keyframe = grab.keyframe,
                        side = grab.side,
                        tangent = KeyTangent(grab.start.time + time, grab.start.value + value),
                        mode = handleModeFor(event.modifiers),
                        timeScale = pxPerSec,
                        valueScale = pxPerValue,
                    )
                }
                event.consume()
                refresh()
            }.onRelease {
                controller.commitHistoryTransaction()
                refresh()
            },
    )
}

/** A handle a drag moves, and its tangent when the drag began. */
private class HandleGrab(val keyframe: Keyframe, val side: TangentSide, val start: KeyTangent)

/**
 * The handles a drag of the [side] handle of [keyframe] moves, all by the same amount: when it is among what is picked,
 * every handle picked alone and that side of every picked key; otherwise it alone.
 */
private fun handlesDragged(controller: TimelineController, keyframe: Keyframe, side: TangentSide): List<HandleGrab> {
    val inSelection = controller.isSelected(keyframe, side) || controller.isSelected(keyframe)
    val picks = if (!inSelection) listOf(HandlePick(keyframe, side))
    else controller.selectedHandles + controller.selectedKeyframes.map { HandlePick(it, side) }
    return picks.distinctBy { System.identityHashCode(it.keyframe) to it.side }.mapNotNull { pick ->
        val curve = controller.curveOf(pick.keyframe) ?: return@mapNotNull null
        val property = controller.propertyOf(curve) ?: return@mapNotNull null
        if (!curve.spec.supportsCurveEditor || controller.isLocked(property)) return@mapNotNull null
        if (!curve.hasNeighbour(pick.keyframe, pick.side)) return@mapNotNull null
        HandleGrab(pick.keyframe, pick.side, curve.effectiveTangents(pick.keyframe).tangent(pick.side))
    }
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
    controller: TimelineController,
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
    val startX = max(fromX, controller.timeX(first, pxPerSec))
    val endX = min(toX, controller.timeX(last, pxPerSec))
    return GenericShape {
        if (keys.isEmpty() || endX < startX) return@GenericShape
        var x = startX
        var started = false
        while (x <= endX) {
            val time = controller.timeAtX(x, pxPerSec)
            val y = curveValueToY(lane.curve.valueAt(time, 0f), center, span, height)
            if (started) lineTo(x, y) else moveTo(x, y)
            started = true
            x += CurveSampleStep
        }
        if (started) {
            val time = controller.timeAtX(endX, pxPerSec)
            lineTo(endX, curveValueToY(lane.curve.valueAt(time, 0f), center, span, height))
        }
    }
}

private fun stackedInGraph(
    controller: TimelineController,
    lanes: List<CurveLane>,
    pressed: Keyframe,
    pxPerSec: Float,
    center: Float,
    span: Float,
    height: Float,
): List<Keyframe> {
    val x = controller.timeX(pressed.time, pxPerSec)
    val y = curveValueToY(pressed.value, center, span, height)
    val radius = CurvePointSize * 0.5f
    return lanes.filterNot { it.locked }.flatMap { lane ->
        lane.curve.keyframes.filter { key ->
            abs(controller.timeX(key.time, pxPerSec) - x) <= radius && abs(
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
            if (!CurveHandleDisplay.all && !controller.showsHandles(keyframe)) return@forEach
            val keyX = controller.timeX(keyframe.time, pxPerSec)
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
    val zoom = remember { TimelineZoomDrag() }
    return this.onPress { event ->
        if (zoom.begin(event, controller, scroll, event.localX)) {
            event.consume()
            return@onPress
        }
        if (!isPanPress(event)) return@onPress
        pan.begin(event, controller, scroll, controller.curveAxis.targetCenter)
        event.consume()
    }.onDrag { event ->
        if (zoom.drag(event, controller, scroll, values = true)) {
            event.consume()
            refresh()
            return@onDrag
        }
        if (!isPanPress(event)) return@onDrag
        if (!pan.advance(event)) return@onDrag
        scroll.scrollTo(x = pan.scrollX(controller, event))
        controller.curveAxis.snapTo(
            center = pan.center + event.dragTotalY / pixelsPerValue.coerceAtLeast(1e-6f),
            span = controller.curveAxis.targetSpan,
        )
        event.consume()
        refresh()
    }.onRelease { zoom.end() }
}

/** The keymap's pan drag, or the right button, which pans the graph too and opens its menu on a click. */
private fun isPanPress(event: UiEvent): Boolean =
    event.isRightClick() || HollowKeys.DRAG_PAN.matchesMouse(event.button, event.modifiers)

private fun keysInCurveMarquee(
    controller: TimelineController,
    lanes: List<CurveLane>,
    pxPerSec: Float,
    center: Float,
    span: Float,
    height: Float,
    marquee: TimelineMarquee,
): List<Keyframe> = lanes.filterNot { it.locked }.flatMap { lane ->
    lane.curve.keyframes.filter { key ->
        marquee.contains(
            controller.timeX(key.time, pxPerSec),
            curveValueToY(key.value, center, span, height),
        )
    }
}

/** The handles shown inside [marquee] whose keys it leaves out: a key inside carries its handles anyway. */
private fun handlesInCurveMarquee(
    controller: TimelineController,
    lanes: List<CurveLane>,
    pxPerSec: Float,
    center: Float,
    span: Float,
    height: Float,
    marquee: TimelineMarquee,
): List<HandlePick> {
    val pxPerValue = curvePixelsPerValue(span, height)
    return lanes.filterNot { it.locked }.flatMap { lane ->
        lane.curve.keyframes.flatMap { key ->
            if (!CurveHandleDisplay.all && !controller.showsHandles(key)) return@flatMap emptyList()
            val keyX = controller.timeX(key.time, pxPerSec)
            val keyY = curveValueToY(key.value, center, span, height)
            if (marquee.contains(keyX, keyY)) return@flatMap emptyList()
            val tangents = lane.curve.effectiveTangents(key)
            TangentSide.entries.filter { side ->
                val tangent = tangents.tangent(side)
                lane.curve.hasNeighbour(key, side) &&
                    marquee.contains(keyX + tangent.time * pxPerSec, keyY - tangent.value * pxPerValue)
            }.map { HandlePick(key, it) }
        }
    }
}

/** Keeps where the pointer is for [host] without the graph composing again on every move. */
@Composable
private fun TrackPointer(host: CurveTransformHost) {
    val pointer = LocalPointer.current
    SideEffect {
        host.pointerX = pointer.x
        host.pointerY = pointer.y
    }
}

/** While a transform runs, the pointer stays over the graph, wrapping around its edges. */
@Composable
private fun CurvePointerWrap(host: CurveTransformHost, scroll: UiScrollHandle) {
    val wrap = LocalUiPointerWrap.current ?: return
    val active = host.active != null
    DisposableEffect(active) {
        if (active) wrap.hold(scroll.viewport, host.pointerX, host.pointerY, host.onWrap)
        onDispose { wrap.release(host.onWrap) }
    }
}

/**
 * The layer over the graph while [transform] runs: it takes the pointer, so the keys under it do not, ends the
 * transform on a click and draws the line from the middle to the pointer while turning or stretching.
 */
@Composable
private fun CurveTransformLayer(
    transform: CurveTransform,
    host: CurveTransformHost,
    width: Float,
    height: Float,
    refresh: () -> Unit,
) {
    Box(
        id = "timeline-curve-transform",
        modifier = Modifier.position(0.px, 0.px).size(width.px, height.coerceAtLeast(1f).px)
            .input(hoverable = true, clickable = true)
            .onHover { event ->
                host.move(event.localX, event.localY)
                refresh()
            }.onPress { event ->
                host.click(event.button)
                event.consume()
                refresh()
            }.drawBehind(key = listOf(transform.pointerX, transform.pointerY, transform.mode)) {
                if (transform.mode == GizmoEditMode.TRANSLATE) return@drawBehind
                val line = GenericShape {
                    moveTo(transform.pivotX, transform.pivotY)
                    lineTo(transform.pointerX, transform.pointerY)
                }
                drawShape(line, UiPaint.Color(TimelineColors.Muted), UiDrawStyle.Stroke(1f))
            },
    )
}
