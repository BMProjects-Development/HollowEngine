package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.common.models.*
import kotlin.math.*

internal const val AnimatorCanvasId = "animator-canvas"
private const val NodeHeight = 46f
private const val NodeMinWidth = 96f
private const val NodeMaxWidth = 260f
private const val GridStep = 32f
private const val ArrowSize = 9f
private const val CurveBow = 26f
private const val ParallelOffset = 9f
private const val EdgeSamples = 32
private const val EdgeHitRadius = 6f
private const val MiniMapWidth = 120f
private const val MiniMapHeight = 78f
private const val MinZoom = 0.45f
private const val MaxZoom = 2.0f
private const val PlayIcon = "hollowengine:textures/gui/icons/play.svg"
private const val StateIcon = "hollowengine:textures/gui/icons/state.svg"
private const val AnyStateIcon = "hollowengine:textures/gui/icons/any_state.svg"
private const val ResetIcon = "hollowengine:textures/gui/icons/reload.svg"
internal const val MaximizeIcon = "hollowengine:textures/gui/icons/maximize.svg"
internal const val MinimizeIcon = "hollowengine:textures/gui/icons/minimize.svg"

/**
 * Pan and zoom of one graph.
 */
class AnimatorCanvasState {
    var panX by mutableStateOf(60f)
    var panY by mutableStateOf(60f)
    var zoom by mutableStateOf(1f)
        private set

    private var targetZoom = 1f
    private var velocity = 0f
    private var anchorScreenX = 0f
    private var anchorScreenY = 0f
    private var anchorGraphX = 0f
    private var anchorGraphY = 0f
    private var lastFrame = 0L

    fun toScreenX(x: Float) = x * zoom + panX
    fun toScreenY(y: Float) = y * zoom + panY
    fun toGraphX(x: Float) = (x - panX) / zoom
    fun toGraphY(y: Float) = (y - panY) / zoom

    fun zoomBy(step: Float, aroundX: Float, aroundY: Float) {
        anchorScreenX = aroundX
        anchorScreenY = aroundY
        anchorGraphX = toGraphX(aroundX)
        anchorGraphY = toGraphY(aroundY)
        targetZoom = (targetZoom * step).coerceIn(MinZoom, MaxZoom)
    }

    fun reset() {
        targetZoom = 1f
        velocity = 0f
        panX = 60f
        panY = 60f
        zoom = 1f
    }

