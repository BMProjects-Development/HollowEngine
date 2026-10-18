package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoSettings
import ru.hollowhorizon.hollowengine.client.editor.GizmoTarget
import ru.hollowhorizon.hollowengine.client.history.UndoKeys
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.GizmoPointerWrap
import ru.hollowhorizon.hollowengine.client.ui.ide.GizmoToolbarButtons
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.PublishScene
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorColors
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInsertMenu
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineKeys
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackPick
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackPreview
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.playbackInspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.playbackText
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.PublishTimeline
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.hasInspectableSelection
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.OrbitCameraTurn
import ru.hollowhorizon.hollowengine.client.ui.widgets.ViewAxes
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L

private const val PlayIcon = "hollowengine:textures/gui/icons/timeline/play.svg"
private const val PauseIcon = "hollowengine:textures/gui/icons/timeline/pause.svg"
private const val RestartIcon = "hollowengine:textures/gui/icons/vfx/restart.svg"
private const val FloorIcon = "hollowengine:textures/gui/icons/vfx/floor.svg"
private const val ShapeIcon = "hollowengine:textures/gui/icons/vfx/handles.svg"

/**
 * The editor for a `.vfx` file.
 *
 * The hierarchy goes to the scene window, curves to the timeline window and the fields to the
 * inspector.
 */
@Composable
internal fun VfxEditorPanel(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeVfxDocument
    val state = remember(document) { document.editorState { VfxEditorState(document) } }
    val session = state.session

    LaunchedEffect(document.revision) {
        file.updateDirty(document.isModified)
        session.sync()
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified) file.save()
    }

    DisposableEffect(document) {
        onDispose {
            RenderSystem.recordRenderCall {
                state.preview.close()
                state.materialPreview.close()
            }
        }
    }

    LaunchedEffect(document) {
        while (isActive) {
            withFrameNanos { frameNanos ->
                state.preview.advanceCamera(frameNanos)
                val rebuilt = state.preview.sync(document.effect, file.path)
                state.playbackPreview.values.let { if (state.preview.data !== it) state.preview.data = it }
                val sought = state.preview.applyPendingSeek()
                val delta = state.preview.deltaSinceLastFrame(frameNanos)
                if (session.timeline.isPlaying && delta > 0f) state.preview.advanceBy(delta)
                if (rebuilt || session.timeline.isPlaying && !sought) {
                    session.timeline.followPlayhead(state.preview.timelineTime)
                }
            }
        }
    }

    val selected = state.selected?.takeIf { document.effect.node(it) != null }

    val timelineHasSelection = session.timeline.hasInspectableSelection()
    val playbackPick = state.playbackPick
    PublishInspector(source = "vfx-${file.path}", key = listOf(selected, timelineHasSelection, playbackPick)) {
        when {
            timelineHasSelection -> null
            playbackPick != null -> playbackInspectorTarget(
                lanes = session.playback,
                pick = playbackPick,
                conditions = VfxExpressionEditing,
                eventHint = vfxText("event_hint"),
                onRemoved = { state.playbackPick = null },
            )

            selected != null -> vfxNodeInspector(document, state, selected)
            else -> vfxEffectInspector(document, state)
        }
    }

    PublishScene(
        source = "vfx-${file.path}",
        key = listOf(document.revision, state.selected, state.selectedLane, state.expanded.toList(), state.rootExpanded),
    ) {
        vfxSceneTarget(document, state, file.path.substringAfterLast('/').substringBeforeLast('.'))
    }

    PublishTimeline(source = "vfx-${file.path}", key = file.path) {
        TimelineTarget(
            id = "vfx-${file.path}",
            controller = session.timeline,
            refresh = {
                session.commit()
                session.applyListing()
            },
            recordable = true,
        )
    }

    Column(
        tags = listOf("ide-file-panel"),
        modifier = Modifier.style(AnimatorStylesheet).background(AnimatorColors.Canvas).focusScope()
            .onKeyInput { input ->
                if (!input.repeat && TimelineKeys.handlePlayback(
                        session.timeline, input.key, input.modifiers
                    )
                ) input.consume()
            },
    ) {
        document.error?.let { message ->
            Text(
                vfxText("broken_file") + " " + message,
                modifier = Modifier.size(100.percent).padding(6.px).fontSize(10f).foreground(AnimatorColors.Muted),
            )
        }

        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            Viewport(document, state, selected)
            Toolbar(state)
            ParticleCounter(state.preview)
        }
    }
}

