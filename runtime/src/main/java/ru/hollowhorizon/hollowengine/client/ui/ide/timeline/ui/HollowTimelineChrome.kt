package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.docking.DockTags
import ru.hollowhorizon.hollowengine.client.ui.docking.LocalDockPanelTitle
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.abs
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits

/** The stylesheet of the timeline window's chrome; lanes and keys are drawn from [TimelineColors]. */
internal const val TimelineStylesheet = "hollowengine:ui/styles/timeline.hss"

/** The icons of the timeline, one family drawn for it. */
internal object TimelineIcons {
    private const val ROOT = "hollowengine:textures/gui/icons/timeline/"

    const val PLAY = ROOT + "play.svg"
    const val PAUSE = ROOT + "pause.svg"
    const val TO_START = ROOT + "to_start.svg"
    const val TO_END = ROOT + "to_end.svg"
    const val PREV_KEY = ROOT + "prev_key.svg"
    const val NEXT_KEY = ROOT + "next_key.svg"
    const val ZOOM_IN = ROOT + "zoom_in.svg"
    const val ZOOM_OUT = ROOT + "zoom_out.svg"
    const val MORE = ROOT + "more.svg"
    const val DOPE_SHEET = ROOT + "dope_sheet.svg"
    const val CURVES = ROOT + "curves.svg"
    const val FRAME = ROOT + "frame.svg"
    const val CAPTURE = ROOT + "capture.svg"
    const val RECORD = ROOT + "record.svg"
    const val EYE = ROOT + "eye.svg"
    const val EYE_OFF = ROOT + "eye_off.svg"
    const val LOCK = ROOT + "lock.svg"
    const val LOCK_OPEN = ROOT + "lock_open.svg"
    const val CLOSE = ROOT + "close.svg"
    const val DELETE = ROOT + "delete.svg"
    const val SMOOTH = ROOT + "smooth.svg"
    const val FILM = ROOT + "film.svg"
    const val SAVE = ROOT + "save.svg"
    const val LOAD = ROOT + "load.svg"
}

/**
 * Which of the shared timeline controls make sense for what is being edited.
 */
class TimelineFeatures(
    val capture: Boolean = true,
    val storage: Boolean = true,
    val cameraPreview: Boolean = true,
    /** Play, pause and the jumps to either end; a curve with no clock has no use for them. */
    val playback: Boolean = true,
    /** Auto-keying, for an owner that turns edits into keys while [TimelineController.isRecording] is on. */
    val record: Boolean = false,
) {
    companion object {
        val CUTSCENE = TimelineFeatures()
    }
}

