package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.CutsceneIcon
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelinePlayheadHold
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineViewMode
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CutsceneStorage
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.scroll.rememberScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.round

private const val TimelineScrollStep = 44f
private const val TimelineValueZoomStep = 0.12f
private const val TimelineValuePanStep = 0.08f
internal const val TimelineMinValueSpan = 0.001f
internal const val TimelineMaxValueSpan = 100_000f

internal val LocalTimelineRevision = staticCompositionLocalOf { 0 }

@Composable
fun CutsceneTimelineDock(session: CutsceneEditorSession, keyboardActive: Boolean = true) {
    session.uiRevision.value
    var dialog by remember { mutableStateOf(CutsceneDialog.NONE) }

    LaunchedEffect(session) {
        var lastNanos = -1L
        while (isActive) {
            withFrameNanos { now ->
                val delta =
                    if (lastNanos < 0L) 0f else ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                lastNanos = now
                if (session.timeline.isPlaying) session.update(delta)
                if (session.timeline.curveAxis.advance(now)) session.invalidateUi()
            }
        }
    }

    PublishInspector(source = "cutscene", key = cutsceneInspectorKey(session)) {
        cutsceneInspectorTarget(session)
    }

    CompositionLocalProvider(LocalTimelineRevision provides session.uiRevision.value) {
        Box(id = "cutscene-timeline-dock", mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
            HollowTimelineEditor(
                controller = session.timeline,
                refresh = session::invalidateUi,
                onKeyInput = session::onHollowUiKey,
                keyboardActive = keyboardActive,
                onCapture = { session.captureFrame(session.timeline.currentTime) },
                onSave = { dialog = CutsceneDialog.SAVE },
                onLoad = { dialog = CutsceneDialog.LOAD },
            )

            when (dialog) {
                CutsceneDialog.SAVE -> SaveCutsceneDialog(session) { dialog = CutsceneDialog.NONE }
                CutsceneDialog.LOAD -> LoadCutsceneDialog(session) { dialog = CutsceneDialog.NONE }
                CutsceneDialog.NONE -> {}
            }
        }
    }
}

private fun cutsceneInspectorTarget(session: CutsceneEditorSession) = InspectorTarget(
    id = "cutscene-${cutsceneInspectorKey(session)}",
    title = CutsceneLang.PROPERTIES.lang,
    icon = CutsceneIcon,
) {
    CompositionLocalProvider(LocalTimelineRevision provides session.uiRevision.value) {
        HollowTimelineProperties(session, refresh = session::invalidateUi)
    }
}

private fun cutsceneInspectorKey(session: CutsceneEditorSession): String {
    val controller = session.timeline
    val keyframe = controller.selectedKeyframes.firstOrNull()
        ?: return if (controller.isWorkAreaSelected) "work-area" else "none"
    return "key-${controller.curveOf(keyframe)?.spec?.name}-${keyframe.time}"
}

private enum class CutsceneDialog { NONE, SAVE, LOAD }

