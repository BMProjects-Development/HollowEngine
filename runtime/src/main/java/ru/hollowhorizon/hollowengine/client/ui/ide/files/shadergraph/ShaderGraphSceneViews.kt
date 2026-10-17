package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.*

/**
 * Everything on the canvas of a graph, back to front: the title bars of open groups, the nodes, the
 * selected ones last so they lie on top, and the collapsed groups.
 */
@Composable
internal fun ShaderGraphSceneView(
    graph: ShaderGraph,
    scene: ShaderGraphScene,
    types: ShaderGraphTypes,
    view: GraphViewState,
    selection: ShaderGraphSelection,
    problems: Map<String, List<ShaderDiagnostic>>,
    previews: ShaderGraphPreviews,
    actions: ShaderNodeActions,
) {
    scene.frames.forEach { (id, rect) ->
        val group = graph.group(id) ?: return@forEach
        key("frame-$id") {
            GraphGroupHeader(
                id = "sg-frame-$id",
                canvasId = ShaderGraphCanvasId,
                view = view,
                frame = rect,
                title = group.title.ifBlank { graphText("group") },
                color = GraphGroupColors.of(group.color),
                selected = selection.group == id,
                onPress = { actions.pressGroup(id, it) },
                onDrag = { actions.dragNode(id, it) },
                onRelease = { actions.releaseNode(id) },
                onContextMenu = { x, y -> actions.groupMenu(id, x, y) },
                onToggle = { actions.toggleGroup(id) },
            )
        }
    }
    scene.boxes.values.sortedBy { it.node.id in selection }.forEach { box ->
        key(box.node.id) {
            if (box.isReroute) {
                ShaderRerouteView(
                    box = box,
                    type = types.output(box.node.id, ShaderNodeLibrary.REROUTE_OUTPUT),
                    view = view,
                    selected = box.node.id in selection,
                    actions = actions,
                )
            } else {
                ShaderNodeView(
                    graph = graph,
                    box = box,
                    types = types,
                    view = view,
                    selected = box.node.id in selection,
                    problem = problems[box.node.id]?.joinToString("\n") { problemText(it) },
                    previews = previews,
                    actions = actions,
                )
            }
        }
    }
    scene.cards.values.forEach { card ->
        key("card-${card.group.id}") {
            ShaderGroupCardView(graph, card, types, view, selected = selection.group == card.group.id, actions = actions)
        }
    }
}

/**
 * A reroute: a dot in the color of what passes through it. A drag moves it; Alt and a drag pull a new
 * link out of it, since a dot has no room for a pin of its own.
 */
@Composable
internal fun ShaderRerouteView(
    box: ShaderNodeBox,
    type: ShaderType?,
    view: GraphViewState,
    selected: Boolean,
    actions: ShaderNodeActions,
) {
    val node = box.node
    val linking = remember { booleanArrayOf(false) }
    GraphNode(
        id = "sg-node-${node.id}",
        canvasId = ShaderGraphCanvasId,
        view = view,
        x = node.x,
        y = node.y,
        width = box.width,
        height = box.height,
        selected = selected,
        tags = listOfNotNull("sg-reroute", type?.tag()),
        onPress = { gesture ->
            linking[0] = gesture.alt
            val output = box.pins.first { it.output }
            if (gesture.alt) actions.pressPin(output, gesture.screenX, gesture.screenY) else actions.pressNode(node.id, gesture)
        },
        onDrag = { gesture ->
            if (linking[0]) actions.dragPin(gesture.canvasX, gesture.canvasY, gesture.screenX, gesture.screenY)
            else actions.dragNode(node.id, gesture)
        },
        onRelease = {
            if (linking[0]) actions.releasePin() else actions.releaseNode(node.id)
            linking[0] = false
        },
        onContextMenu = { x, y -> actions.nodeMenu(node.id, x, y) },
    ) {}
}

/**
 * A collapsed group as one node: title bar in group's color with arrow that opens it
 * again, and a row per pin surrounding links reach, named after pin and node it belongs to.
 */
@Composable
internal fun ShaderGroupCardView(
    graph: ShaderGraph,
    card: ShaderGroupCard,
    types: ShaderGraphTypes,
    view: GraphViewState,
    selected: Boolean,
    actions: ShaderNodeActions,
) {
    val group = card.group
    val rect = card.rect
    GraphNode(
        id = "sg-group-${group.id}",
        canvasId = ShaderGraphCanvasId,
        view = view,
        x = rect.x,
        y = rect.y,
        width = rect.width,
        height = rect.height,
        selected = selected,
        tags = listOf("sg-node", "sg-group-card"),
        onPress = { actions.pressGroup(group.id, it) },
        onDrag = { actions.dragNode(group.id, it) },
        onRelease = { actions.releaseNode(group.id) },
        onContextMenu = { x, y -> actions.groupMenu(group.id, x, y) },
    ) {
        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
            Row(
                tags = listOf("sg-node-head", "sg-group-head"),
                modifier = Modifier.position(0.px, 0.px).size(rect.width.px, ShaderNodeLayout.COLLAPSED.px)
                    .background(GraphGroupColors.of(group.color)).alignItems(vertical = UiAlign.CENTER),
            ) {
                Image(GroupIcon, tags = listOf("sg-node-icon"))
                Text(
                    group.title.ifBlank { graphText("group") },
                    tags = listOf("sg-node-title"),
                    modifier = Modifier.size(0.px, UiLength.Auto).grow(1f),
                )
                GraphGroupToggle("sg-group-toggle-${group.id}", collapsed = true) { actions.toggleGroup(group.id) }
            }
            card.inputs.forEach { pin -> CardRow(graph, pin, rect.y, rect.width) }
            card.outputs.forEach { pin -> CardRow(graph, pin, rect.y, rect.width) }
            card.pins.forEach { pin ->
                val type = if (pin.output) types.output(pin.node, pin.name) else types.input(pin.node, pin.name)
                ShaderPinView(pin, rect.x, rect.y, type, connected = true, actions)
            }
        }
    }
}

/** The name of a pin of a collapsed group, on its side of the node, with the node it belongs to after it. */
@Composable
private fun CardRow(graph: ShaderGraph, pin: ShaderPin, y: Float, width: Float) {
    val owner = graph.node(pin.node)?.let { ShaderNodeTypes.of(it.type) }?.title() ?: pin.node
    val half = ShaderNodeLayout.ROW / 2f
    Row(
        tags = listOf("sg-node-row"),
        modifier = Modifier.position(0.px, (pin.y - y - half).px).size(width.px, ShaderNodeLayout.ROW.px)
            .padding(ShaderNodeLayout.INSET.px, 0.px).alignItems(vertical = UiAlign.CENTER).inputTransparent(),
    ) {
        // The pin name sits next to its pin, the node it belongs to on the inner side.
        if (pin.output) {
            Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
            Text(owner, tags = listOf("sg-pin-hint"))
            Text(pin.name, tags = listOf("sg-pin-label", "output"))
        } else {
            Text(pin.name, tags = listOf("sg-pin-label"))
            Text(owner, tags = listOf("sg-pin-hint"))
        }
    }
}

private const val GroupIcon = "hollowengine:textures/gui/icons/actions/group.svg"