@Composable
internal fun TimelineToolbar(
    controller: TimelineController,
    onCapture: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    refresh: () -> Unit,
    features: TimelineFeatures = TimelineFeatures.CUTSCENE,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(UiRect.Zero) }

    val parked = LocalDockPanelTitle.current
    Row(
        id = parked?.headerId ?: "cutscene-timeline-toolbar",
        tags = listOf("timeline-toolbar"),
        modifier = parked?.dragHandle,
    ) {
        parked?.let { panel ->
            panel.icon?.let { icon -> Image(icon, tags = listOf(DockTags.PinnedHeaderIcon)) }
            Text(panel.title, tags = listOf(DockTags.PinnedHeaderLabel), modifier = Modifier.textWrap(false))
            TimelineSeparator()
        }
        if (features.playback) {
            TimelineButton(TimelineIcons.TO_START, "timeline-start", CutsceneLang.TO_START.lang) {
                controller.isPlaying = false
                controller.applyCurrentTime(0f)
                refresh()
            }
            TimelineButton(TimelineIcons.PREV_KEY, "timeline-prev-key", CutsceneLang.PREV_KEY.lang) {
                if (controller.jumpToKey(-1)) refresh()
            }
            TimelineButton(
                if (controller.isPlaying) TimelineIcons.PAUSE else TimelineIcons.PLAY,
                "timeline-play",
                CutsceneLang.PLAY.lang,
                active = controller.isPlaying,
            ) {
                controller.togglePlayback()
                refresh()
            }
            TimelineButton(TimelineIcons.NEXT_KEY, "timeline-next-key", CutsceneLang.NEXT_KEY.lang) {
                if (controller.jumpToKey(1)) refresh()
            }
            TimelineButton(TimelineIcons.TO_END, "timeline-end", CutsceneLang.TO_END.lang) {
                controller.isPlaying = false
                controller.applyCurrentTime(controller.workAreaEnd)
                refresh()
            }
            TimeField(controller, refresh)
            TimelineSeparator()
        }

        TimelineButton(
            TimelineIcons.DOPE_SHEET,
            "timeline-view-dope",
            CutsceneLang.VIEW_DOPE_SHEET.lang,
            active = controller.viewMode == TimelineViewMode.DOPE_SHEET,
        ) {
            controller.viewMode = TimelineViewMode.DOPE_SHEET
            refresh()
        }
        TimelineButton(
            TimelineIcons.CURVES,
            "timeline-view-curves",
            CutsceneLang.VIEW_CURVES.lang,
            active = controller.viewMode == TimelineViewMode.CURVES,
        ) {
            controller.enterCurveView()
            refresh()
        }

        // Push the trailing controls to the right edge.
        Box(modifier = Modifier.size(0.px, 1.px).grow(1f))

        if (features.record) {
            TimelineButton(
                TimelineIcons.RECORD,
                "timeline-record",
                CutsceneLang.RECORD.lang,
                active = controller.isRecording,
                tags = listOf("record"),
            ) {
                controller.isRecording = !controller.isRecording
                refresh()
            }
        }
        if (features.capture) {
            TimelineButton(TimelineIcons.CAPTURE, "timeline-capture", CutsceneLang.CAPTURE_KEYFRAME.lang) {
                onCapture()
                refresh()
            }
        }
        if (features.record || features.capture) TimelineSeparator()

        val curves = controller.viewMode == TimelineViewMode.CURVES
        TimelineButton(
            TimelineIcons.FRAME,
            "timeline-frame",
            (if (curves) CutsceneLang.FRAME_CURVES else CutsceneLang.FRAME_TIME).lang,
        ) {
            if (curves) controller.frameCurves()
            controller.requestFrameTime()
            refresh()
        }
        TimelineButton(TimelineIcons.ZOOM_OUT, "timeline-zoom-out", CutsceneLang.ZOOM_OUT.lang) {
            zoomAroundCenter(controller, 1f / TimelineZoomButtonFactor)
            refresh()
        }
        TimelineButton(TimelineIcons.ZOOM_IN, "timeline-zoom-in", CutsceneLang.ZOOM_IN.lang) {
            zoomAroundCenter(controller, TimelineZoomButtonFactor)
            refresh()
        }

        // Everything that doesn't fit on the bar lives in an overflow menu.
        Box(modifier = Modifier.onPlaced { menuAnchor = it }) {
            TimelineButton(TimelineIcons.MORE, "timeline-overflow", CutsceneLang.MORE.lang, active = menuOpen) {
                menuOpen = !menuOpen
            }
        }
        if (menuOpen) ContextMenu(
            id = "timeline-overflow-menu",
            anchorBounds = menuAnchor,
            alignment = OverflowMenuAlignment,
            onExpandedChange = { if (!it) menuOpen = false },
            items = listOfNotNull(
                UiDropdownItem(
                    CutsceneLang.DELETE_SELECTED.lang,
                    icon = TimelineIcons.DELETE,
                    enabled = controller.selectedKeyframes.isNotEmpty(),
                    shortcut = "Del",
                ) {
                    controller.deleteSelectedKeyframes(); refresh()
                },
                UiDropdownItem(
                    CutsceneLang.SMOOTH_SELECTED.lang,
                    icon = TimelineIcons.SMOOTH,
                    enabled = controller.canEditSelectedCurves,
                    shortcut = "S",
                ) {
                    controller.smoothSelectedKeyframes(); refresh()
                },
                if (!features.cameraPreview) null else UiDropdownItem(
                    if (controller.isCameraPreviewEnabled) CutsceneLang.CAMERA_PREVIEW_ON.lang
                    else CutsceneLang.CAMERA_PREVIEW_OFF.lang,
                    icon = TimelineIcons.FILM,
                    closeOnClick = false,
                    separatorBefore = true,
                ) {
                    controller.applyCameraPreviewEnabled(!controller.isCameraPreviewEnabled)
                    refresh()
                },
                if (!features.storage) null else UiDropdownItem(
                    CutsceneLang.SAVE.lang,
                    icon = TimelineIcons.SAVE,
                    separatorBefore = true,
                ) { onSave() },
                if (!features.storage) null else UiDropdownItem(
                    CutsceneLang.LOAD.lang,
                    icon = TimelineIcons.LOAD,
                ) { onLoad() },
            ),
        )
    }
}