    /** One step of the spring; returns whether anything is still moving. */
    fun advance(frameNanos: Long): Boolean {
        val previous = lastFrame
        lastFrame = frameNanos
        if (previous == 0L) return false

        val delta = targetZoom - zoom
        if (abs(delta) < 0.0005f && abs(velocity) < 0.0005f) {
            if (zoom != targetZoom) {
                zoom = targetZoom
                reanchor()
            }
            velocity = 0f
            return false
        }

        val dt = ((frameNanos - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
        velocity += (delta * STIFFNESS - velocity * DAMPING) * dt
        zoom += velocity * dt
        reanchor()
        return true
    }

    private fun reanchor() {
        if (anchorScreenX == 0f && anchorScreenY == 0f) return
        panX = anchorScreenX - anchorGraphX * zoom
        panY = anchorScreenY - anchorGraphY * zoom
    }

    private companion object {
        const val STIFFNESS = 260f
        const val DAMPING = 30f
    }
}

/** A state as it sits on screen right now. */
private data class NodeBox(val id: String, val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX get() = x + width / 2f
    val centerY get() = y + height / 2f
}

/** A transition as a sampled curve, which is what gets drawn, clicked and labeled. */
private data class EdgeCurve(val index: Int, val points: FloatArray) {
    /**
     * A point along the curve.
     */
    fun pointAt(fraction: Float): FloatArray {
        val samples = points.size / 2 - 1
        val step = (fraction.coerceIn(0f, 1f) * samples).toInt().coerceIn(0, samples)
        return floatArrayOf(points[step * 2], points[step * 2 + 1])
    }

    val labelPoint: FloatArray get() = pointAt(0.42f + (index % 3) * 0.11f)

    override fun equals(other: Any?) = other is EdgeCurve && index == other.index && points.contentEquals(other.points)
    override fun hashCode() = 31 * index + points.contentHashCode()
}

/**
 * State machine of controller layer, as a graph.
 */
@Composable
internal fun AnimatorGraphCanvas(
    document: HollowIdeAnimatorDocument,
    layerId: String,
    selection: AnimatorSelection,
    view: AnimatorCanvasState,
    chrome: CanvasChrome,
    onSelect: (AnimatorSelection) -> Unit,
    modifier: Modifier,
) {
    val animator = document.animator
    val controller = animator.controller(layerId)
    var canvasSize by remember { mutableStateOf(UiRect.Zero) }

    if (controller == null) {
        Box(
            mode = UiBoxMode.STACK,
            modifier = modifier.background(AnimatorColors.Canvas).onPlaced { canvasSize = it }) {
            Text(
                animatorText("pick_controller"),
                modifier = Modifier.padding(12.px).fontSize(11f).foreground(AnimatorColors.Muted),
            )
            CanvasChromeButtons(chrome, null)
        }
        return
    }

    LaunchedEffect(view) {
        while (true) {
            withFrameNanos(view::advance)
        }
    }

    val positions = animator.nodeLayout(layerId) + anyStatePosition(animator, layerId, controller)
    var measured by remember(layerId) { mutableStateOf(emptyMap<String, UiRect>()) }
    val boxes = positions.map { (stateId, point) ->
        val state = controller.states.firstOrNull { it.id == stateId }
        val size = measured[stateId]
        NodeBox(
            id = stateId,
            x = view.toScreenX(point.x),
            y = view.toScreenY(point.y),
            width = size?.width ?: (nodeWidth(stateId, state.subtitle()) * view.zoom),
            height = size?.height ?: (NodeHeight * view.zoom),
        )
    }
    val boxById = boxes.associateBy { it.id }
    val edges = controller.edgeCurves(boxById)

    var dragging by remember(layerId) { mutableStateOf<String?>(null) }
    var dragOrigin by remember(layerId) { mutableStateOf(GraphPoint()) }
    var linkingFrom by remember(layerId) { mutableStateOf<String?>(null) }
    var linkX by remember(layerId) { mutableStateOf(0f) }
    var linkY by remember(layerId) { mutableStateOf(0f) }
    var menu by remember(layerId) { mutableStateOf<CanvasMenu?>(null) }
    var hoveredEdge by remember(layerId) { mutableStateOf<Int?>(null) }

    Box(
        id = AnimatorCanvasId,
        mode = UiBoxMode.STACK,
        modifier = modifier
            .background(AnimatorColors.Canvas)
            .clip(true)
            .onPlaced { canvasSize = it }
            .input(hoverable = true, clickable = true, draggable = true)
            .drawBehind(
                GraphKey(
                    view.zoom,
                    view.panX,
                    view.panY,
                    document.revision,
                    selection,
                    linkingFrom,
                    linkX,
                    linkY,
                    hoveredEdge
                )
            ) {
                drawGrid(view)
                edges.sortedBy { selection == AnimatorSelection.Transition(layerId, it.index) }.forEach { edge ->
                    val color = when {
                        selection == AnimatorSelection.Transition(layerId, edge.index) -> AnimatorColors.EdgeSelected
                        edge.index == hoveredEdge -> AnimatorColors.EdgeHover
                        else -> AnimatorColors.Edge
                    }
                    drawCurve(edge.points, view.zoom, color)
                }
                linkingFrom?.let { from ->
                    val box = boxById[from] ?: return@let
                    drawLine(box.centerX, box.centerY, linkX, linkY, AnimatorColors.Accent)
                }
            }
            .onHover { event ->
                val edge = edges.nearest(event.localX, event.localY)
                if (edge?.index != hoveredEdge) hoveredEdge = edge?.index
            }
            .onExit { hoveredEdge = null }
            .focus()
            .onKeyInput { input ->
                when {
                    input.key == GLFW.GLFW_KEY_DELETE -> {
                        when (selection) {
                            is AnimatorSelection.State ->
                                if (selection.stateId == ANY_STATE) {
                                    document.edit { it.withoutAnyState(layerId) }
                                } else {
                                    document.edit { it.withoutState(layerId, selection.stateId) }
                                }

                            is AnimatorSelection.Transition ->
                                document.edit { it.withoutTransitionAt(layerId, selection.index) }

                            else -> return@onKeyInput
                        }
                        onSelect(AnimatorSelection.Layer(layerId))
                        input.consume()
                    }

                    input.key == GLFW.GLFW_KEY_ESCAPE -> {
                        onSelect(AnimatorSelection.None)
                        input.consume()
                    }

                    input.key == GLFW.GLFW_KEY_F -> {
                        view.reset()
                        input.consume()
                    }

                    input.key == GLFW.GLFW_KEY_E -> {
                        val target = selection as? AnimatorSelection.State ?: return@onKeyInput
                        document.edit { it.withEntryState(layerId, target.stateId) }
                        input.consume()
                    }

                    input.key == GLFW.GLFW_KEY_INSERT || (input.control && input.key == GLFW.GLFW_KEY_N) -> {
                        val id = freeStateId(controller)
                        document.edit {
                            it.withState(
                                layerId,
                                ClipStateSpec(id = id, animation = id),
                                at = GraphPoint(
                                    view.toGraphX(canvasSize.width / 2f),
                                    view.toGraphY(canvasSize.height / 2f)
                                ),
                            )
                        }
                        onSelect(AnimatorSelection.State(layerId, id))
                        input.consume()
                    }
                }
            }
            .onScroll { event ->
                view.zoomBy(if (event.rawScrollY > 0f) 1.18f else 1f / 1.18f, event.localX, event.localY)
                event.consume()
            }
            .onDrag { event ->
                view.panX += event.deltaX
                view.panY += event.deltaY
                event.consume()
            }
            .onClick { event ->
                when {
                    event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                        menu = CanvasMenu(
                            target = boxes.firstOrNull { it.contains(event.localX, event.localY) }?.id
                                ?: edges.nearest(event.localX, event.localY)?.let { EdgeTarget(it.index) },
                            screenX = event.x,
                            screenY = event.y,
                            graphX = view.toGraphX(event.localX),
                            graphY = view.toGraphY(event.localY),
                        )
                    }

                    else -> {
                        val edge = edges.nearest(event.localX, event.localY)
                        onSelect(
                            if (edge != null) AnimatorSelection.Transition(layerId, edge.index)
                            else AnimatorSelection.None
                        )
                    }
                }
                event.consume()
            },
    ) {
        edges.sortedBy { selection == AnimatorSelection.Transition(layerId, it.index) }.forEach { edge ->
            val label = edge.labelPoint
            TransitionLabel(
                transition = controller.transitions[edge.index],
                selected = selection == AnimatorSelection.Transition(layerId, edge.index),
                zoom = view.zoom,
                x = label[0],
                y = label[1],
                onSelect = { onSelect(AnimatorSelection.Transition(layerId, edge.index)) },
            )
        }

        boxes.forEach { box ->
            val state = controller.states.firstOrNull { it.id == box.id }
            StateNode(
                box = box,
                subtitle = state.subtitle(),
                playMode = (state as? ClipStateSpec)?.playMode,
                isEntry = controller.entryState == box.id,
                selected = selection == AnimatorSelection.State(layerId, box.id),
                zoom = view.zoom,
                onSelect = { onSelect(AnimatorSelection.State(layerId, box.id)) },
                onPressed = { linking ->
                    if (linking) {
                        linkingFrom = box.id
                        linkX = box.centerX
                        linkY = box.centerY
                    } else {
                        dragging = box.id
                        dragOrigin = positions[box.id] ?: GraphPoint()
                    }
                },
                onDragged = { event ->
                    val from = linkingFrom
                    if (from != null) {
                        val local = event.ancestorLocalPositions[AnimatorCanvasId]
                        if (local != null) {
                            linkX = local.x
                            linkY = local.y
                        }
                        return@StateNode
                    }
                    if (dragging != box.id) return@StateNode
                    document.edit {
                        it.withNodeAt(
                            layerId,
                            box.id,
                            GraphPoint(
                                dragOrigin.x + event.dragTotalX / view.zoom,
                                dragOrigin.y + event.dragTotalY / view.zoom,
                            ),
                        )
                    }
                },
                onReleased = {
                    val from = linkingFrom
                    if (from != null) {
                        val target = boxes.firstOrNull { it.contains(linkX, linkY) }?.id
                        if (target != null && target != from && target != ANY_STATE) {
                            document.edit {
                                it.withTransition(layerId, AnimationControllerTransitionSpec(from = from, to = target))
                            }
                        }
                    }
                    linkingFrom = null
                    dragging = null
                },
                onContextMenu = { screenX, screenY ->
                    menu = CanvasMenu(box.id, screenX, screenY, 0f, 0f)
                },
                onMeasured = { rect ->
                    if (measured[box.id]?.width != rect.width || measured[box.id]?.height != rect.height) {
                        measured = measured + (box.id to rect)
                    }
                },
            )
        }

        Column(
            modifier = Modifier.size(100.percent, 100.percent).padding(8.px).inputTransparent()
                .alignItems(horizontal = UiAlign.END, vertical = UiAlign.END),
        ) {
            MiniMap(
                positions = positions,
                selected = (selection as? AnimatorSelection.State)?.stateId,
                modifier = Modifier,
            ) { point ->
                view.panX = canvasSize.width / 2f - point.x * view.zoom
                view.panY = canvasSize.height / 2f - point.y * view.zoom
            }
        }
        CanvasChromeButtons(chrome, view)
    }

    CanvasContextMenu(
        menu = menu,
        controller = controller,
        layerId = layerId,
        document = document,
        view = view,
        onSelect = onSelect,
        onDismiss = { menu = null },
    )
}

@Composable
private fun StateNode(
    box: NodeBox,
    subtitle: String?,
    playMode: AnimationPlayMode?,
    isEntry: Boolean,
    selected: Boolean,
    zoom: Float,
    onSelect: () -> Unit,
    onPressed: (linking: Boolean) -> Unit,
    onDragged: (UiEvent) -> Unit,
    onReleased: () -> Unit,
    onMeasured: (UiRect) -> Unit,
    onContextMenu: (Float, Float) -> Unit,
) {
    val anyState = box.id == ANY_STATE
    val accent = when {
        selected -> AnimatorColors.NodeSelected
        anyState -> AnimatorColors.AnyState
        isEntry -> AnimatorColors.NodeEntry
        else -> AnimatorColors.Border
    }

    Column(
        tags = listOf("animator-node"),
        modifier = Modifier
            .position(box.x.px, box.y.px)
            .size((nodeWidth(box.id, subtitle) * zoom).px)
            .background(
                145f,
                listOf(
                    UiGradientStop(0f, AnimatorColors.NodeTop.mixedWith(accent)),
                    UiGradientStop(1f, AnimatorColors.NodeBottom),
                ),
            )
            .border(if (selected) 2.px else 1.px, accent, 5f)
            .shadow(UiShadow(offset = UiVec3(0f, 2f), blur = 9f, color = AnimatorColors.NodeShadow))
            .padding((5f * zoom).px)
            .gap((2f * zoom).px)
            .input(hoverable = true, clickable = true, draggable = true)
            .onPlaced(onMeasured)
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    onSelect()
                    onContextMenu(event.x, event.y)
                    event.consume()
                    return@onPress
                }
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                onSelect()
                onPressed(event.modifiers and GLFW.GLFW_MOD_CONTROL != 0)
                event.consume()
            }
            .onDrag { event ->
                onDragged(event)
                event.consume()
            }
            .onRelease { event ->
                onReleased()
                event.consume()
            },
    ) {
        Row(modifier = Modifier.size(100.percent).gap((4f * zoom).px).alignItems(vertical = UiAlign.CENTER)) {
            Image(
                if (anyState) AnyStateIcon else StateIcon,
                modifier = Modifier.size((9f * zoom).px, (9f * zoom).px),
            )
            NodeLine(
                if (anyState) "Any State" else box.id,
                10f * zoom,
                if (anyState) AnimatorColors.AnyState else AnimatorColors.Text,
            )
        }
        if (!anyState) {
            NodeLine(subtitle.orEmpty(), 8f * zoom, AnimatorColors.Muted)
            Row(modifier = Modifier.size(100.percent).gap((3f * zoom).px).alignItems(vertical = UiAlign.CENTER)) {
                playMode?.let { PlayModeChip(it, zoom) }
                Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
                if (isEntry) EntryBadge(zoom)
            }
        }
    }
}