@Composable
private fun Toolbar(state: VfxEditorState) {
    val timeline = state.session.timeline
    val preview = state.preview
    Row(tags = listOf("viewport-toolbar"), modifier = Modifier.position(8.px, 8.px)) {
        IconButton(
            icon = if (timeline.isPlaying) PauseIcon else PlayIcon,
            tooltip = HollowKeys.PLAY.hint(vfxText(if (timeline.isPlaying) "pause" else "play")),
            iconSize = 12f,
            active = timeline.isPlaying,
        ) { timeline.isPlaying = !timeline.isPlaying }
        IconButton(RestartIcon, playbackText("preview_reset"), iconSize = 12f) { state.playbackPreview.reset() }
        IconButton(FloorIcon, vfxText("toggle_floor"), iconSize = 12f, active = preview.showFloor) {
            preview.showFloor = !preview.showFloor
        }
        IconButton(ShapeIcon, vfxText("toggle_shape"), iconSize = 12f, active = preview.showShape) {
            preview.showShape = !preview.showShape
        }
        GizmoToolbarButtons()
    }
}

/** What editor is looking at, as opposed to what it is editing. */
internal class VfxEditorState(private val document: HollowIdeVfxDocument) : VfxInspectorState(), VfxNodeSelection {
    val preview = VfxPreviewState()
    val session = VfxTimelineSession(document, preview).also { it.timeline.isPlaying = true }

    /** The data the preview plays the effect with, in place of a handle's. */
    val playbackPreview = PlaybackPreview()

    /** The event or section picked on the timeline, which the inspector shows instead of the node. */
    var playbackPick by mutableStateOf<PlaybackPick?>(null)

    init {
        document.onEdit = session::recordEdit
        preview.held = session::held
        preview.selection = { selected?.let { preview.instance?.node(it) }?.frame?.position }
        session.playback.onPick = { pick -> playbackPick = pick }
        playbackPreview.onReset = {
            preview.data = playbackPreview.values
            preview.restart()
            session.timeline.followPlayhead(0f)
        }
    }

    /** A drag in the viewport: one step back in the file, the keys it records included. */
    fun beginGesture() {
        document.beginGesture()
        session.timeline.beginHistoryTransaction(TimelineEdits.RECORD_KEYS)
    }

    /** The timeline's keys go in while the file's step is still open, so both go back together. */
    fun endGesture() {
        session.timeline.commitHistoryTransaction()
        document.endGesture()
    }

    override var selected by mutableStateOf<String?>(null)
        private set
    val expanded = mutableStateListOf<String>()
    var rootExpanded by mutableStateOf(true)

    /** The events or the sections, when the scene picked them in place of a node; null otherwise. */
    var selectedLane by mutableStateOf<String?>(null)

    /** The shape handle being dragged, with node as it was when drag started. */
    var drag: VfxGizmoHandle? = null

    /** The transform gizmo of the world, drawn over the preview. */
    val transformGizmo = VfxTransformGizmo()

    /** Turns the preview to a view picked on its axes or by the numpad. */
    val cameraTurn = OrbitCameraTurn(preview)

    /** Where the preview is on the surface, for a menu opened at the pointer. */
    var viewportBounds = UiRect.Zero

    /** Where the insert-key menu is open, at the pointer; null while it is closed. */
    var keyMenu by mutableStateOf<UiRect?>(null)

    /** Where the pointer last was: where a transform from the keyboard starts. */
    var pointerX = 0f
    var pointerY = 0f

