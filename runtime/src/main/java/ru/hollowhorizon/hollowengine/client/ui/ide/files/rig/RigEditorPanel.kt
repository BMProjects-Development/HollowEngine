package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.models.internal.rig.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugSkeletonRenderer
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.entity.LocalEditorBones
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorColors
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorIconButton
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.widgets.Model
import ru.hollowhorizon.hollowengine.client.ui.widgets.ModelViewerState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeView
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L
private const val BoneListWidth = 210f
private const val InspectorWidth = 250f
private const val MinPanelWidth = 160f
private const val MaxPanelWidth = 420f

private const val PhysicsIcon = "hollowengine:textures/gui/icons/play.svg"
private const val StopIcon = "hollowengine:textures/gui/icons/pause.svg"
private const val SkeletonIcon = "hollowengine:textures/gui/icons/graph.svg"
private const val ColliderIcon = "hollowengine:textures/gui/icons/box.svg"
private const val GenerateIcon = "hollowengine:textures/gui/icons/reload.svg"
private const val VisibleIcon = "hollowengine:textures/gui/icons/visible.svg"
private const val InvisibleIcon = "hollowengine:textures/gui/icons/invisible.svg"

@Composable
internal fun RigEditorPanel(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeRigDocument
    val state = remember(document) { document.editorState { RigEditorState(file.path.toModelId()) } }
    val viewer = state.viewer
    var preview by remember(document) { mutableStateOf<RigPreview?>(null) }

    LaunchedEffect(document.revision) {
        viewer.attachment.rig = document.rig
        file.updateDirty(document.isModified)
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified) file.save()
    }

    LaunchedEffect(preview) {
        while (true) withFrameNanos { }
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
            RigOverlays.all.forEach { it.draw(viewer.attachment, lines, state.selected) }
            preview?.draw(lines)
        }
    }

    val bones = remember(viewer.nodes) { viewer.nodes.flatMap { node -> node.walk().map(RuntimeNode::name) } }

    Row(
        modifier = Modifier.size(100.percent, 100.percent).style(AnimatorStylesheet).background(AnimatorColors.Canvas)
            .focusScope(),
    ) {
        BoneList(
            viewer = viewer,
            rig = document.rig,
            selected = state.selected,
            onSelect = { state.selected = it },
            onToggleVisibility = viewer::toggleNodeVisibility,
            modifier = Modifier.size(state.boneListSize.px, 100.percent),
        )
        Splitter(state.boneListSize) { state.boneListSize = it.coerceIn(MinPanelWidth, MaxPanelWidth) }

        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(0.px, 100.percent).grow(1f)) {
            RigViewport(
                viewer = viewer,
                preview = preview,
                onPick = { candidates ->
                    val index = candidates.indexOfFirst { it.name == state.selected }
                    val next = (index + 1) % candidates.size.coerceAtLeast(1)
                    state.selected = candidates.getOrNull(next)?.name
                },
            )
            Column(
                modifier = Modifier.position(8.px, 8.px).gap(4.px),
            ) {
                AnimatorIconButton(SkeletonIcon, rigText("toggle_skeleton"), active = state.showSkeleton) {
                    state.showSkeleton = !state.showSkeleton
                }
                if (RigOverlays.all.isNotEmpty()) {
                    AnimatorIconButton(ColliderIcon, rigText("toggle_colliders"), active = state.showColliders) {
                        state.showColliders = !state.showColliders
                    }
                }
                if (RigPreviews.isAvailable) {
                    AnimatorIconButton(
                        icon = if (preview == null) PhysicsIcon else StopIcon,
                        tooltip = rigText(if (preview == null) "start_physics" else "stop_physics"),
                        active = preview != null,
                    ) {
                        preview = preview.toggled(viewer)
                    }
                }
                RigGenerators.all.forEach { entry ->
                    AnimatorIconButton(GenerateIcon, entry.titleKey.lang) {
                        viewer.attachment.ensureReady()
                        document.edit { entry.generator.generate(viewer.attachment, it) }
                    }
                }
            }
        }

        val bone = state.selected
        if (bone != null) {
            Splitter(state.inspectorSize, reversed = true) {
                state.inspectorSize = it.coerceIn(MinPanelWidth, MaxPanelWidth)
            }
            CompositionLocalProvider(LocalEditorBones provides bones) {
                RigInspector(
                    document = document,
                    bone = bone,
                    modifier = Modifier.size(state.inspectorSize.px, 100.percent),
                )
            }
        }
    }
}