/** The playback mode, as a chip: on its own line it reads as the tail of a wrapped animation name. */
@Composable
private fun PlayModeChip(playMode: AnimationPlayMode, zoom: Float) {
    Text(
        playMode.name.lowercase(),
        modifier = Modifier
            .background(AnimatorColors.Chip)
            .border(1.px, AnimatorColors.Border, 3f)
            .borderRadius(3f)
            .padding((4f * zoom).px, (1f * zoom).px)
            .fontSize(7f * zoom)
            .foreground(AnimatorColors.ChipText)
            .textWrap(false),
    )
}

/** Marks the state a controller starts in, in the corner where it cannot be hidden by the selection border. */
@Composable
private fun EntryBadge(zoom: Float) {
    Image(
        PlayIcon,
        modifier = Modifier
            .size((13f * zoom).px, (13f * zoom).px)
            .background(AnimatorColors.Panel)
            .border(1.px, AnimatorColors.NodeEntry, 3f)
            .borderRadius(3f)
            .padding((2f * zoom).px),
    )
}

@Composable
private fun NodeLine(text: String, fontSize: Float, color: UiColor) {
    Text(
        text,
        modifier = Modifier
            .size(100.percent)
            .fontSize(fontSize)
            .foreground(color)
            .textWrap(false)
            .textOverflow(UiTextOverflow.DOTS),
    )
}

