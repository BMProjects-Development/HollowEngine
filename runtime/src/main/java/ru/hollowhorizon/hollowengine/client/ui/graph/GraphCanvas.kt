package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

const val GraphStylesheet = "hollowengine:ui/styles/graph.hss"

/** The colors the canvas draws itself; everything built out of nodes takes its look from [GraphStylesheet]. */
object GraphColors {
    val Grid = UiColor(1f, 1f, 1f, 0.04f)
    val Edge = UiColor(0.60f, 0.64f, 0.72f)
    val EdgeHover = UiColor(0.82f, 0.86f, 0.94f)
    val EdgeSelected = UiColor(0.37f, 0.62f, 0.98f)
    val Link = UiColor(0.37f, 0.62f, 0.98f)
}

/** Where the pointer is, in every space a handler of the graph may want it in. */
data class GraphPointer(
    val screenX: Float,
    val screenY: Float,
    val canvasX: Float,
    val canvasY: Float,
    val graphX: Float,
    val graphY: Float,
    val modifiers: Int = 0,
) {
    val control: Boolean get() = modifiers and GLFW.GLFW_MOD_CONTROL != 0
    val shift: Boolean get() = modifiers and GLFW.GLFW_MOD_SHIFT != 0
}

/** A link of the graph as the canvas draws it under the nodes. [key] is what a click on it reports. */
class GraphEdge(
    val key: Any,
    val curve: GraphCurve,
    val color: UiColor = GraphColors.Edge,
    val arrow: Boolean = false,
    val selected: Boolean = false,
)

/**
 * A link being dragged out and not yet dropped, in canvas space: a straight line, or [curve] when the
 * owner shapes it like the links it draws.
 */
data class GraphLinkPreview(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val color: UiColor = GraphColors.Link,
    val curve: GraphCurve? = null,
)

/**
 * The backdrop of a group of nodes, in graph space: drawn under the links, so they stay readable over
 * it. Its title bar is a node of its own ([GraphGroupHeader]), since only that bar takes the pointer.
 */
data class GraphFrame(val rect: GraphRect, val color: UiColor)

/** A node as the minimap marks it, in graph space. */
data class GraphMiniMapItem(
    val rect: GraphRect,
    val color: UiColor,
    val tooltip: String? = null,
)

/**
 * A pannable, zoomable canvas for a node graph: a grid, the links between nodes, and the nodes.
 */
