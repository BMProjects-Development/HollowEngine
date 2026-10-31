package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.duplicate
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.link
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.linkValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.unlink
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.unlinkValue
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.list.StoryCardKind
import ru.hollowhorizon.hollowengine.addons.narrative.ide.list.StoryTargets
import ru.hollowhorizon.hollowengine.addons.narrative.ide.list.storyInspector
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeFilePreview
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewError
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeViewMode
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import kotlin.math.hypot

private const val CanvasId = "story-graph"
private const val NodeWidth = 220f
private const val HeaderHeight = 26f
private const val PinRow = 20f
private const val PinSize = 10f
private const val SnapRadius = 14f
private const val ColumnStep = 280f
private const val RowStep = 130f

/** The `.story` file as nodes and the links between them. */
internal val StoryGraphFilePreview = HollowIdeFilePreview(
    id = "story-graph",
    defaultMode = HollowIdeViewMode.PREVIEW,
    icon = "hollowengine:textures/gui/icons/files/story.svg",
    title = "hollowengine.gui.ide.story.graph",
    matcher = { it.endsWith(".story") },
) { StoryGraphPreview(it) }

/** One pin of a node as the graph draws it. */
private class Pin(val node: String, val name: String, val output: Boolean, val flow: Boolean, val row: Int)

/** A link being drawn out of [from], with the pointer at ([x], [y]) in graph units. */
private class PendingLink(val from: Pin, val x: Float, val y: Float)

private class GraphMenu(
    val x: Float,
    val y: Float,
    val graphX: Float,
    val graphY: Float,
    val node: String?,
    val edge: StoryLink?,
    val from: Pin?,
)

private class StoryGraphState : StoryEditorState() {
    val view = GraphViewState()
    var pending by mutableStateOf<PendingLink?>(null)
    var menu by mutableStateOf<GraphMenu?>(null)

    /** The node being dragged and how far it went, until the drop writes its place to the file. */
    var moved by mutableStateOf<Pair<String, StoryPoint>?>(null)
}

@Composable
internal fun StoryGraphPreview(context: HollowIdePreviewContext) {
    val state = context.state { StoryGraphState() }
    state.context = context
    val parsed = state.model.read(context.text)
    val graph = parsed.getOrNull() ?: return HollowIdePreviewError(parsed.exceptionOrNull())
    val arranged = remember(graph) { arrange(graph) }
    val places = state.moved?.let { arranged + it } ?: arranged
    val pins = remember(graph) { graph.nodes.associate { it.id to pinsOf(graph, it) } }

    fun position(pin: Pin): Pair<Float, Float> {
        val place = places.getValue(pin.node)
        return (if (pin.output) place.x + NodeWidth else place.x) to place.y + HeaderHeight + (pin.row + 0.5f) * PinRow
    }

    fun pinOf(node: String, name: String, output: Boolean) =
        pins[node]?.firstOrNull { it.name == name && it.output == output }

    PublishInspector(source = "story-graph:${context.file.path}", key = state.selected) {
        storyInspector(state, state.selected)
    }

    val edges = graph.links.mapNotNull { link ->
        val from = pinOf(link.from, link.fromPin, output = true) ?: return@mapNotNull null
        val to = pinOf(link.to, link.toPin, output = false) ?: return@mapNotNull null
        val (x1, y1) = position(from)
        val (x2, y2) = position(to)
        val view = state.view
        GraphEdge(
            link, GraphCurves.betweenPins(
                view.toCanvasX(x1), view.toCanvasY(y1), view.toCanvasX(x2), view.toCanvasY(y2), view.zoom
            )
        )
    }
    val preview = state.pending?.let { pending ->
        val (x, y) = position(pending.from)
        val view = state.view
        GraphLinkPreview(view.toCanvasX(x), view.toCanvasY(y), view.toCanvasX(pending.x), view.toCanvasY(pending.y))
    }

    Column(
        tags = listOf("ide-file-panel", "story-list-root"),
        modifier = Modifier.style(InspectorStylesheet).style(StoryEditorStylesheet),
    ) {
        GraphCanvas(
            id = CanvasId,
            view = state.view,
            modifier = Modifier.size(100.percent, 0.px).grow(1f),
            edges = edges,
            link = preview,
            onBackgroundClick = { state.selected = null },
            onContextMenu = { pointer, edge ->
                state.menu = GraphMenu(
                    pointer.screenX, pointer.screenY, pointer.graphX, pointer.graphY, null, edge as? StoryLink, null
                )
            },
            onKey = { input ->
                val selected = state.selected?.node
                if (input.key != GLFW.GLFW_KEY_DELETE || selected == null) return@GraphCanvas false
                state.change(StoryUndo.REMOVE) { removeNode(selected) }
                state.selected = null
                true
            },
        ) {
            graph.nodes.forEach { node ->
                val place = places[node.id] ?: return@forEach
                val nodePins = pins.getValue(node.id)
                val rows = (nodePins.maxOfOrNull { it.row } ?: -1) + 1
                GraphNode(
                    id = "story-node-${node.id}",
                    canvasId = CanvasId,
                    view = state.view,
                    x = place.x,
                    y = place.y,
                    width = NodeWidth,
                    height = HeaderHeight + rows * PinRow,
                    selected = state.selected?.node == node.id,
                    tags = listOf("story-node", StoryCardKind.of(node).tag),
                    onPress = { state.selected = StorySelection(node.id) },
                    onDrag = { gesture ->
                        state.moved = node.id to StoryPoint(
                            place0(arranged, node.id).x + gesture.graphDeltaX,
                            place0(arranged, node.id).y + gesture.graphDeltaY
                        )
                    },
                    onRelease = {
                        state.moved?.let { (id, point) -> state.change(StoryUndo.MOVE) { copy(layout = arranged + (id to point)) } }
                        state.moved = null
                    },
                    onContextMenu = { x, y ->
                        state.selected = StorySelection(node.id)
                        state.menu = GraphMenu(x, y, place.x, place.y, node.id, null, null)
                    },
                ) {
                    Text(StoryCardKind.of(node).title, tags = listOf("story-node-title"))
                    Text(StoryTargets.title(node), tags = listOf("story-muted"))
                }
            }
            pins.values.flatten().forEach { pin ->
                if (pin.node !in places) return@forEach
                val (x, y) = position(pin)
                PinView(state, pin, x, y) { px, py ->
                    pins.values.flatten().filter { it.output != pin.output && it.flow == pin.flow }.minByOrNull {
                        val (ox, oy) = position(it)
                        hypot(ox - px, oy - py)
                    }?.takeIf {
                        val (ox, oy) = position(it)
                        hypot(ox - px, oy - py) <= SnapRadius
                    }
                }
            }
        }
    }
    state.menu?.let { GraphContextMenu(state, it, arranged) }
}