@Composable
private fun TransitionLabel(
    transition: AnimationControllerTransitionSpec,
    selected: Boolean,
    zoom: Float,
    x: Float,
    y: Float,
    onSelect: () -> Unit,
) {
    val label = transition.condition.source.ifBlank { animatorText("always") }
    Text(
        if (label.length > 20) label.take(19) + "…" else label,
        tags = listOf("animator-edge-label") + if (selected) listOf("selected") else emptyList(),
        modifier = Modifier
            .position((x - 24f * zoom).px, (y - 7f * zoom).px)
            .fontSize(8f * zoom)
            .foreground(if (selected) AnimatorColors.EdgeSelected else AnimatorColors.Muted)
            .input(hoverable = true, clickable = true)
            .onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onSelect()
                event.consume()
            },
    )
}

/**
 * Handles the graph shows for the surrounding panels.
 */
internal data class CanvasChrome(
    val layersFolded: Boolean,
    val foldLayers: @Composable () -> Unit,
)

/**
 * Handles that fold the panels, and one that puts the view back.
 */
@Composable
private fun CanvasChromeButtons(chrome: CanvasChrome, view: AnimatorCanvasState?) {
    Row(modifier = Modifier.position(6.px, 6.px).gap(4.px)) {
        chrome.foldLayers()
        if (view != null) {
            AnimatorIconButton(ResetIcon, animatorText("reset_view"), size = 11f) { view.reset() }
        }
    }
}