@Composable
fun HollowTimelineEditor(
    controller: TimelineController,
    refresh: () -> Unit,
    onKeyInput: (Int, Int) -> Boolean = { _, _ -> false },
    keyboardActive: Boolean = true,
    onCapture: () -> Unit = {},
    onSave: () -> Unit = {},
    onLoad: () -> Unit = {},
    features: TimelineFeatures = TimelineFeatures.CUTSCENE,
) {
    val bump = refresh
    val scroll = rememberScrollState()
    val zoomGlide = remember(controller) { TimelineZoomGlide() }
    val playheadHold = remember(controller) { TimelinePlayheadHold() }
    val headerScroll = rememberScrollState()
    val isCurveView = controller.viewMode == TimelineViewMode.CURVES
    val rows = timelineRows(controller, curveEditorOnly = isCurveView)
    val rowsHeight = rows.sumOf { it.height.toDouble() }.toFloat()
    val pxPerSec = controller.pixelsPerSecond
    val contentWidth = timelineContentWidth(
        controller.workAreaEnd,
        timelineMaxKeyTime(controller),
        pxPerSec,
        TimelineMinContentWidth,
    )

    var laneViewport by remember { mutableStateOf(UiRect.Zero) }
    var isScrubbing by remember { mutableStateOf(false) }
    val scrollContentHeight = rowsHeight + if (laneViewport.height > 0f && rowsHeight > laneViewport.height) {
        TimelineScrollbarClearance
    } else {
        0f
    }
    val visibleTimes = visibleTimeRange(scroll.offsetX, laneViewport.width, pxPerSec)

    // Keep the moving focus (playhead while playing, or a dragged keyframe) in view by panning.
    val focusContentX = when {
        controller.dragFocusKeyframe != null ->
            TimelineLeftPadding + controller.dragFocusKeyframe!!.time * pxPerSec

        controller.isPlaying || isScrubbing -> TimelineLeftPadding + controller.currentTime * pxPerSec
        else -> null
    }
    if (focusContentX != null) autoPanToContentX(scroll, laneViewport, focusContentX)

    LaunchedEffect(controller) {
        while (isActive) {
            withFrameNanos { now ->
                zoomGlide.advance(now, controller, scroll)
                playheadHold.advance(controller, now)
            }
        }
    }

    LaunchedEffect(controller.frameTimeRequests) {
        if (controller.frameTimeRequests == 0 || laneViewport.width <= 0f) return@LaunchedEffect
        zoomGlide.stop()
        val usable = laneViewport.width - TimelineLeftPadding - TimelineFrameMargin
        controller.pixelsPerSecond = (usable / controller.workAreaEnd.coerceAtLeast(0.1f))
            .coerceIn(TimelineMinZoom, TimelineMaxZoom)
        scroll.scrollTo(x = 0f)
        bump()
    }

    Column(
        id = "cutscene-timeline-root",
        tags = listOf("timeline-root"),
        modifier = Modifier.size(100.percent, 100.percent)
            .style(TimelineStylesheet)
            .focusScope()
            .onKeyInput { input ->
                if (keyboardActive && !input.repeat && onKeyInput(input.key, input.modifiers)) {
                    input.consume()
                    playheadHold.press(controller, input.key, input.modifiers)
                }
            },
    ) {
        TimelineToolbar(controller, onCapture, onSave, onLoad, bump, features)

        Row(modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            TimelineHeaders(
                controller = controller,
                rows = rows,
                scroll = headerScroll,
                verticalOffset = if (isCurveView) headerScroll.offsetY else scroll.offsetY,
                ownsVerticalScroll = isCurveView,
                contentHeight = scrollContentHeight,
                trackScroll = if (isCurveView) headerScroll else scroll,
                refresh = bump,
            )
            HeaderSplitter(controller, bump)

            Column(modifier = Modifier.size(0.px, 100.percent).grow(1f)) {
                // Ruler: horizontally synced to the lane scroll, pinned vertically.
                Box(
                    id = "timeline-ruler-viewport",
                    tags = listOf("timeline-ruler"),
                    modifier = Modifier.size(100.percent, TimelineRulerHeight.px).clip(),
                ) {
                    Box(
                        modifier = Modifier.position((-scroll.offsetX).px, 0.px)
                            .size(contentWidth.px, TimelineRulerHeight.px),
                    ) {
                        TimeRuler(controller, pxPerSec, contentWidth, bump) { isScrubbing = it }
                    }
                }

                // Lanes: the only real scroll container; the ruler mirrors its offset.
                Box(
                    id = "timeline-lane-viewport",
                    tags = listOf("timeline-scroll"),
                    modifier = Modifier.size(100.percent, 0.px)
                        .grow(1f)
                        .background(TimelineColors.Background)
                        .clip()
                        .scrollable(state = scroll, vertical = !isCurveView)
                        .onPlaced { laneViewport = it }
                        .onScroll { event -> handleTimelineScroll(event, controller, scroll, zoomGlide, laneViewport, bump) },
                ) {
                    if (isCurveView) {
                        TimelineCurveGraph(
                            controller = controller,
                            lanes = curveLanes(rows, controller),
                            pxPerSec = pxPerSec,
                            contentWidth = contentWidth,
                            height = laneViewport.height,
                            scrollX = scroll.offsetX,
                            visibleTimes = visibleTimes,
                            scroll = scroll,
                            refresh = bump,
                        )
                    } else {
                        TimelineLanes(
                            controller = controller,
                            rows = rows,
                            pxPerSec = pxPerSec,
                            contentWidth = contentWidth,
                            contentHeight = scrollContentHeight,
                            rowsHeight = rowsHeight,
                            visibleTimes = visibleTimes,
                            scroll = scroll,
                            refresh = bump,
                        )
                    }
                }
            }
        }
    }
}