/** Where the playhead is, as a field: typing a time jumps there. */
@Composable
private fun TimeField(controller: TimelineController, refresh: () -> Unit) {
    TextField(
        value = formatSeconds(controller.currentTime),
        id = "timeline-time",
        fontSize = 9f,
        filter = UiTextInputFilter.DECIMAL,
        tags = listOf("timeline-time"),
        modifier = Modifier.tooltipOnHover(CutsceneLang.TIME_HINT.lang),
        onChange = { typed ->
            val time = typed.toFloatOrNull() ?: return@TextField
            if (abs(time - controller.currentTime) < TimeFieldEpsilon) return@TextField
            controller.isPlaying = false
            controller.applyCurrentTime(time)
            refresh()
        },
    )
    Text("/ " + formatSeconds(controller.workAreaEnd), tags = listOf("timeline-duration"))
}

private const val TimeFieldEpsilon = 0.005f

/** A flat button of the bar, the way the IDE toolbar draws them. */
@Composable
private fun TimelineButton(
    icon: String,
    id: String,
    tooltip: String,
    active: Boolean = false,
    tags: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    Box(
        id = id,
        tags = listOf("timeline-button") + tags + if (active) listOf("active") else emptyList(),
        modifier = Modifier.cursor(UiCursorShape.HAND).tooltipOnHover(tooltip).onClick { event ->
            onClick()
            event.consume()
        },
    ) {
        key(icon) { Image(icon, tags = listOf("timeline-button-icon")) }
    }
}

@Composable
internal fun TimelineHeaders(
    controller: TimelineController,
    rows: List<TimelineRow>,
    scroll: UiScrollHandle,
    verticalOffset: Float,
    ownsVerticalScroll: Boolean,
    contentHeight: Float,
    trackScroll: UiScrollHandle,
    refresh: () -> Unit,
) {
    val width = controller.headerWidth
    val labelWidth = maxOf(width, rows.maxOfOrNull { headerLabelWidth(it) } ?: width)
    var menu by remember { mutableStateOf<TrackMenu?>(null) }

    Column(
        id = "timeline-headers",
        modifier = Modifier.size(width.px, 100.percent),
    ) {
        // Corner cell, level with the ruler so the rows below line up with the lanes.
        Box(
            mode = UiBoxMode.STACK,
            tags = listOf("timeline-corner"),
            modifier = Modifier.size(width.px, TimelineRulerHeight.px),
        ) {
            Text(
                CutsceneLang.TRACKS.lang,
                tags = listOf("timeline-corner-label"),
                modifier = Modifier.align(vertical = UiAlign.CENTER),
            )
        }
        val overflows = labelWidth > width + 1f
        Box(
            tags = listOf("timeline-scroll"),
            modifier = Modifier.size(width.px, 0.px).grow(1f).clip().scrollable(
                state = scroll,
                vertical = ownsVerticalScroll,
                horizontal = overflows,
                hasHorizontalScrollbar = overflows,
            ).onScroll { event -> scrollTrackList(event, trackScroll, scroll) },
        ) {
            Box(
                modifier = Modifier.position((-scroll.offsetX).px, (-verticalOffset).px)
                    .size(labelWidth.px, contentHeight.coerceAtLeast(1f).px),
            ) {
                rows.forEach { row ->
                    key(row.id) {
                        TimelineHeaderRow(row, controller, labelWidth, refresh) { event ->
                            menu = TrackMenu(event.x, event.y, row)
                        }
                    }
                }
            }
        }
    }

    menu?.let { open ->
        ContextMenu(
            id = "timeline-track-menu",
            anchorBounds = UiRect(open.x, open.y, 0f, 0f),
            alignment = UiPopupAlignment.Cursor,
            items = trackMenu(controller, open.row, refresh),
            onExpandedChange = { if (!it) menu = null },
        )
    }
}

/** Room at the end of a track row, so the list's scrollbar does not sit on the row's buttons. */
private const val TrackRowEndPadding = 9f

private class TrackMenu(val x: Float, val y: Float, val row: TimelineRow)

private fun headerLabelWidth(row: TimelineRow): Float =
    8f + row.depth * 10f + row.label.length * 5.2f + headerControlsWidth(row)

private fun headerControlsWidth(row: TimelineRow): Float = when (row.kind) {
    TimelineRowKind.CHANNEL -> 20f
    else -> 58f
}

