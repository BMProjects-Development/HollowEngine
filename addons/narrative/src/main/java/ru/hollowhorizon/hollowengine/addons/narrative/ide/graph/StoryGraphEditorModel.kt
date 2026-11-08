package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.copyNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.newFunction
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.link
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.linkValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withGroup
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withGroupChanged
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withMembership
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withoutGroup
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCategories
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorState
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryInsight
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryNodeKind
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryUndo
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts

/**
 * The boxes of one analyzed graph, and what can be read off them wherever they stand. A node that stays where it was
 * keeps its box, so a drag recomposes only the nodes it moves.
 */
class StoryGraphBoxes(private val graph: StoryGraph, state: StoryEditorState) {
    private val layout = StoryNodeLayout(graph, state)
    private val boxes = HashMap<String, StoryNodeBox>()

    /** Boxes laid out only to tell what their pins carry. */
    private val detached = HashMap<String, StoryNodeBox>()

    /** The nodes whose types are being worked out, so that a cycle of generic blocks ends. */
    private val resolving = HashSet<String>()

    fun at(node: StoryNode, place: StoryPoint): StoryNodeBox =
        boxes[node.id]?.takeIf { it.x == place.x && it.y == place.y }
            ?: resolved(node.id) { layout.of(node, place) }.also { boxes[node.id] = it }

    /** The box [node] gets on its own, for one not in the graph yet; what its pins carry can be asked from then on. */
    fun of(node: StoryNode): StoryNodeBox =
        resolved(node.id) { layout.of(node, StoryPoint(0f, 0f)) }.also { boxes[node.id] = it }

    /** The box of [node] with the fields too rare to take up room on a node, for the inspector. */
    fun detailed(node: StoryNode): StoryNodeBox =
        resolved(node.id) { layout.of(node, StoryPoint(0f, 0f), detailed = true) }

    /** Lays [node] out with [lay], during which no lookup lays it out again. */
    private fun <T> resolved(node: String, lay: () -> T): T {
        val added = resolving.add(node)
        try {
            return lay()
        } finally {
            if (added) resolving.remove(node)
        }
    }

    /**
     * The box of [node] as the types of other nodes are worked out; null for one being worked out already. A box is
     * kept unless a cycle was cut while it was laid out, since such a box missed what the cut node carries.
     */
    private fun lookup(node: StoryNode): StoryNodeBox? {
        boxes[node.id]?.let { return it }
        detached[node.id]?.let { return it }
        if (node.id in resolving) {
            cuts++
            return null
        }
        val before = cuts
        val box = resolved(node.id) { layout.of(node, StoryPoint(0f, 0f)) }
        if (cuts == before) detached[node.id] = box
        return box
    }

    /** How many times a lookup met a node being worked out already. */
    private var cuts = 0

    fun typeOf(pin: GraphPin): StoryPinType {
        val box = boxes[pin.node] ?: return StoryPinType.ANY
        val node = box.node
        if (node is StoryNode.Reroute && !node.flow) return carried(node)?.type ?: StoryPinType.ANY
        return box.type(pin) ?: StoryPinType.ANY
    }

    /** The exact type of what [pin] carries, when that is known. */
    fun kotlinOf(pin: GraphPin): String? {
        val box = boxes[pin.node] ?: return null
        val node = box.node
        return if (node is StoryNode.Reroute && !node.flow) carried(node)?.kotlin else box.kotlin(pin)
    }

    /** The exact type the output [pin] of [node] gives, through reroutes too; null while it is being worked out. */
    fun outputKotlin(node: String, pin: String): String? {
        val found = graph.node(node) ?: return null
        if (found is StoryNode.Reroute) return carried(found)?.kotlin
        return lookup(found)?.kotlin(GraphPin(node, pin, true, 0f, 0f))
    }

    /** The exact type the input [pin] of [node] takes. */
    fun inputKotlin(node: String, pin: String): String? {
        val found = graph.node(node) ?: return null
        return lookup(found)?.kotlin(GraphPin(node, pin, false, 0f, 0f))
    }

    /** The pin the value passing through [reroute] comes from. */
    private fun carried(reroute: StoryNode.Reroute): StoryPinSpec? {
        val seen = HashSet<String>()
        var at = reroute.id
        while (seen.add(at)) {
            val link = graph.index.valuesInto(at).firstOrNull() ?: return null
            val box = boxes[link.from] ?: detached[link.from] ?: return null
            if (box.node !is StoryNode.Reroute) return box.rows.firstNotNullOfOrNull { row -> row.output?.takeIf { it.name == link.fromPin } }
            at = link.from
        }
        return null
    }

    fun isLinked(pin: GraphPin): Boolean = graph.links.any {
        if (pin.output) it.from == pin.node && it.fromPin == pin.name else it.to == pin.node && it.toPin == pin.name
    }