@Composable
private fun MiniMap(
    positions: Map<String, GraphPoint>,
    selected: String?,
    modifier: Modifier,
    onJumpTo: (GraphPoint) -> Unit,
) {
    if (positions.isEmpty()) return

    val minX = positions.values.minOf { it.x }
    val maxX = positions.values.maxOf { it.x } + NodeMinWidth
    val minY = positions.values.minOf { it.y }
    val maxY = positions.values.maxOf { it.y } + NodeHeight
    val scale = minOf(
        (MiniMapWidth - 8f) / (maxX - minX).coerceAtLeast(1f),
        (MiniMapHeight - 8f) / (maxY - minY).coerceAtLeast(1f),
    )

    Box(
        mode = UiBoxMode.STACK,
        modifier = modifier
            .size(MiniMapWidth.px, MiniMapHeight.px)
            .background(AnimatorColors.Panel)
            .border(1.px, AnimatorColors.Border, 4f)
            .borderRadius(4f),
    ) {
        positions.forEach { (stateId, point) ->
            MiniMapMark(
                stateId = stateId,
                selected = stateId == selected,
                x = 4f + (point.x - minX) * scale,
                y = 4f + (point.y - minY) * scale,
                width = (NodeMinWidth * scale).coerceAtLeast(4f),
                height = (NodeHeight * scale).coerceAtLeast(3f),
                onClick = { onJumpTo(point) },
            )
        }
    }
}