@Composable
private fun TimelineHeaderRow(
    row: TimelineRow,
    controller: TimelineController,
    width: Float,
    refresh: () -> Unit,
    onMenu: (UiEvent) -> Unit,
) {
    val top = row.y - TimelineRulerHeight
    val isCurveView = controller.viewMode == TimelineViewMode.CURVES
    val rowCurves = if (isCurveView) row.curves.filter { it.spec.supportsCurveEditor } else row.curves
    val isFocused = isCurveView && rowCurves.isNotEmpty() && rowCurves.all { controller.isFocused(it) }
    val tags = buildList {
        add("timeline-row")
        add(row.kind.name.lowercase())
        if (isFocused) add("focused")
        if (row.locked) add("locked")
        if (!row.visible) add("muted")
    }
    Box(
        id = "header-${row.id}",
        mode = UiBoxMode.STACK,
        tags = tags,
        modifier = Modifier.position(0.px, top.px).size(width.px, row.height.px).onClick { event ->
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                onMenu(event)
                event.consume()
                return@onClick
            }
            // In the graph a click picks what the graph shows; ctrl adds to it. Everywhere else
            // the row list is what it always was.
            if (isCurveView && rowCurves.isNotEmpty()) {
                controller.focusCurves(rowCurves, additive = event.modifiers and GLFW.GLFW_MOD_CONTROL != 0)
            }
            when {
                row.group != null -> row.group.isCollapsed = !row.group.isCollapsed
                else -> if (!isCurveView) controller.clearSelection()
            }
            event.consume()
            refresh()
        },
    ) {
        Row(
            modifier = Modifier.size(100.percent, 100.percent).alignItems(vertical = UiAlign.CENTER)
                .padding(0.px, 0.px, TrackRowEndPadding.px, 0.px).gap(2.px),
        ) {
            Box(modifier = Modifier.size((4f + row.depth * 10f).px, 1.px))
            when {
                row.group != null -> DisclosureArrow("${row.id}-fold", !row.group.isCollapsed) {
                    row.group.isCollapsed = !row.group.isCollapsed
                    refresh()
                }

                row.kind == TimelineRowKind.PROPERTY && (row.property?.curves?.size ?: 0) > 1 ->
                    DisclosureArrow("${row.id}-fold", row.property?.isExpanded == true) {
                        row.property?.let { it.isExpanded = !it.isExpanded }
                        refresh()
                    }

                else -> Box(modifier = Modifier.size(10.px, 1.px))
            }
            row.color?.let { swatch ->
                Box(
                    modifier = Modifier.size(3.px, 10.px).align(vertical = UiAlign.CENTER)
                        .background(swatch.toUiColor(if (row.visible) 1f else 0.35f)).borderRadius(1.5f)
                        .margin(0.px, 0.px, 3.px, 0.px),
                )
            }
            Text(
                row.label,
                tags = listOf("timeline-row-label"),
                modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).align(vertical = UiAlign.CENTER)
                    .textOverflow(UiTextOverflow.DOTS),
            )
            HeaderControls(row, controller, refresh)
        }
    }
}

@Composable
private fun HeaderControls(row: TimelineRow, controller: TimelineController, refresh: () -> Unit) {
    val hideProperty = controller.onHideProperty
    val hideGroup = controller.onHideGroup
    val property = row.property.takeIf { row.kind == TimelineRowKind.PROPERTY }
    val group = row.group

    if (property != null && hideProperty != null || group != null && hideGroup != null) {
        RowToggle("${row.id}-remove", TimelineIcons.CLOSE, CutsceneLang.TRACK_REMOVE.lang, listOf("hide-track")) {
            if (property != null) hideProperty?.invoke(property) else group?.let { hideGroup?.invoke(it) }
            refresh()
        }
    }

    when (row.kind) {
        TimelineRowKind.CHANNEL -> {
            val curve = row.curve ?: return
            VisibilityToggle("${row.id}-visible", curve.isVisible) {
                curve.isVisible = !curve.isVisible
                refresh()
            }
        }

        TimelineRowKind.PROPERTY -> {
            val owner = row.property ?: return
            VisibilityToggle("${row.id}-visible", owner.isVisible) {
                owner.isVisible = !owner.isVisible
                refresh()
            }
            LockToggle("${row.id}-lock", owner.isLocked) {
                owner.isLocked = !owner.isLocked
                refresh()
            }
        }

        TimelineRowKind.GROUP -> {
            val owner = row.group ?: return
            VisibilityToggle("${row.id}-visible", owner.isVisible) {
                owner.isVisible = !owner.isVisible
                refresh()
            }
            LockToggle("${row.id}-lock", owner.isLocked) {
                owner.isLocked = !owner.isLocked
                refresh()
            }
        }
    }
}

@Composable
private fun VisibilityToggle(id: String, visible: Boolean, onClick: () -> Unit) = RowToggle(
    id,
    if (visible) TimelineIcons.EYE else TimelineIcons.EYE_OFF,
    (if (visible) CutsceneLang.TRACK_HIDE else CutsceneLang.TRACK_SHOW).lang,
    if (visible) listOf("on") else emptyList(),
    onClick,
)