    fun tooltip(pin: GraphPin): String {
        val type = kotlinOf(pin)?.let(StoryPinType::shortName) ?: when (val type = typeOf(pin)) {
            StoryPinType.FLOW -> storyLang("pin.flow")
            StoryPinType.ANY, StoryPinType.OBJECT -> storyLang("pin.any")
            else -> type.label
        }
        val source = if (pin.output) null else graph.links.firstOrNull { it.to == pin.node && it.toPin == pin.name }
        val empty = source?.let { readsEmpty(GraphPin(it.from, it.fromPin, true, 0f, 0f), pin) } == true
        return if (empty) "$type\n${storyLang("pin.empty")}" else type
    }

    /**
     * Whether the link from [output] into [input] may carry null where none is taken: the story then stops with an
     * error, unless the value is checked or given a default first.
     */
    fun readsEmpty(output: GraphPin, input: GraphPin): Boolean {
        val from = kotlinOf(output) ?: return false
        val to = kotlinOf(input) ?: return false
        return from.endsWith("?") && !to.endsWith("?") && typeOf(input) != StoryPinType.ANY
    }
}

/** A story as [GraphEditorController] sees it, made anew for each graph drawn. */
internal class StoryGraphEditorModel(
    private val state: StoryGraphState,
    private val graph: StoryGraph,
    private val layout: StoryGraphBoxes,
    private val places: Map<String, StoryPoint>,
    private val select: (GraphSelection) -> Unit,
) : GraphEditorModel {
    /** Every node, those a collapsed group hides too. */
    val all: Map<String, StoryNodeBox> =
        graph.nodes.mapNotNull { node -> places[node.id]?.let { node.id to layout.at(node, it) } }.toMap()

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
        val pins = boxes.values.flatMap { it.shownPins } + cards.flatMap { it.pins }
        val byName = (boxes.values.flatMap { it.pins } + cards.flatMap { it.pins }).reversed()
            .associateBy { Triple(it.node, it.name, it.output) }
        val links = graph.links.mapNotNull { link ->
            val from = byName[Triple(link.from, link.fromPin, true)] ?: return@mapNotNull null
            val to = byName[Triple(link.to, link.toPin, false)] ?: return@mapNotNull null
            GraphLinkView(link, from, to, pinColor(from), dashed = layout.readsEmpty(from, to))
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

    fun typeOf(pin: GraphPin): StoryPinType = layout.typeOf(pin)

    override fun pinColor(pin: GraphPin): UiColor = UiColor.fromArgb(typeOf(pin).color)

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

    override fun cancelGesture() {
        state.moved = emptyMap()
        history?.rollback()
    }

    override fun positionOf(node: String): Pair<Float, Float>? = places[node]?.let { it.x to it.y }

    override fun moveNodes(positions: Map<String, Pair<Float, Float>>) {
        state.moved += positions.mapValues { (_, at) -> StoryPoint(at.first, at.second) }
    }

    /** Whether a link can go from [output] to [input], whichever way it gets made. */
    private fun takes(output: GraphPin, input: GraphPin): Boolean {
        val analysis = StoryInsight.analysis(state, state.root() ?: graph)
        return analysis.accepts(typeOf(output), layout.kotlinOf(output), typeOf(input), layout.kotlinOf(input))
    }

    override fun pinTooltip(pin: GraphPin): String = layout.tooltip(pin)

    /** A node is found by its title and by what its fields hold, also while it is collapsed. */
    override fun searchText(node: String): String {
        val box = all[node] ?: return ""
        return (listOf(box.title) + box.node.texts()).joinToString("\n")
    }

    override fun connect(from: GraphPin, to: GraphPin) {
        val output = if (from.output) from else to
        val input = if (from.output) to else from
        if (!takes(output, input)) return
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
        val target = box.shownPins.firstOrNull { pin ->
            pin.output != pending.fromOutput && (if (pin.output) takes(pin, own) else takes(own, pin)) && (pin.output || type == StoryPinType.FLOW || graph.links.none { it.to == node && it.toPin == pin.name })
        } ?: return
        connect(own, target)
    }

    /** The pin [pending] comes from, also one a folded section hides, as a link given up by its input can be. */
    private fun pinOf(pending: GraphPendingLink): GraphPin? {
        fun GraphPin.fits() = node == pending.node && name == pending.pin && output == pending.fromOutput
        return scene.pins.firstOrNull { it.fits() } ?: all[pending.node]?.pins?.firstOrNull { it.fits() }
    }

    /** A value input gives its link up to be dropped elsewhere; a flow input only the link it has alone. */
    override fun detach(input: GraphPin): GraphPin? {
        val into = graph.links.filter { it.to == input.node && it.toPin == input.name }
        val link = into.singleOrNull() ?: return null
        state.change(StoryUndo.REMOVE) { copy(links = links - link) }
        return all[link.from]?.pins?.firstOrNull { it.output && it.name == link.fromPin }
    }

    private fun add(node: StoryNode, x: Float, y: Float, then: StoryGraph.() -> StoryGraph = { this }) {
        state.change(StoryUndo.ADD) {
            copy(
                nodes = nodes + node, layout = layout + (node.id to StoryPoint(x, y))
            ).then()
        }
        select(GraphSelection.of(node.id))
    }

    private fun boxFor(node: StoryNode) = layout.of(node)

    /** Links [pending] to the pin of the new [node] that takes it, one of its very type before an untyped one. */
    private fun StoryGraph.linked(pending: GraphPendingLink, type: StoryPinType, node: StoryNode): StoryGraph {
        val box = boxFor(node)
        val candidates = box.shownPins.filter { linkable(pending, it) }
        val target = candidates.firstOrNull { box.type(it) == type } ?: candidates.firstOrNull() ?: return this
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

        val items = if (type == StoryPinType.FLOW) addStepItems(::placed) else valueItems(pending, type, ::placed)
        return listOf(reroute) + items.mapIndexed { index, item -> if (index == 0) item.copy(separatorBefore = true) else item }
    }

    /**
     * What a value link dragged from [pending] can go into, or come out of: blocks whose pins are of its very type,
     * commands among them when it carries a value out, and those that take or give a value of any type apart, after.
     */
    private fun valueItems(pending: GraphPendingLink, type: StoryPinType, add: (StoryNode) -> Unit): List<UiDropdownItem> {
        val carried = pinOf(pending)?.let(layout::kotlinOf)
        val analysis = StoryInsight.analysis(state, state.root() ?: graph)

        fun fits(other: StoryPinType, kotlin: String?): Boolean {
            return if (pending.fromOutput) analysis.accepts(type, carried, other, kotlin)
                else analysis.accepts(other, kotlin, type, carried)
        }

        fun exact(other: StoryPinType, kotlin: String?) = other != StoryPinType.ANY && fits(other, kotlin)
        fun inputKotlin(input: StoryOperatorInput) = input.shape?.starred ?: input.param?.type?.let(StoryKotlinTypes::name)

        val operator: (StoryOperator, Boolean) -> Boolean = { operator, strict ->
            val check = if (strict) ::exact else ::fits
            if (pending.fromOutput) operator.inputs.any { check(it.type, inputKotlin(it)) }
            else operator.outputs.any { check(it.type, it.kotlin) }
        }
        val strict = type == StoryPinType.ANY
        val typed = StoryCatalog.linkItems(
            commands = { signature ->
                pending.fromOutput && signature.params.any { exact(StoryPinType.of(it.type), it.type?.let(StoryKotlinTypes::name)) }
            },
            operators = { !TypeOperators.isGeneric(it.id) && operator(it, !strict) },
            add = add,
        )
        val loose = if (strict) emptyList() else StoryCatalog.operatorItems(
            accepts = { !TypeOperators.isTyped(it.id) && !operator(it, true) && operator(it, false) },
            add = add,
        )
        val anyType = loose.takeIf { it.isNotEmpty() }?.let {
            listOf(UiDropdownItem(storyLang("link.any"), mark = UiMenuParts.icon(StoryNodeKind.VALUE.icon), children = it, separatorBefore = true))
        }.orEmpty()
        return variableItems(add) { variable -> boxFor(variable).pins.any { linkable(pending, it) } } + typed + anyType
    }

    /** Whether [pin] of a new node could take the link [pending] is being dragged as. */
    private fun linkable(pending: GraphPendingLink, pin: GraphPin): Boolean {
        val own = pinOf(pending) ?: return false
        return pin.output != pending.fromOutput && (if (pin.output) takes(pin, own) else takes(own, pin))
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
        val from = GraphPin(link.from, link.fromPin, true, 0f, 0f)
        val into = GraphPin(link.to, link.toPin, false, 0f, 0f)
        val input = pins.firstOrNull { !it.output && takes(from, it) } ?: return
        val output = pins.firstOrNull { it.output && takes(it, into) } ?: return
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
        return addStepItems(::placed, values = true, create = functionItem(x, y).takeIf { state.scope == null })
    }

    /**
     * What can be added to the flow; with [values], the operators that read values too, and with [create], the item
     * that declares a new function, first among the functions.
     */
    private fun addStepItems(
        add: (StoryNode) -> Unit,
        values: Boolean = false,
        create: UiDropdownItem? = null,
    ): List<UiDropdownItem> = buildList {
        add(StoryCatalog.kindItem(storyLang("add.phrase"), StoryNodeKind.PHRASE) { add(StoryNode.Say(StoryGraph.newId())) })
        add(StoryCatalog.kindItem(storyLang("add.choice"), StoryNodeKind.CHOICE) {
            add(StoryNode.Choose(StoryGraph.newId(), listOf(StoryOption(storyLang("new.option")))))
        })
        add(StoryCatalog.kindItem(storyLang("add.condition"), StoryNodeKind.CONDITION) {
            add(StoryNode.Branch(StoryGraph.newId()))
        })
        add(StoryCatalog.kindItem(storyLang("add.loop"), StoryNodeKind.LOOP) { add(StoryNode.Loop(StoryGraph.newId())) })
        if (state.scope == null && graph.nodes.none { it is StoryNode.Start }) {
            add(StoryCatalog.kindItem(storyLang("add.start"), StoryNodeKind.START) { add(StoryNode.Start(StoryGraph.newId())) })
        }
        if (state.scope != null) {
            add(StoryCatalog.kindItem(storyLang("add.return"), StoryNodeKind.RETURN) { add(StoryNode.Return(StoryGraph.newId())) })
        }
        state.function()?.flows.orEmpty().forEach { flow ->
            add(StoryCatalog.kindItem(storyLang("add.signal", flow), StoryNodeKind.SIGNAL) { add(StoryNode.Signal(StoryGraph.newId(), flow)) })
        }
        addAll(variableItems(add))
        StoryFunctionItems.of(state, add, create)?.let(::add)
        addAll(StoryCatalog.items(add, values))
    }

    /** A function and what it holds: only the story itself declares them, so none is offered inside another. */
    private fun functionItem(x: Float, y: Float) = StoryCatalog.kindItem(storyLang("add.function"), StoryNodeKind.FUNCTION) {
        add(graph.newFunction(), x, y)
    }

    /** A double click on a function goes inside it, and so does one on a call of a function of this story. */
    override fun openNode(node: String): Boolean {
        state.scope = graph.node(node)?.let(state::functionInside) ?: return false
        return true
    }

    override fun nodeItems(node: String): List<UiDropdownItem> {
        val target = graph.node(node)?.let(state::functionInside) ?: return emptyList()
        return listOf(UiDropdownItem(storyLang("function.enter"), mark = UiMenuParts.icon(ENTER_ICON)) { state.scope = target })
    }

    /** The parameters of a function are where it starts, so a body never goes without them. */
    override fun isRemovable(node: String): Boolean = graph.node(node) !is StoryNode.Parameters

    /** Reading and writing the variables of the story, those of the nodes [accepts] lets through. */
    private fun variableItems(add: (StoryNode) -> Unit, accepts: (StoryNode) -> Boolean = { true }): List<UiDropdownItem> {
        val children = graph.variables.flatMap { variable ->
            listOf(
                StoryNode.Expression(StoryGraph.newId(), variable.name) to storyLang("add.variable.read").format(variable.name),
                StoryNode.Assign(StoryGraph.newId(), variable.name) to storyLang("add.variable.write").format(variable.name),
            )
        }.filter { (node, _) -> accepts(node) }.map { (node, label) ->
            val kind = if (node is StoryNode.Assign) StoryNodeKind.SET else StoryNodeKind.VALUE
            StoryCatalog.kindItem(label, kind) { add(node) }
        }
        if (children.isEmpty()) return emptyList()
        val heading = StoryCategories.PREFIX + "variables"
        return listOf(UiDropdownItem(
            storyLang("add.variable"),
            mark = UiMenuParts.icon(StoryCategories.icon(heading), StoryCategories.tint(StoryCategories.color(heading))),
            children = children,
        ))
    }

    private companion object {
        const val COPY_OFFSET = 24f
        const val ENTER_ICON = "hollowengine:textures/gui/icons/graph/enter.svg"
    }
}

/** What [this] holds in words, whether its node shows it or is collapsed. */
private fun StoryNode.texts(): List<String> = when (this) {
    is StoryNode.Start, is StoryNode.Parameters -> emptyList()
    is StoryNode.Function -> listOf(name) + (params + results).map { it.name } + flows
    is StoryNode.Signal -> listOf(flow)
    is StoryNode.Return -> values.values.toList()
    is StoryNode.Invoke -> listOf(function) + arguments.values
    is StoryNode.Say -> listOf(speaker, text)
    is StoryNode.Call -> arguments.values + groups.values.flatMap { it.arguments.values }
    is StoryNode.Choose -> options.flatMap { listOfNotNull(it.text, it.condition) }
    is StoryNode.Branch -> listOf(condition)
    is StoryNode.Assign -> listOf(variable, value)
    is StoryNode.Code -> listOf(code)
    is StoryNode.Expression -> listOf(code)
    is StoryNode.Pure -> arguments.values.toList()
    is StoryNode.Loop -> listOf(items)
    is StoryNode.Reroute -> emptyList()
}
