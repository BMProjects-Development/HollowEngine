package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.editor.GizmoDrag
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandleId
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.models.internal.rig.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugSkeletonRenderer
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.PublishScene
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorIconButton
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.ModelViewerState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L

private const val PhysicsIcon = "hollowengine:textures/gui/icons/timeline/play.svg"
private const val StopIcon = "hollowengine:textures/gui/icons/timeline/pause.svg"
private const val SkeletonIcon = "hollowengine:textures/gui/icons/rig/skeleton.svg"
private const val ColliderIcon = "hollowengine:textures/gui/icons/rig/colliders.svg"

/** The modes of the collider gizmo, with the icons and names the IDE toolbar gives them for the world. */
private val GizmoModes = listOf(
    Triple(GizmoEditMode.TRANSLATE, "hollowengine:textures/gui/icons/gizmo_translate.svg", "hollowengine.gui.ide.gizmo.translate"),
    Triple(GizmoEditMode.ROTATE, "hollowengine:textures/gui/icons/gizmo_rotate.svg", "hollowengine.gui.ide.gizmo.rotate"),
    Triple(GizmoEditMode.SCALE, "hollowengine:textures/gui/icons/gizmo_scale.svg", "hollowengine.gui.ide.gizmo.scale"),
)

/**
 * The editor for a `.rig` file.
 *
 * The bones go to the scene window and what hangs on the selected one to the inspector; the editor
 * itself is the preview, like the effect editor's.
 */
@Composable
internal fun RigEditorPanel(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeRigDocument
    val state = remember(document) { document.editorState { RigEditorState(document, file.path.toModelId()) } }
    val viewer = state.viewer
    val model by viewer.modelFlow.collectAsState()
    var preview by remember(document) { mutableStateOf<RigPreview?>(null) }

    LaunchedEffect(document.revision) {
        viewer.attachment.rig = document.rig
        file.updateDirty(document.isModified)
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified && file.save()) publishRig(state.viewer.model, document.rig)
    }

    LaunchedEffect(preview, state.colliderSelection) {
        while (preview != null || state.colliderSelection != null) withFrameNanos { state.frame++ }
    }

    DisposableEffect(document) {
        onDispose {
            preview?.let(RigPreview::close)
            preview = null
        }
    }

    viewer.debugDraw = { lines ->
        if (state.showSkeleton) DebugSkeletonRenderer.draw(viewer.attachment, lines, state.selected)
        if (state.showColliders) {
            lines.colliders(previewColliders(document.rig, viewer.nodes), state.colliderSelection)
            RigOverlays.all.forEach { it.draw(viewer.attachment, lines, state.selected) }
            preview?.draw(lines)
        }
    }

    val bones = remember(model) { viewer.nodes.flatMap { node -> node.walk().map(RuntimeNode::name) } }

    PublishInspector(source = "rig-${file.path}", key = Triple(state.selected, state.selectedCollider, bones)) {
        rigInspectorTarget(document, state, bones)
    }

    PublishScene(
        source = "rig-${file.path}",
        key = listOf(
            document.revision, model, state.selected, state.expanded.toList(), state.rootExpanded,
            viewer.nodeVisibilityRevision,
        ),
    ) {
        rigSceneTarget(document, state, file.path.substringAfterLast('/').substringBeforeLast('.'))
    }

    Column(
        tags = listOf("ide-file-panel"),
        modifier = Modifier.style(AnimatorStylesheet).focusScope(),
    ) {
        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            RigViewport(document, state, preview)
            Toolbar(document, state, preview) { preview = preview.toggled(viewer) }
        }
    }
}

@Composable
private fun Toolbar(document: HollowIdeRigDocument, state: RigEditorState, preview: RigPreview?, onPhysics: () -> Unit) {
    val viewer = state.viewer
    Row(tags = listOf("viewport-toolbar"), modifier = Modifier.position(8.px, 8.px)) {
        AnimatorIconButton(SkeletonIcon, rigText("toggle_skeleton"), size = 12f, active = state.showSkeleton) {
            state.showSkeleton = !state.showSkeleton
        }
        AnimatorIconButton(ColliderIcon, rigText("toggle_colliders"), size = 12f, active = state.showColliders) {
            state.showColliders = !state.showColliders
        }
        if (state.colliderSelection != null) {
            GizmoModes.forEach { (mode, icon, tooltip) ->
                AnimatorIconButton(icon, tooltip.lang, size = 12f, active = state.gizmoMode == mode) { state.gizmoMode = mode }
            }
        }
        if (RigPreviews.isAvailable) {
            AnimatorIconButton(
                icon = if (preview == null) PhysicsIcon else StopIcon,
                tooltip = rigText(if (preview == null) "start_physics" else "stop_physics"),
                size = 12f,
                active = preview != null,
                onClick = onPhysics,
            )
        }
        GenerateButton(document, viewer)
    }
}