@Composable
private fun MiniMapMark(
    stateId: String,
    selected: Boolean,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    onClick: () -> Unit,
) {
    Box(
        tags = listOf("animator-minimap-mark"),
        modifier = Modifier
            .position(x.px, y.px)
            .size(width.px, height.px)
            .background(
                when {
                    selected -> AnimatorColors.NodeSelected
                    stateId == ANY_STATE -> AnimatorColors.AnyState
                    else -> AnimatorColors.Edge
                }
            )
            .input(hoverable = true, clickable = true)
            .tooltipOnHover(if (stateId == ANY_STATE) animatorText("any_state_node") else stateId)
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    )
}

private data class EdgeTarget(val index: Int)

private data class CanvasMenu(
    val target: Any?,
    val screenX: Float,
    val screenY: Float,
    val graphX: Float,
    val graphY: Float,
)

@Composable
private fun CanvasContextMenu(
    menu: CanvasMenu?,
    controller: AnimationControllerLayerSpec,
    layerId: String,
    document: HollowIdeAnimatorDocument,
    view: AnimatorCanvasState,
    onSelect: (AnimatorSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    if (menu == null) return

    ContextMenu(
        id = "animator-canvas-context",
        anchorBounds = UiRect(menu.screenX, menu.screenY, 0f, 0f),
        items = buildList {
            when (val target = menu.target) {
                ANY_STATE -> add(UiDropdownItem(animatorText("delete_any_state")) {
                    document.edit { it.withoutAnyState(layerId) }
                    onSelect(AnimatorSelection.Layer(layerId))
                })

                is String -> {
                    add(UiDropdownItem(animatorText("make_entry")) {
                        document.edit { it.withEntryState(layerId, target) }
                    })
                    add(UiDropdownItem(animatorText("make_any_state")) {
                        document.edit { it.withStateAsAnyState(layerId, target) }
                        onSelect(AnimatorSelection.State(layerId, ANY_STATE))
                    })
                    add(UiDropdownItem(animatorText("delete_state")) {
                        document.edit { it.withoutState(layerId, target) }
                        onSelect(AnimatorSelection.Layer(layerId))
                    })
                }

                is EdgeTarget -> add(UiDropdownItem(animatorText("delete_transition")) {
                    document.edit { it.withoutTransitionAt(layerId, target.index) }
                    onSelect(AnimatorSelection.Layer(layerId))
                })

                else -> {
                    val kinds = AnimatorStateTypes.all.filter { it.createDefault != null }
                    kinds.forEach { type ->
                        val create = type.createDefault ?: return@forEach
                        val label = if (kinds.size == 1) animatorText("add_state")
                        else "${animatorText("add_state")}: ${type.title()}"

                        add(UiDropdownItem(label) {
                            val id = freeStateId(controller)
                            document.edit {
                                it.withState(layerId, create(id), at = GraphPoint(menu.graphX, menu.graphY))
                            }
                            onSelect(AnimatorSelection.State(layerId, id))
                        })
                    }
                    if (ANY_STATE !in document.animator.nodeLayout(layerId).keys &&
                        controller.transitions.none { it.from == ANY_STATE }
                    ) {
                        add(UiDropdownItem(animatorText("add_any_state")) {
                            document.edit { it.withAnyStateAt(layerId, GraphPoint(menu.graphX, menu.graphY)) }
                            onSelect(AnimatorSelection.State(layerId, ANY_STATE))
                        })
                    }
                    add(UiDropdownItem(animatorText("reset_view")) { view.reset() })
                }
            }
        },
        onExpandedChange = { if (!it) onDismiss() },
    )
}

private fun UiCanvasDrawScope.drawGrid(view: AnimatorCanvasState) {
    val paint = UiPaint.Color(AnimatorColors.Grid)
    val step = GridStep * view.zoom
    if (step < 4f) return

    var x = view.panX.mod(step)
    while (x < size.width) {
        drawRect(UiRect(x, 0f, 1f, size.height), paint)
        x += step
    }
    var y = view.panY.mod(step)
    while (y < size.height) {
        drawRect(UiRect(0f, y, size.width, 1f), paint)
        y += step
    }
}

/**
 * Draws a sampled curve, arrowhead included, with every segment clipped to the canvas.
 */
private fun UiCanvasDrawScope.drawCurve(points: FloatArray, zoom: Float, color: UiColor) {
    val width = (1.5f * zoom).coerceIn(1f, 3f)
    for (index in 0 until points.size / 2 - 1) {
        drawLine(points[index * 2], points[index * 2 + 1], points[index * 2 + 2], points[index * 2 + 3], color, width)
    }

    val head = ArrowSize * zoom
    val last = points.size - 2
    val angle = atan2(points[last + 1] - points[last - 1], points[last] - points[last - 2])
    val tipX = points[last]
    val tipY = points[last + 1]
    drawLine(tipX, tipY, tipX - head * cos(angle - ARROW_SPREAD), tipY - head * sin(angle - ARROW_SPREAD), color, width)
    drawLine(tipX, tipY, tipX - head * cos(angle + ARROW_SPREAD), tipY - head * sin(angle + ARROW_SPREAD), color, width)
}

private fun UiCanvasDrawScope.drawLine(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    color: UiColor,
    width: Float = 1.5f,
) {
    val clipped = clipToBounds(x1, y1, x2, y2, size.width, size.height) ?: return
    val shape = GenericShape {
        moveTo(clipped[0], clipped[1])
        lineTo(clipped[2], clipped[3])
    }
    drawShape(shape, bounds, UiPaint.Color(color), UiDrawStyle.Stroke(width))
}

private fun clipToBounds(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    width: Float,
    height: Float,
): FloatArray? {
    val dx = x2 - x1
    val dy = y2 - y1
    var enter = 0f
    var exit = 1f

    val checks = arrayOf(-dx to (x1 - 0f), dx to (width - x1), -dy to (y1 - 0f), dy to (height - y1))
    checks.forEach { (p, q) ->
        if (p == 0f) {
            if (q < 0f) return null
        } else {
            val r = q / p
            if (p < 0f) {
                if (r > exit) return null
                if (r > enter) enter = r
            } else {
                if (r < enter) return null
                if (r < exit) exit = r
            }
        }
    }

    return floatArrayOf(x1 + enter * dx, y1 + enter * dy, x1 + exit * dx, y1 + exit * dy)
}

private fun AnimationControllerLayerSpec.edgeCurves(boxes: Map<String, NodeBox>): List<EdgeCurve> =
    transitions.mapIndexedNotNull { index, transition ->
        val from = boxes[transition.from] ?: return@mapIndexedNotNull null
        val to = boxes[transition.to] ?: return@mapIndexedNotNull null
        if (from.id == to.id) return@mapIndexedNotNull null

        val twoWay = transitions.any { it.from == transition.to && it.to == transition.from }
        val side = if (twoWay && transition.from > transition.to) -1f else 1f
        EdgeCurve(index, curveBetween(from, to, side, twoWay))
    }

/**
 * A link the way node editors draw one: out of a side, in through a side, eased in and out.
 */
private fun curveBetween(from: NodeBox, to: NodeBox, side: Float, twoWay: Boolean): FloatArray {
    val horizontal = abs(to.centerX - from.centerX) >= abs(to.centerY - from.centerY)
    val offset = if (twoWay) ParallelOffset * side else 0f

    val start = from.sidePoint(towards = to, horizontal = horizontal, along = offset)
    val end = to.sidePoint(towards = from, horizontal = horizontal, along = offset, gap = ArrowSize * 0.5f)

    val reach = if (horizontal) {
        (abs(end[0] - start[0]) * 0.6f).coerceIn(CurveBow, 220f)
    } else {
        (abs(end[1] - start[1]) * 0.6f).coerceIn(CurveBow, 220f)
    }
    val startHandleX = start[0] + if (horizontal) reach * start[2] else 0f
    val startHandleY = start[1] + if (horizontal) 0f else reach * start[3]
    val endHandleX = end[0] + if (horizontal) reach * end[2] else 0f
    val endHandleY = end[1] + if (horizontal) 0f else reach * end[3]

    val points = FloatArray((EdgeSamples + 1) * 2)
    for (step in 0..EdgeSamples) {
        val t = step / EdgeSamples.toFloat()
        val inverse = 1f - t
        val a = inverse * inverse * inverse
        val b = 3f * inverse * inverse * t
        val c = 3f * inverse * t * t
        val d = t * t * t
        points[step * 2] = a * start[0] + b * startHandleX + c * endHandleX + d * end[0]
        points[step * 2 + 1] = a * start[1] + b * startHandleY + c * endHandleY + d * end[1]
    }
    return points
}

/**
 * Where a link attaches to this box, and which way it leaves.
 */
private fun NodeBox.sidePoint(
    towards: NodeBox,
    horizontal: Boolean,
    along: Float,
    gap: Float = 0f,
): FloatArray = if (horizontal) {
    val right = towards.centerX >= centerX
    floatArrayOf(
        if (right) x + width + gap else x - gap,
        (centerY + along).coerceIn(y + 4f, y + height - 4f),
        if (right) 1f else -1f,
        0f,
    )
} else {
    val below = towards.centerY >= centerY
    floatArrayOf(
        (centerX + along).coerceIn(x + 4f, x + width - 4f),
        if (below) y + height + gap else y - gap,
        0f,
        if (below) 1f else -1f,
    )
}

private fun List<EdgeCurve>.nearest(x: Float, y: Float): EdgeCurve? =
    mapNotNull { edge ->
        val distance = (0 until edge.points.size / 2 - 1).minOfOrNull { index ->
            distanceToSegment(
                x, y,
                edge.points[index * 2], edge.points[index * 2 + 1],
                edge.points[index * 2 + 2], edge.points[index * 2 + 3],
            )
        } ?: Float.MAX_VALUE
        if (distance <= EdgeHitRadius) edge to distance else null
    }.minByOrNull { it.second }?.first

private fun distanceToSegment(x: Float, y: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return hypot(x - x1, y - y1)
    val t = (((x - x1) * dx + (y - y1) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
}

private fun NodeBox.contains(x: Float, y: Float): Boolean =
    x >= this.x && x <= this.x + width && y >= this.y && y <= this.y + height

private fun AnimationControllerStateSpec?.subtitle(): String? = when (this) {
    null -> null
    is ClipStateSpec -> animation
    else -> kindName()
}

private fun nodeWidth(stateId: String, subtitle: String?): Float {
    val longest = maxOf(stateId.length, subtitle?.length ?: 0)
    return (18f + longest * 5.4f).coerceIn(NodeMinWidth, NodeMaxWidth)
}

private fun anyStatePosition(
    animator: Animator,
    layerId: String,
    controller: AnimationControllerLayerSpec,
): Map<String, GraphPoint> {
    val placed = animator.nodeAt(layerId, ANY_STATE)
    if (placed == null && controller.transitions.none { it.from == ANY_STATE }) return emptyMap()
    return mapOf(ANY_STATE to (placed ?: GraphPoint(-190f, 0f)))
}

internal fun freeStateId(controller: AnimationControllerLayerSpec): String {
    val taken = controller.states.map { it.id }.toSet()
    var index = taken.size + 1
    while ("state_$index" in taken) index++
    return "state_$index"
}

private data class GraphKey(
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val revision: Int,
    val selection: AnimatorSelection,
    val linkingFrom: String?,
    val linkX: Float,
    val linkY: Float,
    val hoveredEdge: Int?,
)

private const val ARROW_SPREAD = 0.5f
