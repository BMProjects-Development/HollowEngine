package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNodeMetrics.FIELD_ROW
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

const val GraphNodesStylesheet = "hollowengine:ui/styles/graph-nodes.hss"

val GraphLinkColor = UiColor.fromArgb(0xFFA5ABC0.toInt())
val GraphSelectedColor = UiColor(0.43f, 0.61f, 0.86f)

fun graphEditorText(name: String): String = "hollowengine.gui.graph.editor.$name".lang

/** The sizes of the nodes of a graph, in graph units. */
object GraphNodeMetrics {
    const val WIDTH = 216f

    /** The title bar of a collapsed node, which is all of it; an open node has a line under it too. */
    const val COLLAPSED = 32f
    const val HEADER = COLLAPSED + 1f

    /** A row that is a name alone; one with a field to edit is [FIELD_ROW] tall. */
    const val ROW = 21f
    const val FIELD_ROW = 27f

    /** How far the rows sit from the edges of the node, sideways and above the first and under the last. */
    const val INSET = 12f
    const val PADDING = 10f
    const val PIN = 9f

    /** How wide a reroute dot is. */
    const val REROUTE = 12f
}

/**
 * The canvas of a node graph with everything [GraphEditorController] does on it. [content] draws the scene, usually
 * through [GraphFrameHeaders], [GraphNodeFrame] and [GraphGroupCard].
 */
@Composable
fun GraphEditorCanvas(
    controller: GraphEditorController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = controller.view
    val scene = controller.model.scene
    val selection = controller.selection
    val curves = scene.links.map { each ->
        each to GraphCurves.throughPoints(
            view.toCanvasX(each.from.x), view.toCanvasY(each.from.y), scene.passing[each.from.node],
            view.toCanvasX(each.to.x), view.toCanvasY(each.to.y), scene.passing[each.to.node],
            view.zoom,
        )
    }
    controller.curves = curves.map { (each, curve) -> each.key to curve }
    Box(mode = UiBoxMode.STACK, modifier = modifier.onPlaced { controller.canvas = it }) {
        GraphCanvas(
            id = controller.canvasId,
            view = view,
            modifier = Modifier.size(100.percent, 100.percent),
            edges = curves.map { (each, curve) ->
                GraphEdge(
                    key = each.key,
                    curve = curve,
                    color = each.color,
                    selected = selection.link == each.key || controller.insertInto == each.key,
                )
            },
            link = controller.preview(),
            frames = scene.frames.map { GraphFrame(it.rect, it.color) },
            minimap = scene.boxes.map { (id, rect) ->
                GraphMiniMapItem(
                    rect,
                    if (id in selection) GraphSelectedColor else GraphLinkColor,
                    scene.titles[id].orEmpty()
                )
            } + scene.cards.map { GraphMiniMapItem(it.rect, it.color, it.title) },
            onBackgroundClick = {
                controller.menu = null
                controller.select(GraphSelection.None)
            },
            onEdgeClick = { link -> controller.select(GraphSelection.ofLink(link)) },
            onEdgeDoubleClick = { link, pointer ->
                controller.select(
                    controller.model.addReroute(
                        link,
                        pointer.graphX,
                        pointer.graphY
                    )
                )
            },
            onContextMenu = { pointer, edge ->
                if (edge != null) controller.linkMenu(edge, pointer) else controller.backgroundMenu(pointer)
            },
            onRelease = {
                controller.finishLink()
                controller.finishDrag()
            },
            onSelectArea = controller::selectArea,
            onKey = controller::handleKey,
            nodes = content,
        )

        controller.menu?.let { open ->
            ContextMenu(
                id = "${controller.canvasId}-menu",
                anchorBounds = UiRect(open.x, open.y, 0f, 0f),
                items = open.items,
                onExpandedChange = { if (!it) controller.menu = null },
            )
        }
    }
}

/** What the link being drawn looks like now. */
private fun GraphEditorController.preview(): GraphLinkPreview? {
    val pending = link ?: return null
    val pin =
        model.scene.pins.firstOrNull { it.node == pending.node && it.name == pending.pin && it.output == pending.fromOutput }
            ?: return null
    val fromX = view.toCanvasX(pin.x)
    val fromY = view.toCanvasY(pin.y)
    val curve = if (pending.fromOutput) GraphCurves.betweenPins(fromX, fromY, pending.x, pending.y, view.zoom)
    else GraphCurves.betweenPins(pending.x, pending.y, fromX, fromY, view.zoom)
    return GraphLinkPreview(fromX, fromY, pending.x, pending.y, model.pinColor(pin), curve)
}

/** The title bars of the open groups. */
@Composable
fun GraphFrameHeaders(controller: GraphEditorController) {
    val selection = controller.selection
    controller.model.scene.frames.forEach { frame ->
        key("frame-${frame.id}") {
            GraphGroupHeader(
                id = "gn-frame-${frame.id}",
                canvasId = controller.canvasId,
                view = controller.view,
                frame = frame.rect,
                title = frame.title,
                color = frame.color,
                selected = selection.group == frame.id,
                onPress = { controller.pressGroup(frame.id, it) },
                onDrag = controller::dragNode,
                onRelease = controller::releaseNode,
                onContextMenu = { x, y -> controller.groupMenu(frame.id, x, y) },
                onToggle = { controller.model.toggleGroup(frame.id) },
            )
        }
    }
}