/** One button for every way to fill in the rig, each named with its own icon in the menu it opens. */
@Composable
private fun GenerateButton(document: HollowIdeRigDocument, viewer: ModelViewerState) {
    val generators = RigGenerators.all
    if (generators.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    AnimatorIconButton(
        RigGenerators.DEFAULT_ICON,
        rigText("generate"),
        size = 12f,
        active = open,
        modifier = Modifier.onPlaced { anchor = it },
    ) { open = !open }
    if (!open) return
    ContextMenu(
        id = "rig-generate-menu",
        anchorBounds = anchor,
        items = generators.map { entry ->
            UiDropdownItem(entry.titleKey.lang, entry.icon) {
                viewer.attachment.ensureReady()
                document.edit(label = UndoLabel(entry.titleKey)) { entry.generator.generate(viewer.attachment, it) }
            }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

/**
 * What the editor is looking at, as opposed to what it is editing.
 */
internal class RigEditorState(private val document: HollowIdeRigDocument, modelId: String) {
    val viewer = ModelViewerState(modelId)

    /** The selected bone, or null for the model itself. */
    var selected by mutableStateOf<String?>(null)
        private set

    /** The selected collider on [selected], if any. */
    var selectedCollider by mutableStateOf<String?>(null)
        private set

    var showSkeleton by mutableStateOf(true)
    var showColliders by mutableStateOf(true)

    /** Which bones are open in the scene window, kept here so they survive switching files. */
    val expanded = mutableStateListOf<String>()
    var rootExpanded by mutableStateOf(true)

    val gizmo = RigColliderGizmo()
    var gizmoMode by mutableStateOf(GizmoEditMode.TRANSLATE)
    var hoveredHandle by mutableStateOf<GizmoHandleId?>(null)
    var transform by mutableStateOf<ColliderTransform?>(null)
    internal var handleDrag: RigHandleDrag? = null

    /** Ticks every frame while something in the preview moves on its own, to redraw the gizmo over it. */
    var frame by mutableStateOf(0L)

    /** Where the pointer last was over the preview: where a transform from the keyboard starts. */
    var pointerX = 0f
        private set
    var pointerY = 0f
        private set

    /** Set by a press the gizmo took, so the click that follows does not change the selection. */
    internal var swallowClick = false

    val colliderSelection: ColliderSelection? get() = selectedCollider?.let { ColliderSelection(selected, it) }

    /** Selects [bone] and [collider] on it; a transform or a drag under way stays where it got to. */
    fun select(bone: String?, collider: String? = null) {
        if (transform != null || handleDrag != null) document.endGesture()
        transform = null
        handleDrag = null
        selected = bone
        selectedCollider = collider
    }

    fun pointer(x: Float, y: Float) {
        pointerX = x
        pointerY = y
    }

    fun endHandleDrag() {
        handleDrag = null
    }

    /** The selected collider where it is now in the preview, or null when none is selected or it is gone. */
    fun selectedFrame(): ColliderFrame? {
        val selection = colliderSelection ?: return null
        val spec = document.rig.holder(selection.bone).attachment(selection.id) as? ColliderAttachmentSpec ?: return null
        val holder = holderMatrix(viewer.nodes, selection.bone) ?: return null
        return ColliderFrame(spec, holder)
    }
}

/** A drag on a gizmo handle: the collider it moves, where it was when the drag began, and the drag. */
internal class RigHandleDrag(val selection: ColliderSelection, val frame: ColliderFrame, val drag: GizmoDrag)

private fun RigPreview?.toggled(viewer: ModelViewerState): RigPreview? {
    if (this != null) {
        viewer.instance.animator.remove(RigPreviewLayer.ID)
        close()
        return null
    }

    val started = RigPreviews.create() ?: return null
    viewer.instance.animator.add(RigPreviewLayer(started))
    return started
}

internal fun rigText(name: String): String = "hollowengine.gui.rig_editor.$name".lang

internal fun String.toModelId(): String = substringAfter("assets/").replaceFirst("/", ":").removeSuffix(".rig")