    /** What dragged handle is set to right now, drawn beside it. */
    var readout by mutableStateOf<VfxGizmoReadout?>(null)

    override fun select(id: String?) {
        transformGizmo.session.settle()
        selected = id
        selectedLane = null
        playbackPick = null
        session.select(id)
        session.timeline.clearSelection()
    }

    override fun reveal(parent: String?) {
        if (parent == null) rootExpanded = true else if (parent !in expanded) expanded.add(parent)
    }

    fun focusProperty(nodeId: String, property: VfxProperty) = session.focus(nodeId, property)
}

/**
 * The preview itself, and the gizmo over it.
 */
@Composable
private fun Viewport(document: HollowIdeVfxDocument, state: VfxEditorState, selected: String?) {
    val preview = state.preview
    val node = selected?.let { document.effect.node(it) }
    val runtime = selected?.let { preview.instance?.node(it) }
    val driven = selected?.let { vfxDrivenLookup(document, state, it) } ?: { null }

    val gizmoKey = listOf(
        preview.yaw, preview.pitch, preview.distance, preview.targetX, preview.targetY, preview.targetZ,
        preview.viewportWidth, preview.viewportHeight, preview.time, preview.showShape, preview.revision, node,
        GizmoSettings.modes, GizmoSettings.orientation, state.session.timeline.isRecording,
    )
    val gizmo = remember(gizmoKey) {
        if (node == null) VfxGizmo.EMPTY else VfxGizmos.build(preview, node, runtime, preview.showShape, driven)
    }
    val transform = state.transformGizmo
    val session = transform.session
    GizmoPointerWrap(session, state.viewportBounds, state.pointerX, state.pointerY)
    // The node where the preview has it now, made anew for every event: the session keeps the one a move began with.
    val target: () -> GizmoTarget? = {
        val current = selected?.let { document.effect.node(it) }
        val placed = selected?.let { preview.instance?.node(it) }
        if (current == null || placed == null || preview.viewportWidth <= 1f) {
            null
        } else {
            transform.capture(preview)
            VfxNodeTarget(document, state, current.id, placed.frame, placed.parent?.frame, current.transform)
        }
    }

    Box(
        id = "vfx-viewport",
        tags = listOf("ide-file-viewport"),
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).clip().onPlaced { rect ->
            preview.viewportWidth = rect.width
            preview.viewportHeight = rect.height
            state.viewportBounds = rect
        }.input(hoverable = true, draggable = true).cursor(UiCursorShape.HAND).focus().onKeyInput { input ->
            if (input.repeat) return@onKeyInput
            if (selected != null && !session.isBusy && HollowKeys.INSERT_KEY.matches(input)) {
                val bounds = state.viewportBounds
                state.keyMenu = UiRect(bounds.x + state.pointerX, bounds.y + state.pointerY, 0f, 0f)
                input.consume()
                return@onKeyInput
            }
            val idle = !session.isBusy
            val taken = idle && UndoKeys.handle(
                document.history, input.key, input.modifiers
            ) || idle && state.cameraTurn.handleKey(input.key, input.modifiers) || session.key(
                target(), input.key, input.modifiers, state.pointerX, state.pointerY
            )
            if (taken) input.consume()
        }.onPress { event ->
            state.pointerX = event.localX
            state.pointerY = event.localY
            state.readout = null
            val left = event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT
            // A shape handle wins over the transform handles; during a keyboard transform every click is the session's.
            val handle = if (left && session.keyboard == null) gizmo.handleAt(event.localX, event.localY) else null
            if (handle == null && session.press(target(), event.localX, event.localY, event.button)) {
                event.consume()
                return@onPress
            }
            state.drag = handle
            if (handle != null) state.beginGesture() else preview.beginCameraDrag()
        }.onDrag { event ->
            val handle = state.drag
            val current = selected?.let { document.effect.node(it) }
            when {
                session.drag != null -> {
                    transform.capture(preview)
                    session.dragTo(event.localX, event.localY, event.modifiers)
                    state.readout = session.readout()?.let { (x, y, value) -> VfxGizmoReadout(x, y, value.toFloat()) }
                }

                handle != null && current != null -> {
                    val fine = event.modifiers and GLFW.GLFW_MOD_SHIFT != 0
                    val snap = event.modifiers and GLFW.GLFW_MOD_CONTROL != 0
                    val distance = VfxGizmos.dragDistance(handle, event.dragTotalX, event.dragTotalY, fine, snap)
                    document.edit { it.withNode(handle.apply(current, distance)) }
                    state.readout = VfxGizmoReadout(handle.x, handle.y, handle.readout(distance))
                }

                event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT -> preview.pan(event.dragTotalX, event.dragTotalY)
                else -> preview.orbit(event.dragTotalX, event.dragTotalY)
            }
            event.consume()
        }.onRelease {
            if (state.drag != null) state.endGesture()
            session.release()
            state.drag = null
            state.readout = null
        }.onHover { event ->
            state.pointerX = event.localX
            state.pointerY = event.localY
            transform.capture(preview)
            if (!session.moveTo(event.localX, event.localY, event.modifiers)) {
                session.hover(target(), event.localX, event.localY)
            }
        }.onScroll { event ->
            preview.zoom(event.scrollY)
            event.consume()
        }.drawBehind(key = preview) {
            preview.draw(this)
        },
    ) {
        Box(
            modifier = Modifier.size(100.percent, 100.percent).inputTransparent()
                .drawBehind(key = listOf(gizmoKey, state.drag?.id, session.hovered, session.drag, session.keyboard)) {
                    drawGizmo(gizmo, state.drag?.id)
                    session.draw(this, target())
                },
        )
        ViewAxes(state.cameraTurn, Modifier.align(UiAlign.END, UiAlign.START).margin(8.px))
        KeyInsertMenu(state.keyMenu, onPick = { parts -> selected?.let { state.session.insertKeys(it, parts) } }) {
            state.keyMenu = null
        }
        state.readout?.let { readout ->
            Text(
                formatNumber(readout.value),
                tags = listOf("vfx-gizmo-readout"),
                modifier = Modifier.position((readout.x + 10f).px, (readout.y - 20f).px).inputTransparent(),
            )
        }
    }
}