@Composable
fun GraphCanvas(
    id: String,
    view: GraphViewState,
    modifier: Modifier = Modifier,
    edges: List<GraphEdge> = emptyList(),
    link: GraphLinkPreview? = null,
    frames: List<GraphFrame> = emptyList(),
    minimap: List<GraphMiniMapItem> = emptyList(),
    onBackgroundClick: (GraphPointer) -> Unit = {},
    onEdgeClick: (Any) -> Unit = {},
    onEdgeDoubleClick: ((Any, GraphPointer) -> Unit)? = null,
    onContextMenu: (GraphPointer, edge: Any?) -> Unit = { _, _ -> },
    onRelease: () -> Unit = {},
    onSelectArea: ((area: GraphRect, modifiers: Int) -> Unit)? = null,
    onKey: (UiKeyInput) -> Boolean = { false },
    overlay: @Composable () -> Unit = {},
    nodes: @Composable () -> Unit,
) {
    var canvas by remember { mutableStateOf(UiRect.Zero) }
    var hovered by remember { mutableStateOf<Any?>(null) }
    var marquee by remember { mutableStateOf<GraphRect?>(null) }
    val grabbedAt = remember { floatArrayOf(0f, 0f) }
    val selecting = remember { booleanArrayOf(false) }
    val lastEdgeClick = remember { EdgeClick() }

    LaunchedEffect(view) {
        while (true) {
            withFrameNanos(view::advance)
        }
    }

    fun pointer(event: UiEvent) = GraphPointer(
        screenX = event.x,
        screenY = event.y,
        canvasX = event.localX,
        canvasY = event.localY,
        graphX = view.toGraphX(event.localX),
        graphY = view.toGraphY(event.localY),
        modifiers = event.modifiers,
    )

    fun edgeAt(x: Float, y: Float): Any? =
        GraphCurves.nearest(edges.map { it.key to it.curve }, x, y, EDGE_HIT_RADIUS)

    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOf("graph-canvas"),
        modifier = modifier
            .style(GraphStylesheet)
            .clip(true)
            .onPlaced { canvas = it }
            .input(hoverable = true, clickable = true, draggable = true)
            .drawBehind(GraphDrawKey(view.zoom, view.panX, view.panY, edges, link, hovered, frames)) {
                drawGrid(view)
                frames.forEach { drawFrame(it, view) }
                edges.sortedBy { it.selected }.forEach { edge ->
                    val color = when {
                        edge.selected -> GraphColors.EdgeSelected
                        edge.key == hovered -> GraphColors.EdgeHover
                        else -> edge.color
                    }
                    drawCurve(edge.curve, view.zoom, color, edge.arrow)
                }
                link?.let { pending ->
                    val curve = pending.curve
                    if (curve != null) drawCurve(curve, view.zoom, pending.color, arrow = false)
                    else drawSegment(pending.fromX, pending.fromY, pending.toX, pending.toY, pending.color, 1.5f)
                }
            }
            .onHover { event ->
                val edge = edgeAt(event.localX, event.localY)
                if (edge != hovered) hovered = edge
            }
            .onExit { hovered = null }
            .focus()
            .onKeyInput { input ->
                when {
                    onKey(input) -> input.consume()
                    HollowKeys.FRAME.matches(input) -> {
                        view.reset()
                        input.consume()
                    }
                }
            }
            .onScroll { event ->
                view.zoomBy(if (event.rawScrollY > 0f) ZOOM_STEP else 1f / ZOOM_STEP, event.localX, event.localY)
                event.consume()
            }
            .onPress { event ->
                grabbedAt[0] = event.localX
                grabbedAt[1] = event.localY
                selecting[0] = onSelectArea != null && event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                if (!selecting[0]) view.grab(event.localX, event.localY)
                event.consume()
            }
            .onDrag { event ->
                val x = grabbedAt[0] + event.dragTotalX
                val y = grabbedAt[1] + event.dragTotalY
                if (selecting[0]) {
                    marquee = GraphRect.between(grabbedAt[0], grabbedAt[1], x, y)
                } else {
                    view.dragTo(x, y)
                }
                event.consume()
            }
            .onRelease { event ->
                marquee?.let { area ->
                    marquee = null
                    val covered = GraphRect.between(
                        view.toGraphX(area.x), view.toGraphY(area.y),
                        view.toGraphX(area.x + area.width), view.toGraphY(area.y + area.height),
                    )
                    onSelectArea?.invoke(covered, event.modifiers)
                }
                selecting[0] = false
                onRelease()
                event.consume()
            }
            .onClick { event ->
                val edge = edgeAt(event.localX, event.localY)
                when {
                    event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT -> onContextMenu(pointer(event), edge)
                    edge != null && onEdgeDoubleClick != null && lastEdgeClick.repeats(edge) ->
                        onEdgeDoubleClick(edge, pointer(event))

                    edge != null -> onEdgeClick(edge)
                    else -> onBackgroundClick(pointer(event))
                }
                event.consume()
            },
    ) {
        Box(
            mode = UiBoxMode.STACK,
            modifier = Modifier
                .position(0.px, 0.px)
                .size(100.percent, 100.percent)
                .pivot(0.px, 0.px)
                .translate(view.panX, view.panY)
                .scale(view.zoom)
                .transition(),
        ) {
            nodes()
        }

        marquee?.let { area ->
            Box(
                tags = listOf("graph-marquee"),
                modifier = Modifier.position(area.x.px, area.y.px).size(area.width.px, area.height.px).inputTransparent(),
            )
        }

        if (minimap.isNotEmpty()) {
            Column(
                modifier = Modifier.size(100.percent, 100.percent).padding(8.px).inputTransparent()
                    .alignItems(horizontal = UiAlign.END, vertical = UiAlign.END),
            ) {
                GraphMiniMap(minimap) { x, y -> view.centerOn(x, y, canvas.width, canvas.height) }
            }
        }
        overlay()
    }
}

/**
 * Every node of the graph at a glance; a click on one brings it to the middle of the canvas.
 */
@Composable
private fun GraphMiniMap(items: List<GraphMiniMapItem>, onJumpTo: (Float, Float) -> Unit) {
    val minX = items.minOf { it.rect.x }
    val maxX = items.maxOf { it.rect.x + it.rect.width }
    val minY = items.minOf { it.rect.y }
    val maxY = items.maxOf { it.rect.y + it.rect.height }
    val scale = minOf(
        (MINIMAP_WIDTH - 8f) / (maxX - minX).coerceAtLeast(1f),
        (MINIMAP_HEIGHT - 8f) / (maxY - minY).coerceAtLeast(1f),
    )

    Box(
        mode = UiBoxMode.STACK,
        tags = listOf("graph-minimap"),
        modifier = Modifier.size(MINIMAP_WIDTH.px, MINIMAP_HEIGHT.px),
    ) {
        items.forEachIndexed { index, item ->
            Box(
                id = "graph-minimap-$index",
                tags = listOf("graph-minimap-mark"),
                modifier = Modifier
                    .position((4f + (item.rect.x - minX) * scale).px, (4f + (item.rect.y - minY) * scale).px)
                    .size((item.rect.width * scale).coerceAtLeast(4f).px, (item.rect.height * scale).coerceAtLeast(3f).px)
                    .background(item.color)
                    .input(hoverable = true, clickable = true)
                    .let { if (item.tooltip != null) it.tooltipOnHover(item.tooltip) else it }
                    .onPress { event ->
                        if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onJumpTo(item.rect.centerX, item.rect.centerY)
                        event.consume()
                    },
            )
        }
    }
}

