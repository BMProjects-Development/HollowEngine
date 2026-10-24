package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.abs

private const val TimelineKeyframeSize = 10f
internal const val LaneContentId = "timeline-lane-content"

@Composable
internal fun TimelineLanes(
    controller: TimelineController,
    rows: List<TimelineRow>,
    pxPerSec: Float,
    contentWidth: Float,
    contentHeight: Float,
    rowsHeight: Float,
    visibleTimes: ClosedFloatingPointRange<Float>,
    scroll: UiScrollHandle,
    refresh: () -> Unit,
) {
    var menu by remember { mutableStateOf<TimelineMenuState?>(null) }
    var marquee by remember { mutableStateOf<TimelineMarquee?>(null) }
    val pan = remember { TimelinePanGesture() }

    val canvas = object : LaneCanvas {
        override fun onPress(event: UiEvent, row: TimelineRow) {
            if (event.isRightClick() || event.isMiddleClick()) {
                marquee = null
                pan.begin(event, controller, scroll, 0f)
                event.consume()
                return
            }
            if (!event.isLeftClick()) return
            val x = event.contentX()
            val y = event.contentY(row)
            marquee = TimelineMarquee(x, y, x, y, event.isAdditiveSelection())
            if (!event.isAdditiveSelection()) controller.clearSelection()
            refresh()
        }

        override fun onDrag(event: UiEvent, row: TimelineRow) {
            if (event.isRightClick() || event.isMiddleClick()) {
                if (!pan.advance(event)) return
                scroll.scrollTo(
                    x = pan.scrollX(controller, event),
                    y = (pan.scrollY - event.dragTotalY).coerceAtLeast(0f),
                )
                event.consume()
                refresh()
                return
            }
            val band = marquee ?: return
            marquee = band.copy(toX = event.contentX(), toY = event.contentY(row))
            event.consume()
            refresh()
        }

        override fun onRelease(event: UiEvent, row: TimelineRow) {
            if (event.isRightClick()) {
                if (pan.release()) {
                    val time = controller.timelineTimeAt(pan.pressLocalX, pxPerSec)
                    menu = TimelineMenuState(pan.pressX, pan.pressY, row.curve, time, row.locked, row.lane)
                }
                refresh()
                return
            }
            val band = marquee ?: return
            marquee = null
            if (band.isDrag) controller.select(keysInMarquee(controller, rows, pxPerSec, band), additive = band.additive)
            refresh()
        }
    }

    Box(
        id = LaneContentId,
        modifier = Modifier.size(contentWidth.px, contentHeight.coerceAtLeast(1f).px).panOnDrag(controller, scroll, refresh),
    ) {
        rows.forEach { row ->
            key(row.id) {
                TimelineLane(
                    row,
                    controller,
                    pxPerSec,
                    contentWidth,
                    visibleTimes,
                    canvas,
                    refresh,
                    onSegmentMenu = { event, segment ->
                        // Where on the segment it was clicked, which is where "split here" splits it.
                        val time = controller.timelineTimeAt(event.contentX(), pxPerSec)
                        menu = TimelineMenuState(event.x, event.y, null, time, row.locked, row.lane, segment)
                    },
                ) { event, curve, time ->
                    menu = TimelineMenuState(event.x, event.y, curve, time, row.locked)
                }
            }
        }
        WorkAreaShade(controller, pxPerSec, contentWidth, rowsHeight)
        MarqueeOverlay(marquee)
        Playhead(controller, pxPerSec, rowsHeight)
    }

    menu?.let { state ->
        TimelineContextMenu(controller, state, refresh) { menu = null }
    }
}

private interface LaneCanvas {
    fun onPress(event: UiEvent, row: TimelineRow)
    fun onDrag(event: UiEvent, row: TimelineRow)
    fun onRelease(event: UiEvent, row: TimelineRow)
}

private fun UiEvent.contentX(): Float = localXInAncestor(LaneContentId) ?: localX

private fun UiEvent.contentY(row: TimelineRow): Float =
    localYInAncestor(LaneContentId) ?: (row.y - TimelineRulerHeight + localY)

private fun keysInMarquee(
    controller: TimelineController,
    rows: List<TimelineRow>,
    pxPerSec: Float,
    marquee: TimelineMarquee,
): List<Keyframe> = rows.filter { it.kind != TimelineRowKind.SEGMENTS && !it.locked }.flatMap { row ->
        val curve = row.curve ?: return@flatMap emptyList()
        val y = row.y - TimelineRulerHeight + row.height * 0.5f
        curve.keyframes.filter { key -> marquee.contains(controller.timeX(key.time, pxPerSec), y) }
    }