/** Drag to give the track list more or less room. */
@Composable
private fun HeaderSplitter(controller: TimelineController, refresh: () -> Unit) {
    val start = remember { floatArrayOf(controller.headerWidth) }
    Box(
        id = "timeline-header-splitter",
        tags = listOf("timeline-splitter"),
        modifier = Modifier.size(2.px, 100.percent)
            .cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .input(hoverable = true, draggable = true)
            .onPress { start[0] = controller.headerWidth }
            .onDrag { event ->
                controller.headerWidth = (start[0] + event.dragTotalX)
                    .coerceIn(TimelineMinHeaderWidth, TimelineMaxHeaderWidth)
                event.consume()
                refresh()
            },
    )
}

/**
 * Wheel handling for the lane viewport, by the keymap: as in Blender, the wheel zooms the time about the middle,
 * Ctrl+wheel goes along the time, Shift+wheel goes down the tracks (in the graph, along the values) and Alt+wheel
 * zooms the values of the graph. A wheel turned sideways always goes along the time.
 */
private fun handleTimelineScroll(
    event: UiEvent,
    controller: TimelineController,
    scroll: UiScrollHandle,
    zoom: TimelineZoomGlide,
    viewport: UiRect,
    refresh: () -> Unit,
) {
    event.consume()
    val modifiers = currentUiKeyModifiers()
    val down = -event.rawScrollY
    val sideways = -event.rawScrollX
    if (sideways != 0f) scroll.animateScrollBy(deltaX = sideways * TimelineScrollStep)
    if (down != 0f) {
        val curves = controller.viewMode == TimelineViewMode.CURVES && viewport.height > 0f
        when {
            HollowKeys.WHEEL_ZOOM_TIME.matchesWheel(modifiers) -> zoom.zoomBy(controller, scroll, viewport.width, down)
            HollowKeys.WHEEL_ALONG_TIME.matchesWheel(modifiers) -> scroll.animateScrollBy(deltaX = down * TimelineScrollStep)
            HollowKeys.WHEEL_ZOOM_VALUES.matchesWheel(modifiers) -> if (curves) zoomValuesAt(controller, event.y - viewport.y, viewport.height, down)
            HollowKeys.WHEEL_SCROLL.matchesWheel(modifiers) -> if (curves) {
                val axis = controller.curveAxis
                axis.glideTo(axis.targetCenter - down * axis.targetSpan * TimelineValuePanStep, axis.targetSpan)
            } else {
                scroll.animateScrollBy(deltaY = down * TimelineScrollStep)
            }
        }
    }
    refresh()
}

/** Zooms the values of the graph [notches] around the value [localY] pixels down a graph [height] tall. */
private fun zoomValuesAt(controller: TimelineController, localY: Float, height: Float, notches: Float) {
    val axis = controller.curveAxis
    val cursorValue = curveYToValue(localY, controller.curveValueCenter, controller.curveValueSpan, height)
    val span = (axis.targetSpan * (1f + notches * TimelineValueZoomStep)).coerceIn(TimelineMinValueSpan, TimelineMaxValueSpan)
    axis.glideTo(cursorValue + (localY - height * 0.5f) / height * span, span)
}

/** The wheel over the track list: down the tracks, or with Shift along long names. */
internal fun scrollTrackList(event: UiEvent, tracks: UiScrollHandle, names: UiScrollHandle) {
    event.consume()
    val down = -event.rawScrollY
    val sideways = -event.rawScrollX
    if (currentUiKeyModifiers() and GLFW.GLFW_MOD_SHIFT != 0) {
        names.animateScrollBy(deltaX = (down + sideways) * TimelineScrollStep)
        return
    }
    if (sideways != 0f) names.animateScrollBy(deltaX = sideways * TimelineScrollStep)
    if (down != 0f) tracks.animateScrollBy(deltaY = down * TimelineScrollStep)
}