/** The number beside the handle being dragged. */
internal class VfxGizmoReadout(val x: Float, val y: Float, val value: Float)

private fun UiCanvasDrawScope.drawGizmo(gizmo: VfxGizmo, active: String?) {
    gizmo.lines.groupBy { it.color }.forEach { (color, lines) ->
        val path = GenericShape {
            lines.forEach { line ->
                moveTo(line.x0, line.y0)
                lineTo(line.x1, line.y1)
            }
        }
        drawShape(path, bounds, UiPaint.Color(color), UiDrawStyle.Stroke(width = 1.4f))
    }
    gizmo.handles.forEach { handle ->
        val half = if (handle.id == active) 5f else 4f
        drawRect(
            UiRect(handle.x - half - 1f, handle.y - half - 1f, half * 2f + 2f, half * 2f + 2f),
            UiPaint.Color(HandleOutline),
            radius = 2f,
        )
        drawRect(
            UiRect(handle.x - half, handle.y - half, half * 2f, half * 2f), UiPaint.Color(handle.color), radius = 1.5f
        )
    }
}

@Composable
private fun ParticleCounter(preview: VfxPreviewState) {
    if (preview.viewportHeight <= 0f) return
    Text(
        vfxText("particles") + ": " + preview.liveParticles,
        modifier = Modifier.position(8.px, (preview.viewportHeight - 18f).px).padding(4.px, 1.px)
            .background(CounterBackground).borderRadius(3f).fontSize(9f).foreground(AnimatorColors.Muted)
            .inputTransparent(),
    )
}

private val HandleOutline = UiColor(0.05f, 0.05f, 0.06f, 0.9f)
private val CounterBackground = UiColor(0.06f, 0.07f, 0.08f, 0.7f)