internal class TimelineMenuState(
    val x: Float,
    val y: Float,
    val curve: ChannelCurve?,
    val time: Float,
    val locked: Boolean,
    /** The lane of segments the menu was opened on, whose owner says what it offers. */
    val lane: SegmentLane? = null,
    val segment: TimelineSegment? = null,
)

/** The keymap's drags over the lanes: the pan drag moves the view, the zoom drag zooms the time. */
@Composable
internal fun Modifier.panOnDrag(controller: TimelineController, scroll: UiScrollHandle, refresh: () -> Unit): Modifier {
    val start = remember { FloatArray(2) }
    val panning = remember { booleanArrayOf(false) }
    val zoom = remember { TimelineZoomDrag() }
    return this.onPress { event ->
        if (zoom.begin(event, controller, scroll, event.localX)) {
            event.consume()
            return@onPress
        }
        panning[0] = HollowKeys.DRAG_PAN.matchesMouse(event.button, event.modifiers)
        if (!panning[0]) return@onPress
        start[0] = controller.timeAtX(scroll.offsetX)
        start[1] = scroll.offsetY
        event.consume()
    }.onDrag { event ->
        if (zoom.drag(event, controller, scroll, values = false)) {
            event.consume()
            refresh()
            return@onDrag
        }
        if (!panning[0]) return@onDrag
        scroll.scrollTo(
            x = (controller.timeX(start[0]) - event.dragTotalX).coerceAtLeast(0f),
            y = (start[1] - event.dragTotalY).coerceAtLeast(0f),
        )
        event.consume()
        refresh()
    }.onRelease {
        zoom.end()
        panning[0] = false
    }
}

@Composable
private fun TimelineLane(
    row: TimelineRow,
    controller: TimelineController,
    pxPerSec: Float,
    contentWidth: Float,
    visibleTimes: ClosedFloatingPointRange<Float>,
    canvas: LaneCanvas,
    refresh: () -> Unit,
    onSegmentMenu: (UiEvent, TimelineSegment) -> Unit,
    onContextMenu: (UiEvent, ChannelCurve?, Float) -> Unit,
) {
    val top = row.y - TimelineRulerHeight
    val background = when {
        row.kind == TimelineRowKind.GROUP -> TimelineColors.Group
        row.kind == TimelineRowKind.SEGMENTS -> TimelineColors.Row
        row.locked -> UiColor(0.08f, 0.08f, 0.09f, 1f)
        !row.visible -> UiColor(0.09f, 0.09f, 0.1f, 1f)
        row.kind == TimelineRowKind.CHANNEL -> TimelineColors.Row
        else -> TimelineColors.Group.withAlpha(0.65f)
    }
    Box(
        id = "lane-${row.id}",
        modifier = Modifier.position(0.px, top.px).size(contentWidth.px, row.height.px).background(background)
            .onPress { event -> canvas.onPress(event, row) }.onDrag { event -> canvas.onDrag(event, row) }
            .onRelease { event -> canvas.onRelease(event, row) },
    ) {
        Box(
            modifier = Modifier.position(0.px, (row.height - 1f).px).size(contentWidth.px, 1.px)
                .background(TimelineColors.Border).inputTransparent(),
        )
        val lane = row.lane
        if (lane != null) {
            SegmentLaneContent(row, lane, controller, pxPerSec, refresh, onSegmentMenu)
            return@Box
        }
        val curve = row.curve ?: return@Box

        val keys = visibleKeys(row, visibleTimes.start, visibleTimes.endInclusive)
        KeyConnections(controller, row, pxPerSec, keys)
        keys.forEachIndexed { index, keyframe ->
            val halfSize = TimelineKeyframeSize * 0.5f
            val leftHalfWidth = keys.getOrNull(index - 1)?.let { previous ->
                ((keyframe.time - previous.time) * pxPerSec * 0.5f).coerceIn(0f, halfSize)
            } ?: halfSize
            val rightHalfWidth = keys.getOrNull(index + 1)?.let { next ->
                ((next.time - keyframe.time) * pxPerSec * 0.5f).coerceIn(0f, halfSize)
            } ?: halfSize
            key(keyframe) {
                TimelineKeyframe(
                    keyframe, keys, curve, row, controller, pxPerSec,
                    leftHalfWidth, rightHalfWidth, refresh, onContextMenu,
                )
            }
        }
    }
}