@Composable
private fun LockToggle(id: String, locked: Boolean, onClick: () -> Unit) = RowToggle(
    id,
    if (locked) TimelineIcons.LOCK else TimelineIcons.LOCK_OPEN,
    (if (locked) CutsceneLang.TRACK_UNLOCK else CutsceneLang.TRACK_LOCK).lang,
    if (locked) listOf("warn") else emptyList(),
    onClick,
)

@Composable
private fun RowToggle(id: String, icon: String, tooltip: String, states: List<String>, onClick: () -> Unit) {
    Box(
        id = id,
        tags = listOf("timeline-row-toggle") + states,
        modifier = Modifier.align(vertical = UiAlign.CENTER).cursor(UiCursorShape.HAND).tooltipOnHover(tooltip)
            .onClick {
                onClick()
                it.consume()
            },
    ) {
        key(icon) { Image(icon, tags = listOf("timeline-row-icon")) }
    }
}

@Composable
private fun DisclosureArrow(id: String, expanded: Boolean, onClick: () -> Unit) {
    Box(
        id = id,
        tags = listOf("tree-expander"),
        attributes = mapOf("expanded" to expanded.toString()),
        modifier = Modifier.size(10.px, 10.px).margin(0.px).align(vertical = UiAlign.CENTER).cursor(UiCursorShape.HAND)
            .onClick {
                onClick()
                it.consume()
            },
    )
}

/**
 * What a right click on a track offers: its keys, whether it shows and takes edits, the basis a
 * rotation is keyed in, and taking it off the list.
 */
private fun trackMenu(controller: TimelineController, row: TimelineRow, refresh: () -> Unit): List<UiDropdownItem> =
    buildList {
        val keys = row.curves.flatMap { it.keyframes }
        add(UiDropdownItem(CutsceneLang.TRACK_SELECT_KEYS.lang, enabled = keys.isNotEmpty() && !row.locked) {
            controller.select(keys, additive = false)
            refresh()
        })

        val property = row.property.takeIf { row.kind == TimelineRowKind.PROPERTY }
        val type = property?.type as? RotationPropertyType
        if (property != null && type != null) {
            add(
                UiDropdownItem(
                    CutsceneLang.ROTATION_MODE.lang,
                    separatorBefore = true,
                    children = RotationMode.entries.map { mode ->
                        UiDropdownItem(rotationModeLabel(mode), checked = type.mode == mode) {
                            controller.edit(TimelineEdits.ROTATION_BASIS) { property.setRotationMode(mode) }
                            refresh()
                        }
                    },
                )
            )
        }

        val hideProperty = controller.onHideProperty
        val hideGroup = controller.onHideGroup
        val group = row.group
        if (property != null && hideProperty != null) {
            add(UiDropdownItem(CutsceneLang.TRACK_REMOVE.lang, icon = TimelineIcons.CLOSE, separatorBefore = true) {
                hideProperty(property)
                refresh()
            })
        } else if (group != null && hideGroup != null) {
            add(UiDropdownItem(CutsceneLang.TRACK_REMOVE.lang, icon = TimelineIcons.CLOSE, separatorBefore = true) {
                hideGroup(group)
                refresh()
            })
        }
    }

@Composable
internal fun ToolbarButton(label: String, id: String, color: UiColor = TimelineColors.PanelAlt, onClick: () -> Unit) {
    Box(
        id = id,
        modifier = Modifier.size(UiLength.Auto, 20.px).background(color).borderRadius(3f)
            .padding(10.px, 0.px).cursor(UiCursorShape.HAND).onClick { event ->
                onClick()
                event.consume()
            },
    ) {
        Text(
            label,
            modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER).fontSize(9f).foreground(TimelineColors.Text)
                .textWrap(false).textOverflow(UiTextOverflow.DOTS).textAlign(UiTextAlign.CENTER),
        )
    }
}

@Composable
private fun TimelineSeparator() {
    Box(tags = listOf("timeline-separator"))
}

internal fun formatSeconds(value: Float): String = "%.2f".format(value).replace(',', '.')

/** Zoom keeping the visible center roughly fixed; used by the toolbar buttons. */
internal fun zoomAroundCenter(controller: TimelineController, factor: Float) {
    controller.pixelsPerSecond = (controller.pixelsPerSecond * factor).coerceIn(TimelineMinZoom, TimelineMaxZoom)
}

internal const val TimelineZoomButtonFactor = 1.25f

/** The overflow menu hangs under its button, flush with its right edge, since the button ends the bar. */
private val OverflowMenuAlignment = UiPopupAlignment(anchorHorizontal = UiAlign.END, popupHorizontal = UiAlign.END)
