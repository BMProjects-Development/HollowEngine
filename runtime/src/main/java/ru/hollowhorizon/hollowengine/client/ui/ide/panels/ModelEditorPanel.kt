package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.io.ByteArrayOutputStream

private const val GridIcon = "hollowengine:textures/gui/icons/graph.svg"
private const val WireframeIcon = "hollowengine:textures/gui/icons/layers.svg"
private const val BoundingBoxIcon = "hollowengine:textures/gui/icons/box.svg"
private const val AutoRotateIcon = "hollowengine:textures/gui/icons/reload.svg"
private const val PlayIcon = "hollowengine:textures/gui/icons/play.svg"
private const val PauseIcon = "hollowengine:textures/gui/icons/pause.svg"
private const val VisibleIcon = "hollowengine:textures/gui/icons/visible.svg"
private const val InvisibleIcon = "hollowengine:textures/gui/icons/invisible.svg"
private const val NodeIcon = "hollowengine:textures/gui/icons/file_model.svg"
private const val RigIcon = "hollowengine:textures/gui/icons/graph.svg"

private fun String.toModelId(): String = substringAfter("assets/").replaceFirst("/", ":")

/**
 * IDE dock panel for model files, the engine-UI successor of the Kool `ModelEditorFile`. Renders a
 * [Model] preview (raw-GL `drawGl`) with orbit controls, an animation control bar and a collapsible
 * inspection sidebar (info, animations, node tree). Styling lives in `ui/styles/model-editor.hss`.
 */
@Composable
internal fun ModelEditorPanel(path: String) {
    val viewer = remember(path) { ModelViewerState(path.toModelId()) }
    val frameTick = remember { mutableStateOf(0L) }
    LaunchedEffect(viewer) {
        while (true) {
            withFrameNanos { }
            if (viewer.isAnimating()) frameTick.value++
        }
    }

    var sidebarWidth by remember { mutableStateOf(260f) }

    val loaded by viewer.modelFlow.collectAsState()

    Row(
        tags = listOf("model-editor-root"),
        modifier = Modifier.style("hollowengine:ui/styles/model-editor.hss").size(100.percent, 100.percent),
    ) {
        Box(tags = listOf("model-viewer-pane")) {
            Model(viewer, modifier = Modifier.size(100.percent, 100.percent))
            Text(viewer.model, tags = listOf("model-title"))
            ModelToolbar(viewer, path)
            ModelAnimationBar(viewer, frameTick)
        }
        SidebarSplitter(sidebarWidth) { sidebarWidth = it }
        ModelSidebar(viewer, sidebarWidth, loaded)
    }
}

private const val SidebarMinWidth = 190f
private const val SidebarMaxWidth = 460f

@Composable
private fun SidebarSplitter(width: Float, onWidthChange: (Float) -> Unit) {
    val dragStart = remember { floatArrayOf(width) }
    Box(
        tags = listOf("model-splitter"),
        modifier = Modifier.size(5.px, 100.percent)
            .input(hoverable = true, draggable = true)
            .cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { dragStart[0] = width }
            .onDrag { event ->
                onWidthChange((dragStart[0] - event.dragTotalX).coerceIn(SidebarMinWidth, SidebarMaxWidth))
                event.consume()
            },
    )
}

@Composable
private fun ModelToolbar(viewer: ModelViewerState, path: String) {
    Column(tags = listOf("model-toolbar")) {
        ToggleChip(GridIcon, viewer.showGrid) { viewer.showGrid = !viewer.showGrid }
        ToggleChip(WireframeIcon, viewer.showWireframe) { viewer.showWireframe = !viewer.showWireframe }
        ToggleChip(BoundingBoxIcon, viewer.showBoundingBox) { viewer.showBoundingBox = !viewer.showBoundingBox }
        ToggleChip(AutoRotateIcon, viewer.autoRotate) { viewer.autoRotate = !viewer.autoRotate }
        ToggleChip(RigIcon, false) { HollowIdeOverlay.openOrCreate("$path.rig", ::emptyRig) }
    }
}

/** An empty rig, which is what a model that has never been rigged starts from. */
private fun emptyRig(): ByteArray {
    val tag = NBTFormat.serialize(ModelRig.serializer(), ModelRig.EMPTY)
    return ByteArrayOutputStream().also { tag.save(it) }.toByteArray()
}

@Composable
private fun ToggleChip(icon: String, active: Boolean, onToggle: () -> Unit) {
    Box(
        tags = if (active) listOf("model-chip", "selected") else listOf("model-chip"),
        modifier = Modifier.cursor(UiCursorShape.HAND).onClick { onToggle() },
    ) {
        Image(icon, tags = if (active) listOf("model-chip-icon", "selected") else listOf("model-chip-icon"))
    }
}