/** A line of the grid every [GRID_STEP] graph units, while they are far enough apart to be worth it. */
private fun UiCanvasDrawScope.drawGrid(view: GraphViewState) {
    val paint = UiPaint.Color(GraphColors.Grid)
    val step = GRID_STEP * view.zoom
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

/** Draws a curve segment by segment, each clipped to the canvas, and an arrowhead at its end. */
private fun UiCanvasDrawScope.drawCurve(curve: GraphCurve, zoom: Float, color: UiColor, arrow: Boolean) {
    val points = curve.points
    val width = (1.5f * zoom).coerceIn(1f, 3f)
    for (index in 0 until curve.pointCount - 1) {
        drawSegment(points[index * 2], points[index * 2 + 1], points[index * 2 + 2], points[index * 2 + 3], color, width)
    }
    if (!arrow || curve.pointCount < 2) return

    val head = ARROW_SIZE * zoom
    val last = points.size - 2
    val angle = atan2(points[last + 1] - points[last - 1], points[last] - points[last - 2])
    val tipX = points[last]
    val tipY = points[last + 1]
    drawSegment(tipX, tipY, tipX - head * cos(angle - ARROW_SPREAD), tipY - head * sin(angle - ARROW_SPREAD), color, width)
    drawSegment(tipX, tipY, tipX - head * cos(angle + ARROW_SPREAD), tipY - head * sin(angle + ARROW_SPREAD), color, width)
}

/** Lines are clipped here because a stroke drawn behind a node is not bounded by the node's clip. */
private fun UiCanvasDrawScope.drawSegment(x1: Float, y1: Float, x2: Float, y2: Float, color: UiColor, width: Float) {
    val clipped = clipSegment(x1, y1, x2, y2, size.width, size.height) ?: return
    val shape = GenericShape {
        moveTo(clipped[0], clipped[1])
        lineTo(clipped[2], clipped[3])
    }
    drawShape(shape, bounds, UiPaint.Color(color), UiDrawStyle.Stroke(width))
}

/**
 * A frame fills its part of the canvas and outlines it, cut to the canvas by hand, since what is drawn
 * behind a node is not bounded by the node's clip.
 */
private fun UiCanvasDrawScope.drawFrame(frame: GraphFrame, view: GraphViewState) {
    val onCanvas = frame.rect.toCanvas(view)
    val left = onCanvas.x.coerceAtLeast(0f)
    val top = onCanvas.y.coerceAtLeast(0f)
    val right = (onCanvas.x + onCanvas.width).coerceAtMost(size.width)
    val bottom = (onCanvas.y + onCanvas.height).coerceAtMost(size.height)
    if (right <= left || bottom <= top) return
    val radius = FRAME_RADIUS * view.zoom
    drawRect(
        UiRect(left, top, right - left, bottom - top),
        UiPaint.Color(frame.color.copy(alpha = frame.color.alpha * FRAME_FILL)),
        radius = radius,
        border = UiBorder(UiInsets.all(1.px), frame.color.copy(alpha = frame.color.alpha * FRAME_OUTLINE), radius),
    )
}

/** When the last link was clicked, so a second click on it soon after reads as a double click. */
private class EdgeClick {
    private var key: Any? = null
    private var at = 0L

    fun repeats(edge: Any): Boolean {
        val now = System.currentTimeMillis()
        val repeated = edge == key && now - at <= DOUBLE_CLICK_MILLIS
        key = if (repeated) null else edge
        at = now
        return repeated
    }
}

private data class GraphDrawKey(
    val zoom: Float,
    val panX: Float,
    val panY: Float,
    val edges: List<GraphEdge>,
    val link: GraphLinkPreview?,
    val hovered: Any?,
    val frames: List<GraphFrame>,
)

const val FRAME_RADIUS = 8f
private const val FRAME_FILL = 0.12f
private const val FRAME_OUTLINE = 0.45f
private const val DOUBLE_CLICK_MILLIS = 400L

/** How far apart the lines of the grid are, in graph units. */
const val GRID_STEP = 32f

/** How big an arrowhead is at zoom 1; a link that ends in one stops this short of its node. */
const val ARROW_SIZE = 9f

private const val ARROW_SPREAD = 0.5f
private const val EDGE_HIT_RADIUS = 6f
private const val ZOOM_STEP = 1.18f
private const val MINIMAP_WIDTH = 120f
private const val MINIMAP_HEIGHT = 78f