/** A node at [rect] that the pointer picks and drags and whose right click opens its menu. */
/** Marks a field of [node]: the node stays built while the field is being typed in, wherever the view goes. */
fun Modifier.graphField(controller: GraphEditorController, node: String) =
    onFocus { controller.editing = node }.onUnfocus { if (controller.editing == node) controller.editing = null }

@Composable
fun GraphNodeFrame(
    controller: GraphEditorController,
    node: String,
    rect: GraphRect,
    tags: List<String>,
    content: @Composable () -> Unit,
) {
    GraphNode(
        id = "gn-node-$node",
        canvasId = controller.canvasId,
        view = controller.view,
        x = rect.x,
        y = rect.y,
        width = rect.width,
        height = rect.height,
        selected = node in controller.selection,
        tags = tags,
        onPress = { controller.pressNode(node, it) },
        onDrag = controller::dragNode,
        onRelease = controller::releaseNode,
        onContextMenu = { x, y -> controller.nodeMenu(node, x, y) },
        content = { Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) { content() } },
    )
}

/**
 * A reroute: a dot in the color of what passes through it. A drag moves it; Alt and a drag pull a new link out of it,
 * since a dot has no room for a pin of its own.
 */
@Composable
fun GraphRerouteView(
    controller: GraphEditorController,
    node: String,
    rect: GraphRect,
    output: GraphPin,
    tags: List<String>,
) {
    val linking = remember { booleanArrayOf(false) }
    GraphNode(
        id = "gn-node-$node",
        canvasId = controller.canvasId,
        view = controller.view,
        x = rect.x,
        y = rect.y,
        width = rect.width,
        height = rect.height,
        selected = node in controller.selection,
        tags = listOf("gn-reroute") + tags,
        onPress = { gesture ->
            linking[0] = gesture.alt
            if (gesture.alt) controller.pressPin(output, gesture.screenX, gesture.screenY) else controller.pressNode(
                node,
                gesture
            )
        },
        onDrag = { gesture ->
            if (linking[0]) controller.dragPin(gesture.canvasX, gesture.canvasY, gesture.screenX, gesture.screenY)
            else controller.dragNode(gesture)
        },
        onRelease = {
            if (linking[0]) controller.releasePin() else controller.releaseNode()
            linking[0] = false
        },
        onContextMenu = { x, y -> controller.nodeMenu(node, x, y) },
    ) {}
}

/** A pin on the edge of its node, in the color of what it carries, filled once something is linked. */
@Composable
fun GraphPinView(
    controller: GraphEditorController,
    pin: GraphPin,
    nodeX: Float,
    nodeY: Float,
    connected: Boolean,
    tags: List<String> = emptyList(),
) {
    val half = GraphNodeMetrics.PIN / 2f
    val held = remember { booleanArrayOf(false) }
    val tooltip = controller.model.pinTooltip(pin)
    Box(
        id = "gn-pin-${pin.node}-${if (pin.output) "out" else "in"}-${pin.name}",
        tags = listOf("gn-pin") + tags + listOfNotNull("connected".takeIf { connected }),
        modifier = Modifier.position((pin.x - nodeX - half).px, (pin.y - nodeY - half).px)
            .size(GraphNodeMetrics.PIN.px, GraphNodeMetrics.PIN.px)
            .let { if (tooltip != null) it.tooltipOnHover(tooltip) else it }
            .input(hoverable = true, clickable = true, draggable = true).onPress { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                held[0] = true
                controller.pressPin(pin, event.x, event.y)
                event.consume()
            }.onDrag { event ->
                if (!held[0]) return@onDrag
                event.ancestorLocalPositions[controller.canvasId]?.let {
                    controller.dragPin(
                        it.x,
                        it.y,
                        event.x,
                        event.y
                    )
                }
                event.consume()
            }.onRelease { event ->
                if (!held[0]) return@onRelease
                held[0] = false
                controller.releasePin()
                event.consume()
            },
    )
}

/**
 * The title bar in the color of the category: its icon, the name, and the arrow that collapses the node.
 *
 * @param node what the arrow's id is made from.
 * @param problem what is wrong with the node, told by a mark on the bar: the bar itself has to keep taking the pointer.
 * @param color what the bar is painted in, when the style of the node does not say.
 * @param warning whether [problem] lets the node work all the same.
 */
