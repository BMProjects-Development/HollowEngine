package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeShaderGraphDocument
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts

/** A material graph as [GraphEditorController] sees it, made anew for each graph drawn. */
internal class ShaderGraphEditorModel(
    private val document: HollowIdeShaderGraphDocument,
    private val code: ShaderGraphCode,
    val nodes: ShaderGraphScene,
    private val select: (GraphSelection) -> Unit,
) : GraphEditorModel {
    private val graph = document.graph

    override val scene: GraphEditorScene = GraphEditorScene(
        boxes = nodes.boxes.mapValues { it.value.rect },
        titles = nodes.boxes.mapValues { it.value.kind.title() },
        reroutes = nodes.boxes.filterValues { it.isReroute }.keys,
        pins = nodes.pins,
        links = graph.links.mapNotNull { each ->
            val from = nodes.pins.firstOrNull { it.node == each.from && it.output && it.name == each.output }
                ?: return@mapNotNull null
            val to = nodes.pins.firstOrNull { it.node == each.to && !it.output && it.name == each.input }
                ?: return@mapNotNull null
            GraphLinkView(each, from, to, code.types.output(each.from, each.output)?.color() ?: GraphLinkColor)
        },
        frames = nodes.frames.mapNotNull { (id, rect) ->
            graph.group(id)?.let { GraphFrameView(id, it.title.ifBlank { graphEditorText("group") }, GraphGroupColors.of(it.color), rect) }
        },
        cards = nodes.cards.values.map { card ->
            GraphCardView(
                card.group.id, card.group.title.ifBlank { graphEditorText("group") }, GraphGroupColors.of(card.group.color),
                card.rect, card.inputs, card.outputs,
            )
        },
    )

    override val history: UndoHistory get() = document.history

    override fun beginGesture() = document.beginGesture()
    override fun endGesture() = document.endGesture()
    override fun cancelGesture() = document.cancelGesture()

    override fun positionOf(node: String) = document.graph.node(node)?.let { it.x to it.y }

    override fun moveNodes(positions: Map<String, Pair<Float, Float>>) = document.edit { it.withNodesAt(positions) }

    override fun pinColor(pin: GraphPin): UiColor = typeOf(pin)?.color() ?: GraphLinkColor

    fun typeOf(pin: GraphPin): ShaderType? = if (pin.output) code.types.output(pin.node, pin.name) else code.types.input(pin.node, pin.name)

    override fun connect(from: GraphPin, to: GraphPin) {
        val output = if (from.output) from else to
        val input = if (from.output) to else from
        document.edit { it.withLink(output.node, output.name, input.node, input.name) }
    }

    override fun connectToNode(pending: GraphPendingLink, node: String) {
        val box = nodes.boxes[node] ?: return
        if (pending.fromOutput) {
            val free = box.kind.inputs(box.node).filter { graph.linkInto(node, it.name) == null }
            val carried = carried(pending)
            val target = free.firstOrNull { it.type.fixed == carried } ?: free.firstOrNull { it.type.accepts(carried) } ?: return
            document.edit { it.withLink(pending.node, pending.pin, node, target.name) }
        } else {
            val output = intoType(pending)?.let(box.kind::outputFor) ?: return
            document.edit { it.withLink(node, output.name, pending.node, pending.pin) }
        }
    }

    override fun detach(input: GraphPin): GraphPin? {
        val existing = graph.linkInto(input.node, input.name) ?: return null
        document.edit { it.withoutLinkInto(input.node, input.name) }
        return scene.pins.firstOrNull { it.node == existing.from && it.output && it.name == existing.output }
    }

    private fun carried(pending: GraphPendingLink) = code.types.output(pending.node, pending.pin) ?: ShaderType.FLOAT

    private fun intoType(pending: GraphPendingLink) =
        graph.node(pending.node)?.let { owner -> ShaderNodeTypes.of(owner.type)?.input(owner, pending.pin)?.type }

    /** A new node at ([x], [y]), with what [then] makes of the graph in the same step back. */
    private fun add(
        kind: ShaderNodeType,
        x: Float,
        y: Float,
        then: ShaderGraph.(ShaderGraphNode) -> ShaderGraph = { this },
    ) {
        val node = ShaderGraphNode(document.graph.freeNodeId(kind.id), kind.id, x, y)
        document.edit { it.withNode(node).then(node) }
        select(GraphSelection.of(node.id))
    }

    /** The add menu, showing only what can take the link, and a reroute, which always can. */
    override fun offerNodes(pending: GraphPendingLink, x: Float, y: Float): List<UiDropdownItem> {
        val half = ShaderNodeLayout.REROUTE / 2f
        val rerouteKind = ShaderNodeTypes.of(ShaderNodeLibrary.REROUTE)
        val items = if (pending.fromOutput) {
            val carried = carried(pending)
            val reroute = graphRerouteItem {
                add(rerouteKind ?: return@graphRerouteItem, x - half, y - half) { node ->
                    withLink(pending.node, pending.pin, node.id, ShaderNodeLibrary.REROUTE_INPUT)
                }
            }
            listOf(reroute) + addNodeItems(graph.target, { it.inputFor(carried) != null }) { kind ->
                add(kind, x, y - ShaderNodeLayout.HEADER) { node ->
                    kind.inputFor(carried, node)?.let { pin -> withLink(pending.node, pending.pin, node.id, pin.name) } ?: this
                }
            }
        } else {
            val into = intoType(pending) ?: return emptyList()
            val reroute = graphRerouteItem {
                add(rerouteKind ?: return@graphRerouteItem, x - half, y - half) { node ->
                    withLink(node.id, ShaderNodeLibrary.REROUTE_OUTPUT, pending.node, pending.pin)
                }
            }
            listOf(reroute) + addNodeItems(graph.target, { it.outputFor(into) != null }) { kind ->
                add(kind, x - ShaderNodeLayout.WIDTH, y - ShaderNodeLayout.HEADER) { node ->
                    kind.outputFor(into)?.let { output -> withLink(node.id, output.name, pending.node, pending.pin) } ?: this
                }
            }
        }
        return items.mapIndexed { index, item -> if (index == 1) item.copy(separatorBefore = true) else item }
    }

    override fun isLinked(node: String): Boolean = document.graph.links.any { it.from == node || it.to == node }

    /** Whatever fed the link feeds the node, and the node feeds where the link went. */
    override fun insertIntoLink(link: Any, node: String) {
        val target = link as ShaderGraphLink
        val current = document.graph
        if (target !in current.links) return
        val placed = current.node(node) ?: return
        val kind = ShaderNodeTypes.of(placed.type) ?: return
        val input = kind.inputFor(code.types.output(target.from, target.output) ?: ShaderType.FLOAT, placed) ?: return
        val into = current.node(target.to)?.let { ShaderNodeTypes.of(it.type)?.input(it, target.input)?.type } ?: return
        val output = kind.outputFor(into) ?: return
        document.edit {
            it.withoutLink(target).withLink(target.from, target.output, node, input.name)
                .withLink(node, output.name, target.to, target.input)
        }
    }

    override fun removeLink(link: Any) = document.edit { it.withoutLink(link as ShaderGraphLink) }

    override fun addReroute(link: Any, x: Float, y: Float): GraphSelection {
        val id = graph.freeNodeId(ShaderNodeLibrary.REROUTE)
        val half = ShaderNodeLayout.REROUTE / 2f
        document.edit { it.withReroute(link as ShaderGraphLink, id, x - half, y - half) }
        return GraphSelection.of(id)
    }

    override fun removeNodes(nodes: Set<String>) = document.edit { it.withoutNodes(nodes) }

    override fun duplicate(nodes: Set<String>): Set<String> {
        val (next, copies) = document.graph.withDuplicates(nodes)
        document.edit { next }
        return copies.toSet()
    }

    override fun groupMembers(group: String): Set<String> =
        graph.group(group)?.nodes?.filter { graph.node(it) != null }?.toSet().orEmpty()

    override fun isGrouped(node: String): Boolean = graph.groupOf(node) != null

    override fun groupNodes(nodes: Set<String>): GraphSelection? {
        if (nodes.isEmpty()) return null
        val (next, id) = graph.withGroup(nodes, graphEditorText("group"))
        if (id.isEmpty()) return null
        document.edit { next }
        return GraphSelection(nodes, group = id)
    }

    /** Takes apart the group of [selection], or every group its nodes are in; the nodes stay selected. */
    override fun ungroup(selection: GraphSelection): GraphSelection? {
        val groups = selection.group?.let(::listOf) ?: selection.nodes.mapNotNull { graph.groupOf(it)?.id }.distinct()
        if (groups.isEmpty()) return null
        document.edit { graph -> groups.fold(graph) { current, id -> current.withoutGroup(id) } }
        return GraphSelection(selection.nodes)
    }

    override fun setMembership(nodes: Set<String>, group: String?) = document.edit { it.withMembership(nodes, group) }

    override fun removeGroup(group: String) = document.edit { it.withoutGroup(group) }

    override fun toggleGroup(group: String) =
        document.edit { graph -> graph.withGroupChanged(group) { it.copy(collapsed = !it.collapsed) } }

    override fun isCollapsed(group: String): Boolean = graph.group(group)?.collapsed == true

    override fun nodeItems(node: String): List<UiDropdownItem> {
        val box = nodes.boxes[node] ?: return emptyList()
        if (box.isReroute) return emptyList()
        val shown = box.kind.showsPreview(box.node)
        return listOf(
            UiDropdownItem(graphText("show_preview"), mark = UiMenuParts.check(shown)) {
                document.edit { it.withPreview(node, !shown) }
            },
        )
    }

    /** The output node stays. */
    override fun isRemovable(node: String): Boolean =
        graph.node(node)?.let { ShaderNodeTypes.of(it.type) }?.master == null

    override fun addItems(x: Float, y: Float): List<UiDropdownItem> = addNodeItems(graph.target) { kind -> add(kind, x, y) }
}
