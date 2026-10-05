package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandleId
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.PublishScene
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorColors
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorIconButton
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.PublishTimeline
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.hasInspectableSelection
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import kotlin.time.Duration.Companion.milliseconds
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits
import ru.hollowhorizon.hollowengine.client.history.UndoKeys

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
                val sought = state.preview.applyPendingSeek()
                val delta = state.preview.deltaSinceLastFrame(frameNanos)
                if (session.timeline.isPlaying && delta > 0f) state.preview.advanceBy(delta)
                if (rebuilt || session.timeline.isPlaying && !sought) {
                    session.timeline.followPlayhead(state.preview.time)
                }
            }
        }
    }

    val selected = state.selected?.takeIf { document.effect.node(it) != null }

    val timelineHasSelection = session.timeline.hasInspectableSelection()
    PublishInspector(source = "vfx-${file.path}", key = selected to timelineHasSelection) {
        when {
            timelineHasSelection -> null
            selected != null -> vfxNodeInspector(document, state, selected)
            else -> vfxEffectInspector(document, state)
        }
    }

    PublishScene(
        source = "vfx-${file.path}",
        key = listOf(document.revision, state.selected, state.expanded.toList(), state.rootExpanded),
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
        modifier = Modifier.style(AnimatorStylesheet).background(AnimatorColors.Canvas)
            .focusScope(),
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
        AnimatorIconButton(
            icon = if (timeline.isPlaying) PauseIcon else PlayIcon,
            tooltip = vfxText(if (timeline.isPlaying) "pause" else "play"),
            size = 12f,
            active = timeline.isPlaying,
        ) { timeline.isPlaying = !timeline.isPlaying }
        AnimatorIconButton(RestartIcon, vfxText("restart"), size = 12f) {
            preview.restart()
            timeline.followPlayhead(0f)
        }
        AnimatorIconButton(FloorIcon, vfxText("toggle_floor"), size = 12f, active = preview.showFloor) {
            preview.showFloor = !preview.showFloor
        }
        AnimatorIconButton(ShapeIcon, vfxText("toggle_shape"), size = 12f, active = preview.showShape) {
            preview.showShape = !preview.showShape
        }
        GizmoModes.forEach { (mode, icon, tooltip) ->
            AnimatorIconButton(icon, tooltip.lang, size = 12f, active = mode in state.gizmoModes) {
                state.gizmoModes = if (mode in state.gizmoModes) state.gizmoModes - mode else state.gizmoModes + mode
            }
        }
    }
}

/** What editor is looking at, as opposed to what it is editing. */
internal class VfxEditorState(private val document: HollowIdeVfxDocument) : VfxInspectorState(), VfxNodeSelection {
    val preview = VfxPreviewState()
    val session = VfxTimelineSession(document, preview).also { it.timeline.isPlaying = true }