/**
 * What the editor is looking at, as opposed to what it is editing.
 */
internal class RigEditorState(modelId: String) {
    val viewer = ModelViewerState(modelId)
    var selected by mutableStateOf<String?>(null)
    var boneListSize by mutableStateOf(BoneListWidth)
    var inspectorSize by mutableStateOf(InspectorWidth)
    var showSkeleton by mutableStateOf(true)
    var showColliders by mutableStateOf(true)
}

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

@Composable
private fun RigViewport(viewer: ModelViewerState, preview: RigPreview?, onPick: (List<RuntimeNode>) -> Unit) {
    Box(modifier = Modifier.size(100.percent, 100.percent).clip()) {
        Model(
            state = viewer,
            modifier = Modifier.size(100.percent, 100.percent).input(hoverable = true, clickable = true)
                .onClick { event ->
                    if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
                    onPick(viewer.bonesAt(event.localX, event.localY))
                    event.consume()
                },
            onDrag = { event ->
                val physics = preview
                if (physics == null || event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) false
                else {
                    physics.push(Vec3f(-event.deltaX, -event.deltaY, 0f) * PUSH_STRENGTH)
                    true
                }
            },
        )
    }
}

@Composable
private fun BoneList(
    viewer: ModelViewerState,
    rig: ModelRig,
    selected: String?,
    onSelect: (String) -> Unit,
    onToggleVisibility: (RuntimeNode) -> Unit,
    modifier: Modifier,
) {
    val expanded = remember(viewer) { mutableStateListOf<String>() }
    val nodes = viewer.nodes
    val items = remember(nodes, expanded.toList(), selected, rig, viewer.nodeVisibilityRevision) {
        buildList { appendBones(nodes, rig, expanded, selected) }
    }

    Column(
        modifier = modifier.background(AnimatorColors.Panel).border(1.px, AnimatorColors.Border).padding(8.px)
            .gap(6.px),
    ) {
        Text(rigText("bones"), modifier = Modifier.fontSize(11f).foreground(AnimatorColors.Muted))
        if (items.isEmpty()) {
            Text(rigText("no_bones"), modifier = Modifier.fontSize(9f).foreground(AnimatorColors.Muted))
            return@Column
        }

        UiTreeView(
            items = items,
            onToggle = { item -> if (item.id in expanded) expanded.remove(item.id) else expanded.add(item.id) },
            onSelect = { item, _ -> onSelect(item.payload.name) },
            onIconClick = { onToggleVisibility(it.payload) },
            fillRowWidth = true,
            modifier = Modifier.size(100.percent, 0.px).grow(1f).scrollable(horizontal = false),
        )
    }
}

private fun MutableList<UiTreeItem<RuntimeNode>>.appendBones(
    nodes: List<RuntimeNode>,
    rig: ModelRig,
    expanded: List<String>,
    selected: String?,
    depth: Int = 0,
) {
    nodes.forEach { node ->
        val bone = rig.bone(node.name)
        val marks = listOfNotNull(
            bone?.attachments?.size?.takeIf { it > 0 }?.let { "●$it" },
            bone?.alias?.takeIf { it.isNotBlank() }?.let { "→$it" },
        ).joinToString(" ")

        add(
            UiTreeItem(
                id = node.name,
                label = if (marks.isEmpty()) node.name else "${node.name}  $marks",
                depth = depth,
                payload = node,
                icon = if (node.isVisible) VisibleIcon else InvisibleIcon,
                hasChildren = node.children.isNotEmpty(),
                expanded = node.name in expanded,
                selected = node.name == selected,
            )
        )
        if (node.children.isNotEmpty() && node.name in expanded) {
            appendBones(node.children, rig, expanded, selected, depth + 1)
        }
    }
}

@Composable
private fun Splitter(width: Float, reversed: Boolean = false, onWidthChange: (Float) -> Unit) {
    val start = remember { floatArrayOf(width) }
    Box(
        modifier = Modifier.size(4.px, 100.percent).background(AnimatorColors.Border)
            .input(hoverable = true, draggable = true).cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { start[0] = width }.onDrag { event ->
                onWidthChange(start[0] + if (reversed) -event.dragTotalX else event.dragTotalX)
                event.consume()
            },
    )
}

private const val PUSH_STRENGTH = 0.05f

internal fun rigText(name: String): String = "hollowengine.gui.rig_editor.$name".lang

internal fun String.toModelId(): String = substringAfter("assets/").replaceFirst("/", ":").removeSuffix(".rig")