@Composable
private fun TimeRuler(
    controller: TimelineController,
    pxPerSec: Float,
    contentWidth: Float,
    refresh: () -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
) {
    Box(
        id = "timeline-ruler",
        modifier = Modifier.size(contentWidth.px, TimelineRulerHeight.px)
            .cursor(UiCursorShape.HAND)
            .onPress { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                onScrubbingChange(true)
                controller.clearSelection()
                controller.applyCurrentTime(
                    controller.snapPlayhead(timelineTimeAt(event.localX, pxPerSec, controller.workAreaEnd), pxPerSec)
                )
                refresh()
            }
            .onDrag { event ->
                controller.applyCurrentTime(
                    controller.snapPlayhead(timelineTimeAt(event.localX, pxPerSec, controller.workAreaEnd), pxPerSec)
                )
                event.consume()
                refresh()
            }
            .onRelease { onScrubbingChange(false) },
    ) {
        val majorStep = rulerMajorStep(pxPerSec)
        val minorPerMajor = 5
        val seconds = timelineRulerSeconds(0f, contentWidth, pxPerSec)
        var second = 0f
        val end = seconds.last.toFloat()
        while (second <= end) {
            val x = TimelineLeftPadding + second * pxPerSec
            Box(
                modifier = Modifier.position(x.px, (TimelineRulerHeight - 6f).px)
                    .size(1.px, 6.px)
                    .background(TimelineColors.Muted),
            )
            Text(
                formatSeconds(second),
                tags = listOf("timeline-ruler-label"),
                modifier = Modifier.position((x + 3f).px, 2.px),
            )
            if (pxPerSec > 60f) {
                val minorStep = majorStep / minorPerMajor
                for (i in 1 until minorPerMajor) {
                    val mx = TimelineLeftPadding + (second + i * minorStep) * pxPerSec
                    Box(
                        modifier = Modifier.position(mx.px, (TimelineRulerHeight - 3f).px)
                            .size(1.px, 3.px)
                            .background(TimelineColors.Grid),
                    )
                }
            }
            second += majorStep
        }
        WorkAreaHandle(controller, pxPerSec, refresh)
        // Playhead head riding on the ruler: a thin line topped by a glowing marker.
        val px = TimelineLeftPadding + controller.currentTime * pxPerSec
        Box(
            modifier = Modifier.position((px - 0.5f).px, 0.px)
                .size(1.px, TimelineRulerHeight.px)
                .background(controller.playheadColor),
        )
        Box(
            modifier = Modifier.position((px - 5f).px, 0.px)
                .size(10.px, 10.px)
                .shape(PlayheadHeadShape, UiPaint.Color(controller.playheadColor)),
        )
    }
}

/** Room left after the end of the work area when it is fitted into the lanes. */
private const val TimelineFrameMargin = 24f

private fun rulerMajorStep(pxPerSec: Float): Float = when {
    pxPerSec < 20f -> 5f
    pxPerSec < 60f -> 1f
    pxPerSec < 160f -> 0.5f
    else -> 0.25f
}

@Composable
private fun WorkAreaHandle(controller: TimelineController, pxPerSec: Float, refresh: () -> Unit) {
    val x = TimelineLeftPadding + controller.workAreaEnd * pxPerSec
    val color = if (controller.isWorkAreaSelected) UiColor.White else TimelineColors.Accent
    // work-area end captured at grab, so total-offset dragging stays in sync past the min bound.
    var dragStartEnd by remember { mutableStateOf(0f) }
    // A ']' bracket capping the usable area: spine on the work-area line, ticks pointing back into it.
    Box(
        id = "timeline-work-area-end",
        mode = UiBoxMode.STACK,
        modifier = Modifier.position((x - 6f).px, 0.px)
            .size(8.px, TimelineRulerHeight.px)
            .cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress {
                controller.selectedKeyframes.clear()
                controller.isWorkAreaSelected = true
                dragStartEnd = controller.workAreaEnd
                refresh()
            }
            .onDrag { event ->
                val maxKeyTime = timelineMaxKeyTime(controller)
                val raw = (dragStartEnd + timelineTimeDelta(event.dragTotalX, pxPerSec))
                    .coerceAtLeast(maxKeyTime)
                    .coerceAtLeast(0.1f)
                val next = snapTimelineTime(raw)
                    .coerceAtLeast(maxKeyTime)
                    .coerceAtLeast(0.1f)
                controller.workAreaEnd = if (event.modifiers == 0) round(next * 100f) / 100f else next
                if (controller.currentTime > controller.workAreaEnd) controller.applyCurrentTime(0f)
                controller.isWorkAreaSelected = true
                event.consume()
                refresh()
            },
    ) {
        Box(modifier = Modifier.position(6.px, 0.px).size(2.px, TimelineRulerHeight.px).background(color))
        Box(modifier = Modifier.position(0.px, 0.px).size(6.px, 2.px).background(color))
        Box(modifier = Modifier.position(0.px, (TimelineRulerHeight - 2f).px).size(6.px, 2.px).background(color))
    }
}