    init {
        document.onEdit = session::recordEdit
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

    /** The shape handle being dragged, with node as it was when drag started. */
    var drag: VfxGizmoHandle? = null

    /** The transform gizmo of the world, drawn over the preview, and what it is set to move. */
    val transformGizmo = VfxTransformGizmo()
    var gizmoModes by mutableStateOf(setOf(GizmoEditMode.TRANSLATE))
    var transformDrag: VfxTransformDrag? = null
    var hoveredHandle by mutableStateOf<GizmoHandleId?>(null)

    /** A transform from the keyboard under way, and where the pointer last was: where the next one starts. */
    var keyboard by mutableStateOf<VfxKeyboardTransform?>(null)
    var pointerX = 0f
    var pointerY = 0f

    /** What dragged handle is set to right now, drawn beside it. */
    var readout by mutableStateOf<VfxGizmoReadout?>(null)

    override fun select(id: String?) {
        if (keyboard != null) {
            keyboard = null
            endGesture()
        }
        selected = id
        id?.let(session::touch)
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
        state.gizmoModes, state.session.timeline.isRecording,
    )
    val gizmo = remember(gizmoKey) {
        if (node == null) VfxGizmo.EMPTY else VfxGizmos.build(preview, node, runtime, preview.showShape, driven)
    }
    val transform = state.transformGizmo
    val handles = remember(gizmoKey) {
        if (node == null || runtime == null || preview.viewportWidth <= 1f) return@remember emptyList()
        transform.capture(preview)
        transform.handles(runtime.frame, state.gizmoModes, driven)
    }

    Box(
        id = "vfx-viewport",
        tags = listOf("ide-file-viewport"),
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).clip().onPlaced { rect ->
            preview.viewportWidth = rect.width
            preview.viewportHeight = rect.height
        }.input(hoverable = true, draggable = true).cursor(UiCursorShape.HAND).focus()
            .onKeyInput { input ->
                if (handleTransformKeys(document, state, driven, input) || handleHistoryKeys(document, input)) input.consume()
            }.onPress { event ->
                state.pointerX = event.localX
                state.pointerY = event.localY
                if (clickDuringKeyboardTransform(document, state, event.button)) {
                    event.consume()
                    return@onPress
                }
                val left = event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                val handle = if (left) gizmo.handleAt(event.localX, event.localY) else null
                val moving = if (left && handle == null && node != null && runtime != null) {
                    transform.pick(handles, event.localX, event.localY)?.let { picked ->
                        transform.begin(picked, runtime.frame, runtime.parent?.frame, node.transform, event.localX, event.localY)
                    }
                } else null
                state.drag = handle
                state.transformDrag = moving
                state.readout = null
                if (handle != null || moving != null) state.beginGesture() else preview.beginCameraDrag()
            }.onDrag { event ->
                val handle = state.drag
                val moving = state.transformDrag
                val current = selected?.let { document.effect.node(it) }
                when {
                    moving != null && current != null -> {
                        transform.drag(moving, event.localX, event.localY, event.modifiers)?.let { placed ->
                            document.edit { it.withNode(current.withCommon(transform = placed)) }
                        }
                        state.readout = transform.labelAt(moving)?.let { (x, y) -> VfxGizmoReadout(x, y, moving.label) }
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
                if (state.drag != null || state.transformDrag != null) state.endGesture()
                state.drag = null
                state.transformDrag = null
                state.readout = null
            }.onHover { event ->
                state.pointerX = event.localX
                state.pointerY = event.localY
                if (state.keyboard != null) {
                    moveKeyboardTransform(document, state, event.modifiers)
                    return@onHover
                }
                val over = transform.pick(handles, event.localX, event.localY)?.id
                if (over != state.hoveredHandle) state.hoveredHandle = over
            }.onScroll { event ->
                preview.zoom(event.scrollY)
                event.consume()
            }.drawBehind(key = preview) {
                preview.draw(this)
            },
    ) {
        Box(
            modifier = Modifier.size(100.percent, 100.percent).inputTransparent()
                .drawBehind(key = listOf(gizmo, handles, state.drag?.id, state.hoveredHandle, state.keyboard)) {
                    drawGizmo(gizmo, state.drag?.id)
                    val keyboard = state.keyboard
                    if (keyboard != null) keyboard.keyboard.draw(this)
                    else transform.draw(this, handles, state.hoveredHandle, state.transformDrag)
                },
        )
        state.keyboard?.let { keyboard ->
            Text(
                keyboard.keyboard.hint,
                tags = listOf("vfx-gizmo-readout"),
                modifier = Modifier.position(8.px, 32.px).inputTransparent(),
            )
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

private fun handleHistoryKeys(document: HollowIdeVfxDocument, input: UiKeyInput): Boolean =
    !input.repeat && UndoKeys.handle(document.history, input.key, input.modifiers)

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

/** The modes of the transform gizmo, with the icons and names the IDE toolbar gives them for the world. */
private val GizmoModes = listOf(
    Triple(GizmoEditMode.TRANSLATE, "hollowengine:textures/gui/icons/gizmo_translate.svg", "hollowengine.gui.ide.gizmo.translate"),
    Triple(GizmoEditMode.ROTATE, "hollowengine:textures/gui/icons/gizmo_rotate.svg", "hollowengine.gui.ide.gizmo.rotate"),
    Triple(GizmoEditMode.SCALE, "hollowengine:textures/gui/icons/gizmo_scale.svg", "hollowengine.gui.ide.gizmo.scale"),
)

private val HandleOutline = UiColor(0.05f, 0.05f, 0.06f, 0.9f)
private val CounterBackground = UiColor(0.06f, 0.07f, 0.08f, 0.7f)