@Composable
fun GraphNodeHeader(
    node: String,
    title: String,
    icon: String?,
    width: Float,
    collapsed: Boolean,
    problem: String? = null,
    color: UiColor? = null,
    warning: Boolean = false,
    onToggle: () -> Unit,
) {
    val bar = GraphNodeMetrics.COLLAPSED
    fun Modifier.painted() = if (color != null) background(color) else this
    Row(
        tags = listOf("gn-node-head"),
        modifier = Modifier.position(0.px, 0.px).size(width.px, bar.px).alignItems(vertical = UiAlign.CENTER).painted(),
    ) {
        if (problem != null) {
            Image(
                if (warning) WarningIcon else ErrorIcon,
                tags = listOf("gn-node-problem"),
                modifier = Modifier.tooltipOnHover(problem)
            )
        }
        if (icon != null) Image(icon, tags = listOf("gn-node-icon"))
        Text(title, tags = listOf("gn-node-title"), modifier = Modifier.size(0.px, UiLength.Auto).grow(1f))
        Box(
            id = "gn-collapse-$node",
            tags = listOf("gn-node-toggle"),
            modifier = Modifier.input(hoverable = true, clickable = true).onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onToggle()
                event.consume()
            },
        ) {
            Image(ArrowIcon, tags = listOf("gn-node-arrow"))
        }
    }
    if (collapsed) return
    Box(
        tags = listOf("gn-node-head-flat"),
        modifier = Modifier.position(0.px, (bar - SquaredStrip).px).size(width.px, SquaredStrip.px).painted()
            .inputTransparent(),
    )
    listOf(0f, width - 1f).forEach { x ->
        Box(
            tags = listOf("gn-node-head-edge"),
            modifier = Modifier.position(x.px, (bar - SquaredStrip).px).size(1.px, SquaredStrip.px).inputTransparent(),
        )
    }
    Box(
        tags = listOf("gn-node-divider"),
        modifier = Modifier.position(0.px, bar.px).size(width.px, 1.px).inputTransparent(),
    )
}

/**
 * A collapsed group as one node: a title bar in the group's color with an arrow that opens it again, and a row per pin
 * surrounding links reach, named after the pin and the node it belongs to.
 *
 * @param pinName how a pin is called on the card.
 * @param ownerName the title of the node a pin belongs to.
 */
@Composable
fun GraphGroupCard(
    controller: GraphEditorController,
    card: GraphCardView,
    pinName: (GraphPin) -> String,
    ownerName: (GraphPin) -> String,
    pinTags: (GraphPin) -> List<String> = { emptyList() },
) {
    val rect = card.rect
    GraphNode(
        id = "gn-group-${card.id}",
        canvasId = controller.canvasId,
        view = controller.view,
        x = rect.x,
        y = rect.y,
        width = rect.width,
        height = rect.height,
        selected = controller.selection.group == card.id,
        tags = listOf("gn-node", "gn-group-card"),
        onPress = { controller.pressGroup(card.id, it) },
        onDrag = controller::dragNode,
        onRelease = controller::releaseNode,
        onContextMenu = { x, y -> controller.groupMenu(card.id, x, y) },
    ) {
        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
            Row(
                tags = listOf("gn-node-head", "gn-group-head"),
                modifier = Modifier.position(0.px, 0.px).size(rect.width.px, GraphNodeMetrics.COLLAPSED.px)
                    .background(card.color).alignItems(vertical = UiAlign.CENTER),
            ) {
                Image(GroupIcon, tags = listOf("gn-node-icon"))
                Text(card.title, tags = listOf("gn-node-title"), modifier = Modifier.size(0.px, UiLength.Auto).grow(1f))
                GraphGroupToggle(
                    "gn-group-toggle-${card.id}",
                    collapsed = true
                ) { controller.model.toggleGroup(card.id) }
            }
            card.pins.forEach { pin -> CardRow(pin, pinName(pin), ownerName(pin), rect.y, rect.width) }
            card.pins.forEach { pin ->
                GraphPinView(
                    controller,
                    pin,
                    rect.x,
                    rect.y,
                    connected = true,
                    tags = pinTags(pin)
                )
            }
        }
    }
}

/** The name of a pin of a collapsed group next to the pin, with the node it belongs to on the inner side. */
@Composable
private fun CardRow(pin: GraphPin, name: String, owner: String, y: Float, width: Float) {
    val half = GraphNodeMetrics.ROW / 2f
    Row(
        tags = listOf("gn-node-row"),
        modifier = Modifier.position(0.px, (pin.y - y - half).px).size(width.px, GraphNodeMetrics.ROW.px)
            .padding(GraphNodeMetrics.INSET.px, 0.px).alignItems(vertical = UiAlign.CENTER).inputTransparent(),
    ) {
        if (pin.output) {
            Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
            Text(owner, tags = listOf("gn-pin-hint"))
            Text(name, tags = listOf("gn-pin-label", "output"))
        } else {
            Text(name, tags = listOf("gn-pin-label"))
            Text(owner, tags = listOf("gn-pin-hint"))
        }
    }
}

private const val ArrowIcon = "hollowengine:textures/gui/icons/graph/arrow.svg"
private const val GroupIcon = "hollowengine:textures/gui/icons/actions/group.svg"
private const val ErrorIcon = "hollowengine:textures/gui/icons/error.svg"
private const val WarningIcon = "hollowengine:textures/gui/icons/warn.svg"

/** How tall the strip is that squares off the bottom corners of the title bar of an open node. */
private const val SquaredStrip = 8f