private fun place0(arranged: Map<String, StoryPoint>, id: String) = arranged.getValue(id)

/** A pin. A link is drawn out of it; dropped on nothing, it opens the menu that adds what it leads to. */
@Composable
private fun PinView(state: StoryGraphState, pin: Pin, x: Float, y: Float, nearest: (Float, Float) -> Pin?) {
    Box(
        id = "story-pin-${pin.node}-${pin.name}-${pin.output}",
        tags = listOfNotNull("story-pin", "flow".takeIf { pin.flow }),
        modifier = Modifier.position((x - PinSize / 2f).px, (y - PinSize / 2f).px).size(PinSize.px, PinSize.px)
            .input(hoverable = true, clickable = true, draggable = true).onPress { it.consume() }.onDrag { event ->
                val zoom = state.view.zoom
                state.pending = PendingLink(pin, x + event.dragTotalX / zoom, y + event.dragTotalY / zoom)
                event.consume()
            }.onRelease { event ->
                val pending = state.pending ?: return@onRelease
                state.pending = null
                event.consume()
                val target = nearest(pending.x, pending.y)
                when {
                    target != null -> connect(state, pin, target)
                    pin.output && pin.flow -> state.menu =
                        GraphMenu(event.x, event.y, pending.x, pending.y, null, null, pin)
                }
            },
    )
}

private fun connect(state: StoryGraphState, first: Pin, second: Pin) {
    val from = if (first.output) first else second
    val to = if (first.output) second else first
    state.change(StoryUndo.LINK) {
        if (from.flow) link(from.node, from.name, to.node) else linkValue(from.node, from.name, to.node, to.name)
    }
}