@Composable
internal fun Playhead(controller: TimelineController, pxPerSec: Float, rowsHeight: Float) {
    val x = TimelineLeftPadding + controller.currentTime * pxPerSec
    Box(
        id = "timeline-playhead",
        modifier = Modifier.position((x - 0.5f).px, 0.px)
            .size(1.px, rowsHeight.coerceAtLeast(1f).px)
            .background(controller.playheadColor)
            .inputTransparent(),
    )
}

// ---------------------------------------------------------------------------
// Save / Load dialogs (lightweight overlays over the timeline dock)
// ---------------------------------------------------------------------------

@Composable
private fun SaveCutsceneDialog(session: CutsceneEditorSession, onClose: () -> Unit) {
    var name by remember { mutableStateOf(session.playback.toData().name) }
    DialogFrame(CutsceneLang.SAVE_TITLE.lang, onClose) {
        Text(CutsceneLang.NAME.lang, modifier = Modifier.fontSize(10f).foreground(TimelineColors.Muted))
        TextField(
            value = name,
            onChange = { name = it },
            modifier = Modifier.size(100.percent, 24.px)
                .background(TimelineColors.Background)
                .border(1.px, TimelineColors.Border, 3f)
                .padding(6.px, 2.px)
                .foreground(TimelineColors.Text)
                .fontSize(11f),
        )
        Row(modifier = Modifier.size(100.percent, UiLength.Auto).gap(8.px).align(horizontal = UiAlign.END)) {
            ToolbarButton(CutsceneLang.CANCEL.lang, "cutscene-save-cancel") { onClose() }
            ToolbarButton(CutsceneLang.SAVE.lang, "cutscene-save-confirm", primary = true) {
                if (name.isNotBlank()) {
                    session.exportCutscene("", name.trim())
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun LoadCutsceneDialog(session: CutsceneEditorSession, onClose: () -> Unit) {
    val files = remember { CutsceneStorage.listFiles() }
    DialogFrame(CutsceneLang.LOAD_TITLE.lang, onClose) {
        if (files.isEmpty()) {
            Text(CutsceneLang.NO_CUTSCENES.lang, modifier = Modifier.fontSize(11f).foreground(TimelineColors.Muted))
        } else {
            Column(
                modifier = Modifier.size(100.percent, UiLength.Auto)
                    .gap(2.px)
                    .scrollable(horizontal = false),
            ) {
                files.forEach { file ->
                    Box(
                        modifier = Modifier.size(100.percent, 24.px)
                            .background(TimelineColors.Background)
                            .border(1.px, TimelineColors.Border, 3f)
                            .cursor(UiCursorShape.HAND)
                            .onClick {
                                session.importCutscene(file)
                                onClose()
                                it.consume()
                            },
                    ) {
                        Text(
                            file.removeSuffix(".${CutsceneStorage.EXTENSION}"),
                            modifier = Modifier.align(vertical = UiAlign.CENTER)
                                .margin(8.px, 0.px, 0.px, 0.px)
                                .fontSize(11f)
                                .foreground(TimelineColors.Text),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.size(100.percent, UiLength.Auto).align(horizontal = UiAlign.END)) {
            ToolbarButton(CutsceneLang.CLOSE.lang, "cutscene-load-close") { onClose() }
        }
    }
}

@Composable
internal fun DialogFrame(title: String, onClose: () -> Unit, content: HollowUiContent) {
    val viewport = LocalUiViewport.current
    Popup(
        anchorBounds = viewport,
        alignment = CenteredOnAnchor,
        id = "cutscene-dialog-popup",
        layer = 100,
        modal = true,
        onDismiss = onClose,
    ) {
        Column(
            id = "cutscene-dialog",
            modifier = Modifier.size(340.px, UiLength.Auto)
                .maxSize(height = (viewport.height - 80f).coerceAtLeast(120f).px)
                .background(TimelineColors.Panel)
                .border(1.px, TimelineColors.Border, 6f)
                .padding(14.px)
                .gap(8.px)
                .scrollable(horizontal = false),
        ) {
            Text(title, modifier = Modifier.fontSize(13f).foreground(TimelineColors.Text))
            content()
        }
    }
}

private val CenteredOnAnchor = UiPopupAlignment(
    anchorHorizontal = UiAlign.CENTER,
    anchorVertical = UiAlign.CENTER,
    popupHorizontal = UiAlign.CENTER,
    popupVertical = UiAlign.CENTER,
)