@Composable
private fun TimelineKeyframe(
    keyframe: Keyframe,
    keys: List<Keyframe>,
    curve: ChannelCurve,
    row: TimelineRow,
    controller: TimelineController,
    pxPerSec: Float,
    leftHalfWidth: Float,
    rightHalfWidth: Float,
    refresh: () -> Unit,
    onContextMenu: (UiEvent, ChannelCurve?, Float) -> Unit,
) {
    val selected = controller.isSelected(keyframe)
    val color = keyframeColor(row.color?.toUiColor() ?: TimelineColors.Blue, selected, row.visible)
    val halfSize = TimelineKeyframeSize * 0.5f
    val x = controller.timeX(keyframe.time, pxPerSec) - leftHalfWidth
    val y = row.height * 0.5f - halfSize
    val press = remember { KeyPressGesture() }
    Box(
        id = "key-${System.identityHashCode(curve)}-${System.identityHashCode(keyframe)}",
        tags = buildList {
            add("timeline-keyframe")
            if (selected) add("selected")
            if (row.locked) add("locked")
        },
        modifier = Modifier.position(x.px, y.px).size((leftHalfWidth + rightHalfWidth).px, TimelineKeyframeSize.px)
            .cursor(if (row.locked) UiCursorShape.DEFAULT else UiCursorShape.MOVE).onPress { event ->
                if (row.locked) return@onPress
                if (event.isRightClick()) {
                    if (!selected) controller.select(listOf(keyframe), additive = false)
                    onContextMenu(event, curve, keyframe.time)
                    event.consume()
                    refresh()
                    return@onPress
                }
                if (!event.isLeftClick()) return@onPress
                event.consume()
                press.begin(keyframe, event.modifiers, controller) { stackedOnLane(keys, keyframe, pxPerSec) }
                refresh()
            }.onDrag { event ->
                if (row.locked) return@onDrag
                press.drag(event, controller, keyframe, curve = false)
                if (!controller.isDragDriver(keyframe)) return@onDrag
                val focus = controller.dragFocusKeyframe ?: return@onDrag
                val origin = controller.dragStartTimes?.get(focus) ?: focus.time
                val delta = timelineTimeDelta(event.dragTotalX, pxPerSec)
                controller.applyKeyframeDrag(snapTimelineTime(origin + delta) - origin)
                event.consume()
                refresh()
            }.onRelease {
                if (row.locked) return@onRelease
                press.release(controller, keyframe)
                refresh()
            },
    ) {
        Box(
            tags = listOf("timeline-keyframe-visual"),
            modifier = Modifier.position((leftHalfWidth - halfSize).px, 0.px)
                .size(TimelineKeyframeSize.px, TimelineKeyframeSize.px).shape(TimelineDiamondShape).shapeFill(color)
                .inputTransparent(),
        )
    }
}

private fun stackedOnLane(keys: List<Keyframe>, pressed: Keyframe, pxPerSec: Float): List<Keyframe> =
    keys.filter { abs(it.time - pressed.time) * pxPerSec <= TimelineKeyframeSize * 0.5f }

internal class KeyPressGesture {
    private var pendingClone = false
    private var dragging = false

    fun begin(
        keyframe: Keyframe,
        modifiers: Int,
        controller: TimelineController,
        stacked: () -> List<Keyframe>,
    ) {
        dragging = false
        pendingClone = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        if (pendingClone) return
        controller.selectStacked(keyframe, stacked(), additive = modifiers and GLFW.GLFW_MOD_SHIFT != 0)
    }

    fun drag(event: UiEvent, controller: TimelineController, keyframe: Keyframe, curve: Boolean) {
        if (dragging) return
        if (pendingClone) {
            if (abs(event.dragTotalX) <= MinDragUnit && abs(event.dragTotalY) <= MinDragUnit) return
            if (!controller.isSelected(keyframe)) controller.select(listOf(keyframe), additive = false)
            if (!controller.beginCloneDrag(keyframe, withValues = curve)) return
            pendingClone = false
            dragging = true
            return
        }
        if (curve) controller.beginCurveDrag(keyframe) else controller.beginKeyframeDrag(keyframe)
        dragging = true
    }

    fun release(controller: TimelineController, keyframe: Keyframe) {
        if (dragging) {
            controller.endKeyframeDrag()
        } else if (pendingClone) {
            controller.toggleSelection(listOf(keyframe))
        }
        pendingClone = false
        dragging = false
    }
}

internal fun keyframeColor(base: UiColor, selected: Boolean, visible: Boolean): UiColor {
    val lifted = if (selected) base.interpolate(UiColor.White, 0.45f) else base
    return lifted.withAlpha(if (visible) 1f else 0.4f)
}