@Composable
private fun ModelAnimationBar(viewer: ModelViewerState, frameTick: State<Long>) {
    viewer.modelFlow.collectAsState().value
    var dropdownOpen by remember { mutableStateOf(false) }
    val animations = viewer.animations
    val current = viewer.currentAnimation

    Column(tags = listOf("model-anim-bar")) {
        Text("Анимация", tags = listOf("model-anim-title"))

        Row(tags = listOf("model-anim-controls")) {
            UiDropdown(
                id = "model-anim-dropdown",
                label = current?.name ?: if (animations.isEmpty()) "нет анимаций" else "—",
                expanded = dropdownOpen,
                onExpandedChange = { dropdownOpen = it },
                items = animations.mapIndexed { index, animation ->
                    UiDropdownItem(
                        label = animation.name,
                        onClick = { viewer.selectAnimation(index) },
                    )
                },
                tags = listOf("model-anim-dropdown"),
            )

            val playing = viewer.isPlaying(viewer.selectedAnimation)
            Box(
                tags = if (playing) listOf("model-play", "active") else listOf("model-play"),
                modifier = Modifier.cursor(UiCursorShape.HAND).onClick { viewer.togglePlayback() },
            ) {
                Image(if (playing) PauseIcon else PlayIcon, tags = listOf("model-play-icon"))
            }
        }

        frameTick.value
        if (viewer.isPlaying(viewer.selectedAnimation) && current != null && current.duration > 0f) {
            val progress = viewer.currentAnimationProgress
            Box(tags = listOf("model-progress-track")) {
                Box(
                    tags = listOf("model-progress-fill"),
                    modifier = Modifier.size((progress * 100f).percent, 100.percent),
                )
            }
        }
    }
}

@Composable
private fun ModelSidebar(viewer: ModelViewerState, width: Float, loaded: Any?) {
    val expanded = remember { mutableStateMapOf("info" to true, "animations" to true, "nodes" to true) }

    Column(
        tags = listOf("model-sidebar"),
        modifier = Modifier.size(width.px, 100.percent).scrollable(horizontal = false),
    ) {
        CollapsibleSection("Информация", expanded["info"] == true, { expanded["info"] = expanded["info"] != true }) {
            InfoRow("Полигоны", viewer.triangles.toString())
            InfoRow("Анимации", viewer.animations.size.toString())
            InfoRow("Shape keys", viewer.shapekeys.toString())
        }

        CollapsibleSection(
            "Анимации",
            expanded["animations"] == true,
            { expanded["animations"] = expanded["animations"] != true },
        ) {
            val animations = viewer.animations
            if (animations.isEmpty()) {
                Text("Список пуст", tags = listOf("model-empty"))
            }
            animations.forEachIndexed { index, animation ->
                key(animation.name) {
                    val selected = index == viewer.selectedAnimation
                    Row(
                        id = "model-anim-item-${animation.name}",
                        tags = if (selected) listOf("model-anim-item", "selected") else listOf("model-anim-item"),
                        modifier = Modifier.cursor(UiCursorShape.HAND).onClick { viewer.selectAnimation(index) },
                    ) {
                        Text(animation.name)
                    }
                }
            }
        }

        CollapsibleSection("Узлы", expanded["nodes"] == true, { expanded["nodes"] = expanded["nodes"] != true }) {
            ModelNodeTree(viewer, loaded)
        }
    }
}

@Composable
private fun ModelNodeTree(viewer: ModelViewerState, loaded: Any?) {
    val expandedIds = remember(viewer) { mutableStateListOf<String>() }
    val items = remember(loaded, expandedIds.toList(), viewer.nodeVisibilityRevision) {
        buildList<UiTreeItem<RuntimeNode>> { appendNodeItems(viewer.nodes, expandedIds) }
    }

    if (items.isEmpty()) {
        Text("Нет узлов", tags = listOf("model-empty"))
        return
    }

    fun toggleExpand(item: UiTreeItem<RuntimeNode>) {
        if (item.id in expandedIds) expandedIds.remove(item.id) else expandedIds.add(item.id)
    }

    UiTreeView(
        items = items,
        onToggle = { toggleExpand(it) },
        onSelect = { item, _ -> if (item.hasChildren) toggleExpand(item) },
        onIconClick = { viewer.toggleNodeVisibility(it.payload) },
        fillRowWidth = false,
        modifier = Modifier.size(100.percent, 220.px),
        tags = listOf("model-node-tree"),
    )
}

private fun MutableList<UiTreeItem<RuntimeNode>>.appendNodeItems(
    nodes: List<RuntimeNode>,
    expandedIds: List<String>,
    depth: Int = 0,
) {
    nodes.forEach { node ->
        val id = node.definition.index.toString()
        val hasChildren = node.children.isNotEmpty()
        val isExpanded = id in expandedIds
        add(
            UiTreeItem(
                id = id,
                label = node.name,
                depth = depth,
                payload = node,
                icon = if (node.isVisible) VisibleIcon else InvisibleIcon,
                hasChildren = hasChildren,
                expanded = isExpanded,
                selected = false,
            )
        )
        if (hasChildren && isExpanded) appendNodeItems(node.children, expandedIds, depth + 1)
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(tags = listOf("model-section")) {
        Row(
            tags = listOf("model-section-header"),
            modifier = Modifier.cursor(UiCursorShape.HAND).onClick { onToggle() },
        ) {
            Box(
                tags = listOf("model-section-arrow"),
                attributes = mapOf("expanded" to expanded.toString()),
            )
            Text(title, tags = listOf("model-section-title"))
        }
        if (expanded) {
            Column(tags = listOf("model-section-body")) { body() }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(tags = listOf("model-info-row")) {
        Text(label, tags = listOf("model-info-label"))
        Text(value, tags = listOf("model-info-value"))
    }
}