@Composable
private fun GraphContextMenu(state: StoryGraphState, menu: GraphMenu, arranged: Map<String, StoryPoint>) {
    fun add(node: StoryNode) {
        state.change(StoryUndo.ADD) {
            val added = copy(nodes = nodes + node, layout = layout + (node.id to StoryPoint(menu.graphX, menu.graphY)))
            menu.from?.let { added.link(it.node, it.name, node.id) } ?: added
        }
        state.selected = StorySelection(node.id)
    }

    val items = buildList {
        val edge = menu.edge
        val node = menu.node
        when {
            edge != null -> add(UiDropdownItem(storyLang("remove")) {
                state.change(StoryUndo.REMOVE) {
                    if (edge.isFlow) unlink(edge.from, edge.fromPin) else unlinkValue(edge.to, edge.toPin)
                }
            })

            node != null -> {
                add(UiDropdownItem(storyLang("duplicate")) { state.change(StoryUndo.DUPLICATE) { duplicate(node) } })
                add(UiDropdownItem(storyLang("remove"), shortcut = "Delete") {
                    state.change(StoryUndo.REMOVE) { removeNode(node) }
                    state.selected = null
                })
            }

            else -> {
                add(UiDropdownItem(storyLang("add.phrase")) { add(StoryNode.Say(StoryGraph.newId())) })
                add(UiDropdownItem(storyLang("add.choice")) {
                    add(StoryNode.Choose(StoryGraph.newId(), listOf(StoryOption(storyLang("new.option")))))
                })
                add(UiDropdownItem(storyLang("add.condition")) { add(StoryNode.Branch(StoryGraph.newId())) })
                add(UiDropdownItem(storyLang("add.start")) {
                    add(
                        StoryNode.Start(
                            StoryGraph.newId(), storyLang("new.start")
                        )
                    )
                })
                add(UiDropdownItem(storyLang("add.expression")) { add(StoryNode.Expression(StoryGraph.newId(), "")) })
                add(UiDropdownItem(storyLang("add.command"), children = StoryCatalog.items(::add)))
            }
        }
    }
    ContextMenu(
        id = "story-graph-menu",
        anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
        items = items,
        onExpandedChange = { if (!it) state.menu = null },
    )
}

/** Every node with its place: as the file says, or by how far flow is from a start for those without one. */
private fun arrange(graph: StoryGraph): Map<String, StoryPoint> {
    val depth = HashMap<String, Int>()
    val queue = ArrayDeque(graph.nodes.filterIsInstance<StoryNode.Start>().map { it.id to 0 })
    while (queue.isNotEmpty()) {
        val (id, at) = queue.removeFirst()
        if (id in depth) continue
        depth[id] = at
        graph.links.filter { it.from == id && it.isFlow }.forEach { queue += it.to to at + 1 }
    }
    val rows = HashMap<Int, Int>()
    return graph.nodes.associate { node ->
        val column = depth[node.id] ?: 0
        val row = rows.merge(column, 1, Int::plus)!! - 1
        node.id to (graph.layout[node.id] ?: StoryPoint(column * ColumnStep, row * RowStep))
    }
}

/** The pins of [node]: flow and what it reads on the left, where flow goes and what it gives on the right. */
private fun pinsOf(graph: StoryGraph, node: StoryNode): List<Pin> {
    val inputs = buildList {
        if (node !is StoryNode.Expression) add(StoryPins.IN to true)
        when (node) {
            is StoryNode.Say -> add(StoryPins.SPEAKER to false)
            is StoryNode.Branch -> add(StoryPins.CONDITION to false)
            is StoryNode.Assign -> add(StoryPins.VALUE to false)
            is StoryNode.Code -> node.inputs.keys.forEach { add(it to false) }
            is StoryNode.Call -> {
                val params =
                    StoryCatalog.signatureOf(node)?.params.orEmpty().filter { it.group == null }.map { it.name }
                val linked = graph.links.filter { it.to == node.id && !it.isFlow }.map { it.toPin }
                (params + linked).distinct().forEach { add(it to false) }
            }

            else -> Unit
        }
    }
    val outputs = when (node) {
        is StoryNode.Expression -> listOf(StoryPins.VALUE to false)
        is StoryNode.Branch -> listOf(StoryPins.THEN to true, StoryPins.ELSE to true)
        is StoryNode.Choose -> node.options.indices.map { StoryPins.option(it) to true }
        is StoryNode.Code -> listOf(StoryPins.NEXT to true) + node.outputs.keys.map { it to false }
        is StoryNode.Call -> listOfNotNull(
            StoryPins.NEXT to true,
            (StoryPins.BODY to true).takeIf { node.block },
            (StoryPins.VALUE to false).takeIf { node.returns != null },
        )

        else -> listOf(StoryPins.NEXT to true)
    }
    return inputs.mapIndexed { row, (name, flow) ->
        Pin(
            node.id, name, false, flow, row
        )
    } + outputs.mapIndexed { row, (name, flow) -> Pin(node.id, name, true, flow, row) }
}