@Composable
private fun KeyConnections(controller: TimelineController, row: TimelineRow, pxPerSec: Float, keys: List<Keyframe>) {
    val alpha = if (row.visible) 1f else 0.25f
    val color = row.color?.toUiColor() ?: TimelineColors.Blue
    for (index in 0 until keys.lastIndex) {
        val first = keys[index]
        val second = keys[index + 1]
        val x = controller.timeX(first.time, pxPerSec)
        val width = (second.time - first.time) * pxPerSec
        if (width <= 1f) continue
        val hold = first.interpolation == KeyInterpolation.CONSTANT
        val spline = first.interpolation == KeyInterpolation.BEZIER
        val thickness = if (spline) 3f else 2f
        Box(
            modifier = Modifier.position(x.px, (row.height * 0.5f - thickness * 0.5f).px).size(width.px, thickness.px)
                .background(color.withAlpha(alpha * (if (hold) 0.8f else 0.45f))).borderRadius(thickness * 0.5f)
                .inputTransparent(),
        )
    }
}

/** Darkens the time the playback never reaches: before zero and past the end of the work area. */
@Composable
internal fun WorkAreaShade(controller: TimelineController, pxPerSec: Float, contentWidth: Float, height: Float) {
    val shadeHeight = height.coerceAtLeast(1f).px
    Box(
        modifier = Modifier.position(0.px, 0.px).size(controller.timeX(0f, pxPerSec).px, shadeHeight)
            .background(OutsideWorkArea).inputTransparent(),
    )
    val x = controller.timeX(controller.workAreaEnd, pxPerSec)
    if (x >= contentWidth) return
    Box(
        modifier = Modifier.position(x.px, 0.px).size((contentWidth - x).px, shadeHeight)
            .background(OutsideWorkArea).inputTransparent(),
    )
}

private val OutsideWorkArea = UiColor(0f, 0f, 0f, 0.38f)

@Composable
internal fun TimelineContextMenu(
    controller: TimelineController,
    state: TimelineMenuState,
    refresh: () -> Unit,
    onClose: () -> Unit,
) {
    var presetCategory by remember { mutableStateOf<String?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val hasSelection = controller.selectedKeyframes.isNotEmpty()
    val hasCurveSelection = controller.canEditSelectedCurves

    val items = when {
        state.lane != null -> if (state.locked) emptyList() else state.lane.menu?.invoke(state.time, state.segment).orEmpty()

        showPresets && presetCategory != null -> CurvePresets.of(presetCategory!!).map { preset ->
            UiDropdownItem(preset.name) {
                controller.applyPreset(preset)
                refresh()
            }
        }

        showPresets -> CurvePresets.categories.map { category ->
            UiDropdownItem(category, closeOnClick = false) { presetCategory = category }
        }

        else -> buildList {
            val curve = state.curve
            if (curve != null && !state.locked) {
                add(UiDropdownItem(TimelineLang.ADD_KEYFRAME.lang, icon = PulseIcon) {
                    controller.edit(TimelineEdits.ADD_KEY) {
                        controller.setKey(curve, state.time, curve.valueAt(state.time, 0f))
                    }
                    refresh()
                })
            }
            add(
                UiDropdownItem(
                    TimelineLang.CURVE_PRESET.lang,
                    icon = PresetIcon,
                    enabled = hasCurveSelection,
                    closeOnClick = false,
                ) { showPresets = true })
            add(UiDropdownItem(TimelineLang.SMOOTH_SELECTED.lang, icon = GraphIcon, enabled = hasCurveSelection) {
                controller.smoothSelectedKeyframes()
                refresh()
            })
            add(UiDropdownItem(TimelineLang.COPY_SELECTED.lang, icon = CopyIcon, enabled = hasSelection) {
                controller.copySelectedKeyframes()
                refresh()
            })
            add(UiDropdownItem(TimelineLang.CUT_SELECTED.lang, icon = CutIcon, enabled = hasSelection) {
                controller.cutSelectedKeyframes()
                refresh()
            })
            add(UiDropdownItem(TimelineLang.PASTE.lang, icon = PasteIcon, enabled = controller.canPaste) {
                controller.pasteKeyframes(state.time)
                refresh()
            })
            add(UiDropdownItem(TimelineLang.DELETE_SELECTED.lang, icon = RemoveIcon, enabled = hasSelection) {
                controller.deleteSelectedKeyframes()
                refresh()
            })
        }
    }

    ContextMenu(
        id = "cutscene-timeline-menu",
        anchorBounds = UiRect(state.x, state.y, 0f, 0f),
        items = items,
        onExpandedChange = { open -> if (!open) onClose() },
    )
}

private const val PulseIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"
private const val GraphIcon = "hollowengine:textures/gui/icons/timeline/smooth.svg"
private const val PresetIcon = "hollowengine:textures/gui/icons/timeline/preset.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/timeline/delete.svg"
private const val CopyIcon = "hollowengine:textures/gui/icons/timeline/copy.svg"
private const val CutIcon = "hollowengine:textures/gui/icons/timeline/cut.svg"
private const val PasteIcon = "hollowengine:textures/gui/icons/timeline/paste.svg"
