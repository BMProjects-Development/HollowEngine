package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.copyNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.link
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.linkValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withGroup
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withGroupChanged
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withMembership
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withoutGroup
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryUndo
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyIcon
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem

/** A story as [GraphEditorController] sees it, made anew for each graph drawn. */
internal class StoryGraphEditorModel(
    private val state: StoryGraphState,
    private val graph: StoryGraph,
    private val places: Map<String, StoryPoint>,
    private val select: (GraphSelection) -> Unit,
) : GraphEditorModel {
    private val layout = StoryNodeLayout(graph, state)

    /** Every node, those a collapsed group hides too. */
    val all: Map<String, StoryNodeBox> =
        graph.nodes.mapNotNull { node -> places[node.id]?.let { node.id to layout.of(node, it) } }.toMap()

    private val hidden = graph.groups.filter { it.collapsed }.flatMap { it.nodes }.toSet()
    val boxes: Map<String, StoryNodeBox> = all.filterKeys { it !in hidden }

    override val scene: GraphEditorScene = scene()
    override val history: UndoHistory? get() = state.context?.history

    private fun scene(): GraphEditorScene {
        val frames = ArrayList<GraphFrameView>()
        val cards = ArrayList<GraphCardView>()
        graph.groups.forEach { group ->
            val members = group.nodes.filter { it in all }.toSet()
            val around = GraphRect.around(members.map { all.getValue(it).rect }) ?: return@forEach
            val title = group.title.ifBlank { graphEditorText("group") }
            val color = GraphGroupColors.of(group.color)
            if (group.collapsed) {
                val entering =
                    graph.links.filter { it.to in members && it.from !in members }.map { it.to to it.toPin }.distinct()
                val leaving =
                    graph.links.filter { it.from in members && it.to !in members }.map { it.from to it.fromPin }
                        .distinct()
                cards += GraphCardView.of(group.id, title, color, around, entering, leaving)
            } else {
                val frame = around.expanded(GROUP_PADDING, GROUP_HEADER + GROUP_PADDING, GROUP_PADDING, GROUP_PADDING)
                frames += GraphFrameView(group.id, title, color, frame)
            }
        }
        val pins = boxes.values.flatMap { it.pins } + cards.flatMap { it.pins }
        val byName = pins.reversed().associateBy { Triple(it.node, it.name, it.output) }
        val links = graph.links.mapNotNull { link ->
            val from = byName[Triple(link.from, link.fromPin, true)] ?: return@mapNotNull null
            val to = byName[Triple(link.to, link.toPin, false)] ?: return@mapNotNull null
            GraphLinkView(link, from, to, pinColor(from))
        }
        val reroutes = boxes.filterValues { it.node is StoryNode.Reroute }.keys
        return GraphEditorScene(
            boxes.mapValues { it.value.rect },
            boxes.mapValues { it.value.title },
            pins,
            links,
            frames,
            cards,
            reroutes
        )
    }

    fun typeOf(pin: GraphPin): StoryPinType {
        val box = all[pin.node] ?: return StoryPinType.ANY
        val node = box.node
        if (node is StoryNode.Reroute && !node.flow) return carried(node)
        return box.type(pin) ?: StoryPinType.ANY
    }

    /** What the value passing through [reroute] is, by the pin it comes from. */
    private fun carried(reroute: StoryNode.Reroute): StoryPinType {
        val seen = HashSet<String>()
        var at = reroute.id
        while (seen.add(at)) {
            val link = graph.index.valuesInto(at).firstOrNull() ?: return StoryPinType.ANY
            val box = all[link.from] ?: return StoryPinType.ANY
            if (box.node !is StoryNode.Reroute) return box.rows.firstNotNullOfOrNull { row -> row.output?.takeIf { it.name == link.fromPin } }?.type
                ?: StoryPinType.ANY
            at = link.from
        }
        return StoryPinType.ANY
    }

    fun isPinLinked(pin: GraphPin): Boolean = graph.links.any {
        if (pin.output) it.from == pin.node && it.fromPin == pin.name else it.to == pin.node && it.toPin == pin.name
    }

    override fun pinColor(pin: GraphPin): UiColor = typeOf(pin).color()

    override fun beginGesture() {
        history?.begin()
    }

    /** A drag writes its places to the file only here, since every write parses the story anew. */
    override fun endGesture() {
        val moved = state.moved
        if (moved.isNotEmpty()) {
            state.change(StoryUndo.MOVE) { copy(layout = layout + places + moved) }
            state.moved = emptyMap()
        }
        history?.commit()
    }

    override fun positionOf(node: String): Pair<Float, Float>? = places[node]?.let { it.x to it.y }

    override fun moveNodes(positions: Map<String, Pair<Float, Float>>) {
        state.moved += positions.mapValues { (_, at) -> StoryPoint(at.first, at.second) }
    }

    private fun takes(output: StoryPinType, input: StoryPinType) = output.fits(input)

    override fun connect(from: GraphPin, to: GraphPin) {
        val output = if (from.output) from else to
        val input = if (from.output) to else from
        if (!takes(typeOf(output), typeOf(input))) return
        if (typeOf(output) == StoryPinType.FLOW) {
            state.change(StoryUndo.LINK) { link(output.node, output.name, input.node) }
        } else if (!readsFrom(output.node, input.node)) {
            state.change(StoryUndo.LINK) { linkValue(output.node, output.name, input.node, input.name) }
        }
    }

    /** Whether the value of [node] is worked out from that of [target]: a link from it into [target] would be a cycle. */
    private fun readsFrom(node: String, target: String): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(node))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at == target) return true
            if (seen.add(at)) queue += graph.index.valuesInto(at).map { it.from }
        }
        return false
    }

    override fun connectToNode(pending: GraphPendingLink, node: String) {
        val own = pinOf(pending) ?: return
        val type = typeOf(own)
        val box = boxes[node] ?: return
        val target = box.pins.firstOrNull { pin ->
            pin.output != pending.fromOutput && (if (pin.output) takes(typeOf(pin), type) else takes(
                type, typeOf(pin)
            )) && (pin.output || type == StoryPinType.FLOW || graph.links.none { it.to == node && it.toPin == pin.name })
        } ?: return
        connect(own, target)
    }

    private fun pinOf(pending: GraphPendingLink) =
        scene.pins.firstOrNull { it.node == pending.node && it.name == pending.pin && it.output == pending.fromOutput }

    /** A value input gives its link up to be dropped elsewhere; a flow input only the link it has alone. */
    override fun detach(input: GraphPin): GraphPin? {
        val into = graph.links.filter { it.to == input.node && it.toPin == input.name }
        val link = into.singleOrNull() ?: return null
        state.change(StoryUndo.REMOVE) { copy(links = links - link) }
        return scene.pins.firstOrNull { it.node == link.from && it.output && it.name == link.fromPin }
    }

    private fun add(node: StoryNode, x: Float, y: Float, then: StoryGraph.() -> StoryGraph = { this }) {
        state.change(StoryUndo.ADD) {
            copy(
                nodes = nodes + node, layout = layout + (node.id to StoryPoint(x, y))
            ).then()
        }
        select(GraphSelection.of(node.id))
    }

    private fun boxFor(node: StoryNode) = layout.of(node, StoryPoint(0f, 0f))

    /** Links [pending] to the first pin of the new [node] that takes it. */
    private fun StoryGraph.linked(pending: GraphPendingLink, type: StoryPinType, node: StoryNode): StoryGraph {
        val pins = boxFor(node).let { box -> box.pins.map { it to box.type(it) } }
        val target = pins.firstOrNull { (pin, carried) ->
            pin.output != pending.fromOutput && carried != null && (if (pin.output) carried.fits(type) else type.fits(
                carried
            ))
        }?.first ?: return this
        return when {
            type == StoryPinType.FLOW && pending.fromOutput -> link(pending.node, pending.pin, node.id)
            type == StoryPinType.FLOW -> link(node.id, target.name, pending.node)
            pending.fromOutput -> linkValue(pending.node, pending.pin, node.id, target.name)
            else -> linkValue(node.id, target.name, pending.node, pending.pin)
        }
    }

    override fun offerNodes(pending: GraphPendingLink, x: Float, y: Float): List<UiDropdownItem> {
        val type = pinOf(pending)?.let(::typeOf) ?: return emptyList()
        val half = GraphNodeMetrics.REROUTE / 2f
        val reroute = graphRerouteItem {
            val node = StoryNode.Reroute(StoryGraph.newId(), type == StoryPinType.FLOW)
            add(node, x - half, y - half) { linked(pending, type, node) }
        }

        fun placed(node: StoryNode) {
            val left = if (pending.fromOutput) x else x - boxFor(node).width
            add(node, left, y - GraphNodeMetrics.HEADER) { linked(pending, type, node) }
        }

        val items =
            if (type == StoryPinType.FLOW) addStepItems(::placed) else variableItems(::placed) + StoryCatalog.operatorItems(
                accepts = { operator ->
                    if (pending.fromOutput) operator.inputs.any { type.fits(it.type) } else operator.outputs.any {
                        it.type.fits(
                            type
                        )
                    }
                },
                add = ::placed,
            )
        return listOf(reroute) + items.mapIndexed { index, item -> if (index == 0) item.copy(separatorBefore = true) else item }
    }

    override fun isLinked(node: String): Boolean = graph.links.any { it.from == node || it.to == node }

    override fun insertIntoLink(link: Any, node: String) {
        link as StoryLink
        val box = all[node] ?: return
        val pins = box.pins
        if (link.isFlow) {
            val output = pins.firstOrNull { it.output && box.type(it) == StoryPinType.FLOW } ?: return
            if (pins.none { !it.output && it.name == StoryPins.IN }) return
            state.change(StoryUndo.LINK) {
                copy(links = links - link).link(link.from, link.fromPin, node).link(node, output.name, link.to)
            }
            return
        }
        val carried = typeOf(GraphPin(link.from, link.fromPin, true, 0f, 0f))
        val into = typeOf(GraphPin(link.to, link.toPin, false, 0f, 0f))
        val input = pins.firstOrNull { !it.output && carried.fits(box.type(it) ?: StoryPinType.FLOW) } ?: return
        val output = pins.firstOrNull { it.output && (box.type(it) ?: StoryPinType.FLOW).fits(into) } ?: return
        state.change(StoryUndo.LINK) {
            copy(links = links - link).linkValue(link.from, link.fromPin, node, input.name)
                .linkValue(node, output.name, link.to, link.toPin)
        }
    }

    override fun removeLink(link: Any) = state.change(StoryUndo.REMOVE) { copy(links = links - (link as StoryLink)) }

    override fun addReroute(link: Any, x: Float, y: Float): GraphSelection {
        link as StoryLink
        val node = StoryNode.Reroute(StoryGraph.newId(), link.isFlow)
        val half = GraphNodeMetrics.REROUTE / 2f
        state.change(StoryUndo.ADD) {
            copy(
                nodes = nodes + node,
                layout = layout + (node.id to StoryPoint(x - half, y - half)),
                links = links - link + link.copy(to = node.id, toPin = node.input) + StoryLink(
                    node.id, node.output, link.to, link.toPin
                ),
            )
        }
        return GraphSelection.of(node.id)
    }

    override fun removeNodes(nodes: Set<String>) = state.change(StoryUndo.REMOVE) { removeNodes(nodes) }

    override fun duplicate(nodes: Set<String>): Set<String> {
        var fresh = emptyMap<String, String>()
        state.change(StoryUndo.DUPLICATE) {
            val placed = copy(layout = layout + places.filterKeys { it in nodes })
            val (next, copies) = placed.copyNodes(nodes, StoryPoint(COPY_OFFSET, COPY_OFFSET))
            fresh = copies
            next
        }
        return fresh.values.toSet()
    }

    override fun groupMembers(group: String): Set<String> =
        graph.groups.firstOrNull { it.id == group }?.nodes?.filter { it in all }?.toSet().orEmpty()

    override fun isGrouped(node: String): Boolean = graph.groups.any { node in it.nodes }

    override fun groupNodes(nodes: Set<String>): GraphSelection? {
        if (nodes.isEmpty()) return null
        var id = ""
        state.change(StoryUndo.EDIT) {
            val (next, made) = withGroup(nodes, graphEditorText("group"))
            id = made
            next
        }
        return GraphSelection(nodes, group = id)
    }

    override fun ungroup(selection: GraphSelection): GraphSelection? {
        val found =
            selection.group?.let(::listOf) ?: graph.groups.filter { group -> selection.nodes.any { it in group.nodes } }
                .map { it.id }
        if (found.isEmpty()) return null
        state.change(StoryUndo.EDIT) { found.fold(this) { graph, id -> graph.withoutGroup(id) } }
        return GraphSelection(selection.nodes)
    }

    override fun setMembership(nodes: Set<String>, group: String?) =
        state.change(StoryUndo.EDIT) { withMembership(nodes, group) }

    override fun removeGroup(group: String) = state.change(StoryUndo.REMOVE) { withoutGroup(group) }

    override fun toggleGroup(group: String) =
        state.change(StoryUndo.EDIT) { withGroupChanged(group) { it.copy(collapsed = !it.collapsed) } }

    override fun isCollapsed(group: String): Boolean = graph.groups.any { it.id == group && it.collapsed }

    override fun addItems(x: Float, y: Float): List<UiDropdownItem> {
        fun placed(node: StoryNode) = add(node, x, y)
        return addStepItems(::placed) + UiDropdownItem(
            storyLang("add.values"), children = StoryCatalog.operatorItems(add = ::placed),
        ).let(::listOf)
    }

    /** What can be added to the flow. */
    private fun addStepItems(add: (StoryNode) -> Unit): List<UiDropdownItem> = buildList {
        add(
            UiDropdownItem(
                storyLang("add.phrase"), icon = kindIcon("phrase")
            ) { add(StoryNode.Say(StoryGraph.newId())) })
        add(UiDropdownItem(storyLang("add.choice"), icon = kindIcon("choice")) {
            add(StoryNode.Choose(StoryGraph.newId(), listOf(StoryOption(storyLang("new.option")))))
        })
        add(
            UiDropdownItem(
                storyLang("add.condition"), icon = kindIcon("condition")
            ) { add(StoryNode.Branch(StoryGraph.newId())) })
        add(UiDropdownItem(storyLang("add.loop"), icon = kindIcon("block")) { add(StoryNode.Loop(StoryGraph.newId())) })
        add(UiDropdownItem(storyLang("add.start"), icon = kindIcon("label")) {
            add(StoryNode.Start(StoryGraph.newId(), freeStartName()))
        })
        addAll(variableItems(add))
        add(UiDropdownItem(storyLang("add.expression")) { add(StoryNode.Expression(StoryGraph.newId(), "")) })
        addAll(StoryCatalog.items(add))
    }

    /** Reading and writing the variables of the story. */
    private fun variableItems(add: (StoryNode) -> Unit): List<UiDropdownItem> {
        if (graph.variables.isEmpty()) return emptyList()
        return listOf(UiDropdownItem(storyLang("add.variable"), children = graph.variables.flatMap { variable ->
            listOf(
                UiDropdownItem(storyLang("add.variable.read").format(variable.name)) {
                    add(StoryNode.Expression(StoryGraph.newId(), variable.name))
                },
                UiDropdownItem(storyLang("add.variable.write").format(variable.name)) {
                    add(StoryNode.Assign(StoryGraph.newId(), variable.name))
                },
            )
        }))
    }

    private fun kindIcon(name: String) = storyIcon(name)

    private fun freeStartName(): String {
        val taken = graph.nodes.filterIsInstance<StoryNode.Start>().mapTo(HashSet()) { it.name }
        return generateSequence(1) { it + 1 }.map { "scene$it" }.first { it !in taken }
    }

    private companion object {
        const val COPY_OFFSET = 24f
    }
}
