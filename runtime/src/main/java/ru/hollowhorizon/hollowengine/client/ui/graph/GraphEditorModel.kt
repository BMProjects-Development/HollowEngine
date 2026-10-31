package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem

/** One pin of a placed node, where it sits in graph units. */
data class GraphPin(val node: String, val name: String, val output: Boolean, val x: Float, val y: Float)

/**
 * What is selected: some nodes, or one link by the key its graph gave it. A group taken by its title bar selects its
 * nodes and names itself in [group], which is what an inspector shows.
 */
data class GraphSelection(val nodes: Set<String> = emptySet(), val link: Any? = null, val group: String? = null) {
    val single: String? get() = nodes.singleOrNull()

    operator fun contains(node: String): Boolean = node in nodes

    companion object {
        val None = GraphSelection()

        fun of(node: String) = GraphSelection(setOf(node))
        fun ofLink(link: Any) = GraphSelection(link = link)
    }
}

/** What an editor of one graph keeps while its file stays open. */
class GraphEditorState {
    val view = GraphViewState()
    var selection by mutableStateOf(GraphSelection.None)
}

/** A link being drawn from a pin: where the pointer is on the canvas, and on the screen for the menu of a drop on nothing. */
data class GraphPendingLink(
    val node: String,
    val pin: String,
    val fromOutput: Boolean,
    val x: Float,
    val y: Float,
    val screenX: Float,
    val screenY: Float,
)

/** A context menu an editor has open, where it was opened on the screen. */
data class GraphMenu(val x: Float, val y: Float, val items: List<UiDropdownItem>)

/** A link as the canvas draws it. [key] tells it apart in a [GraphSelection] and in [GraphEditorModel]. */
class GraphLinkView(val key: Any, val from: GraphPin, val to: GraphPin, val color: UiColor)

/** An open group: the frame around its nodes and what its title bar shows. */
class GraphFrameView(val id: String, val title: String, val color: UiColor, val rect: GraphRect)

/** A collapsed group, drawn as one node with a pin for each link that crosses it. */
class GraphCardView(
    val id: String,
    val title: String,
    val color: UiColor,
    val rect: GraphRect,
    val inputs: List<GraphPin>,
    val outputs: List<GraphPin>,
) {
    val pins: List<GraphPin> get() = inputs + outputs

    companion object {
        /**
         * A card at the corner of [around], with a pin for each of [entering] and [leaving], the node and pin of each
         * link that crosses the group.
         */
        fun of(
            id: String,
            title: String,
            color: UiColor,
            around: GraphRect,
            entering: List<Pair<String, String>>,
            leaving: List<Pair<String, String>>,
        ): GraphCardView {
            val rows = entering.size + leaving.size
            val body = if (rows == 0) 0f else GraphNodeMetrics.PADDING * 2 + rows * GraphNodeMetrics.ROW
            val rect = GraphRect(around.x, around.y, GraphNodeMetrics.WIDTH, GraphNodeMetrics.HEADER + body)

            fun rowY(index: Int) =
                rect.y + GraphNodeMetrics.HEADER + GraphNodeMetrics.PADDING + GraphNodeMetrics.ROW * (index + 0.5f)
            return GraphCardView(
                id, title, color, rect,
                inputs = entering.mapIndexed { index, (node, pin) ->
                    GraphPin(
                        node,
                        pin,
                        false,
                        rect.x,
                        rowY(leaving.size + index)
                    )
                },
                outputs = leaving.mapIndexed { index, (node, pin) ->
                    GraphPin(
                        node,
                        pin,
                        true,
                        rect.x + rect.width,
                        rowY(index)
                    )
                },
            )
        }
    }
}

/** What the canvas shows of a graph, with its groups taken into account. */
class GraphEditorScene(
    val boxes: Map<String, GraphRect>,
    val titles: Map<String, String>,
    val pins: List<GraphPin>,
    val links: List<GraphLinkView>,
    val frames: List<GraphFrameView> = emptyList(),
    val cards: List<GraphCardView> = emptyList(),
    val reroutes: Set<String> = emptySet(),
) {
    /** The way links run through each of the [reroutes]. */
    val passing: Map<String, GraphDirection> by lazy {
        reroutes.associateWith { id ->
            val at = links.firstOrNull { it.to.node == id }?.to ?: links.firstOrNull { it.from.node == id }?.from
            val from = links.filter { it.to.node == id }.map { it.from.x to it.from.y }
            val to = links.filter { it.from.node == id }.map { it.to.x to it.to.y }
            GraphCurves.passing(at?.x ?: 0f, at?.y ?: 0f, from, to)
        }
    }
}

/**
 * What a graph is to [GraphEditorController]: a scene, and the edits gestures on it end in. The controller owns what
 * every graph does alike, a kind of graph answers only what depends on its nodes.
 */
interface GraphEditorModel {
    val scene: GraphEditorScene
    val history: UndoHistory?

    fun beginGesture()
    fun endGesture()

    /** Where the corner of [node] is, also while a collapsed group hides it. */
    fun positionOf(node: String): Pair<Float, Float>?

    /** Puts each of the nodes where [positions] says. */
    fun moveNodes(positions: Map<String, Pair<Float, Float>>)

    /** The color of what [pin] carries, for the link drawn from it. */
    fun pinColor(pin: GraphPin): UiColor

    /** Links [from] to [to], an output and an input in either order. */
    fun connect(from: GraphPin, to: GraphPin)

    /** Links what [pending] carries to the first pin of [node] that takes it. */
    fun connectToNode(pending: GraphPendingLink, node: String)

    /** Takes the link off [input] to be dropped elsewhere and gives the pin it came from; null when it stays. */
    fun detach(input: GraphPin): GraphPin?

    /** The items of the menu of a link dropped on nothing, at ([x], [y]) of the graph. */
    fun offerNodes(pending: GraphPendingLink, x: Float, y: Float): List<UiDropdownItem>

    /** Whether [node] has any links, so that dropping it on a link cannot go into it. */
    fun isLinked(node: String): Boolean

    /** Puts the lone [node] into [link]. */
    fun insertIntoLink(link: Any, node: String)

    fun removeLink(link: Any)

    /** A reroute put into [link], centered on ([x], [y]); it is selected. */
    fun addReroute(link: Any, x: Float, y: Float): GraphSelection

    fun removeNodes(nodes: Set<String>)

    /** Copies [nodes] next to them and gives the copies. */
    fun duplicate(nodes: Set<String>): Set<String>

    fun groupMembers(group: String): Set<String>
    fun isGrouped(node: String): Boolean
    fun groupNodes(nodes: Set<String>): GraphSelection?
    fun ungroup(selection: GraphSelection): GraphSelection?

    /** Puts [nodes] in [group], or out of every group when it is null. */
    fun setMembership(nodes: Set<String>, group: String?)
    fun removeGroup(group: String)
    fun toggleGroup(group: String)
    fun isCollapsed(group: String): Boolean

    /** What the menu of [node] offers ahead of copying, removing and grouping. */
    fun nodeItems(node: String): List<UiDropdownItem> = emptyList()

    /** Whether [node] can be copied and removed. */
    fun isRemovable(node: String): Boolean = true

    /** The items of the menu of an empty spot, at ([x], [y]) of the graph. */
    fun addItems(x: Float, y: Float): List<UiDropdownItem>
}
