package ru.hollowhorizon.hollowengine.client.ui.graph

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoKeys
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import kotlin.math.hypot

/**
 * What every node graph does under the pointer and the keys: picking and dragging nodes, drawing links, dropping a node
 * into a link, groups, reroutes and menus. [GraphEditorModel] answers for the graph, which is replaced as it recomposes.
 */
class GraphEditorController(val canvasId: String, val state: GraphEditorState) {
    private var current by mutableStateOf<GraphEditorModel?>(null)

    /** The graph as it is now. It is state, since the canvas recomposes apart from what draws the nodes. */
    var model: GraphEditorModel
        get() = checkNotNull(current)
        set(value) {
            current = value
        }

    /** Where the canvas is on the screen. */
    var canvas by mutableStateOf(UiRect.Zero)

    /** The node with a field being typed in. */
    var editing by mutableStateOf<String?>(null)

    /**
     * The nodes worth building: those in sight, and those an action in progress would be lost along with. A node out of
     * sight costs nothing then, neither to compose and lay out nor to draw, and the links and the minimap do not need it.
     */
    val shown: Set<String> by derivedStateOf {
        val size = canvas
        val selected = selection
        model.scene.boxes.filter { (node, rect) ->
            size.width <= 0f || node in selected || node == link?.node || node == editing ||
                    view.sees(rect, size.width, size.height, SIGHT_MARGIN)
        }.keys
    }

    var link by mutableStateOf<GraphPendingLink?>(null)
        private set
    var menu by mutableStateOf<GraphMenu?>(null)
    var insertInto by mutableStateOf<Any?>(null)
        private set

    private val dragOrigins = HashMap<String, Pair<Float, Float>>()

    /** The node whose corner lands on the grid while a drag snaps; the rest keep their distance to it. */
    private var dragAnchor: String? = null

    /** The links as they lie on the canvas now, by key. */
    var curves: List<Pair<Any, GraphCurve>> = emptyList()

    val view: GraphViewState get() = state.view
    val selection: GraphSelection get() = state.selection
    private val scene: GraphEditorScene get() = model.scene

    fun select(next: GraphSelection?) {
        if (next != null) state.selection = next
    }

    private fun pinAt(x: Float, y: Float, outputs: Boolean): GraphPin? =
        scene.pins.filter { it.output == outputs }.map { it to hypot(view.toCanvasX(it.x) - x, view.toCanvasY(it.y) - y) }
            .filter { it.second <= PIN_REACH * view.zoom.coerceAtLeast(1f) }.minByOrNull { it.second }?.first

    private fun nodeAt(x: Float, y: Float): String? =
        scene.boxes.entries.lastOrNull { it.value.toCanvas(view).contains(x, y) }?.key

    private fun cardAt(x: Float, y: Float): GraphCardView? =
        scene.cards.lastOrNull { it.rect.toCanvas(view).contains(x, y) }

    /** Ends a link: on a pin it links there, on a node to its first pin that takes it, on nothing it offers nodes. */
    fun finishLink() {
        val pending = link ?: return
        link = null
        val pin = pinAt(pending.x, pending.y, outputs = !pending.fromOutput)
        val node = nodeAt(pending.x, pending.y)?.takeIf { it != pending.node }
        val own = scene.pins.firstOrNull { it.node == pending.node && it.name == pending.pin && it.output == pending.fromOutput }
        when {
            pin != null && own != null -> model.connect(own, pin)
            node != null -> model.connectToNode(pending, node)
            cardAt(pending.x, pending.y) != null -> Unit
            else -> {
                val items = model.offerNodes(pending, view.toGraphX(pending.x), view.toGraphY(pending.y))
                menu = GraphMenu(pending.screenX, pending.screenY, items)
            }
        }
        model.endGesture()
    }

    /** Ends a drag of nodes: one dropped on a link goes into it, and the whole drag is one step back. */
    fun finishDrag() {
        if (dragOrigins.isEmpty()) return
        insertInto?.let { link -> dragOrigins.keys.singleOrNull()?.let { model.insertIntoLink(link, it) } }
        insertInto = null
        joinFrame()
        dragOrigins.clear()
        model.endGesture()
    }

    /**
     * Nodes that were in no group and have been dragged into the frame of one join it, the way they would be put in a
     * box. A frame fits itself around its nodes, so a node leaves it by the node's menu instead.
     */
    private fun joinFrame() {
        val loose = dragOrigins.keys.filter { id ->
            !model.isGrouped(id) && dragOrigins.getValue(id) != model.positionOf(id)
        }
        if (loose.isEmpty()) return
        val target = scene.frames.firstOrNull { frame ->
            loose.all { id -> scene.boxes[id]?.let { frame.rect.contains(it.centerX, it.centerY) } == true }
        } ?: return
        model.setMembership(loose.toSet(), target.id)
    }

    private fun beginDrag(anchor: String?) {
        dragOrigins.clear()
        selection.nodes.forEach { id -> model.positionOf(id)?.let { dragOrigins[id] = it } }
        dragAnchor = anchor
        model.beginGesture()
    }

    /** A click with Shift or Control adds to the selection and takes away what was in it. */
    fun pressNode(node: String, gesture: GraphNodeGesture) {
        menu = null
        when {
            gesture.shift || gesture.control -> {
                val nodes = if (node in selection) selection.nodes - node else selection.nodes + node
                select(GraphSelection(nodes))
            }

            node !in selection || selection.group != null -> select(GraphSelection.of(node))
        }
        beginDrag(node)
    }

    fun pressGroup(group: String, gesture: GraphNodeGesture) {
        menu = null
        val members = model.groupMembers(group)
        when {
            gesture.shift || gesture.control -> {
                val nodes = if (members.all { it in selection }) selection.nodes - members else selection.nodes + members
                select(GraphSelection(nodes))
            }

            selection.group != group -> select(GraphSelection(members, group = group))
        }
        beginDrag(members.firstOrNull())
    }

    fun dragNode(gesture: GraphNodeGesture) {
        if (link != null || dragOrigins.isEmpty()) return
        val anchor = dragAnchor?.let(dragOrigins::get) ?: dragOrigins.values.first()
        val dx = GraphPreferences.place(anchor.first + gesture.graphDeltaX) - anchor.first
        val dy = GraphPreferences.place(anchor.second + gesture.graphDeltaY) - anchor.second
        model.moveNodes(dragOrigins.mapValues { (_, at) -> at.first + dx to at.second + dy })
        val lone = dragOrigins.keys.singleOrNull()?.takeIf { !model.isLinked(it) }
        insertInto = lone?.let { GraphCurves.nearest(curves, gesture.canvasX, gesture.canvasY, INSERT_REACH) }
    }

    fun releaseNode() = finishDrag()

    fun pressPin(pin: GraphPin, screenX: Float, screenY: Float) {
        menu = null
        model.beginGesture()
        val x = view.toCanvasX(pin.x)
        val y = view.toCanvasY(pin.y)
        val source = if (pin.output) null else model.detach(pin)
        link = when {
            pin.output -> GraphPendingLink(pin.node, pin.name, true, x, y, screenX, screenY)
            source != null -> GraphPendingLink(source.node, source.name, true, x, y, screenX, screenY)
            else -> GraphPendingLink(pin.node, pin.name, false, x, y, screenX, screenY)
        }
    }

    fun dragPin(canvasX: Float, canvasY: Float, screenX: Float, screenY: Float) {
        link = link?.copy(x = canvasX, y = canvasY, screenX = screenX, screenY = screenY)
    }

    fun releasePin() = finishLink()

    /** A group taken by its title bar leaves the nodes; the nodes themselves go, those that cannot excepted. */
    fun deleteSelection() {
        val link = selection.link
        val group = selection.group
        when {
            link != null -> model.removeLink(link)
            group != null -> model.removeGroup(group)
            else -> {
                val removable = selection.nodes.filter(model::isRemovable).toSet()
                if (removable.isEmpty()) return
                model.removeNodes(removable)
            }
        }
        select(GraphSelection.None)
    }

    fun duplicateSelection() {
        val copied = selection.nodes.filter(model::isRemovable).toSet()
        if (copied.isNotEmpty()) select(GraphSelection(model.duplicate(copied)))
    }

    private fun deleteGroupWithNodes(group: String) {
        val removable = model.groupMembers(group).filter(model::isRemovable).toSet()
        model.beginGesture()
        model.removeGroup(group)
        model.removeNodes(removable)
        model.endGesture()
        select(GraphSelection.None)
    }

    /** Selects what [area] of the graph touches, on top of the selection with Shift or Control. */
    fun selectArea(area: GraphRect, modifiers: Int) {
        val covered = scene.boxes.filterValues { it.intersects(area) }.keys +
                scene.cards.filter { it.rect.intersects(area) }.flatMap { model.groupMembers(it.id) }
        val additive = modifiers and (GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL) != 0
        select(GraphSelection(if (additive) selection.nodes + covered else covered))
    }

    fun handleKey(input: UiKeyInput): Boolean {
        when {
            HollowKeys.DELETE.matches(input) -> deleteSelection()
            HollowKeys.DUPLICATE.matches(input) -> duplicateSelection()
            HollowKeys.SELECT_ALL.matches(input) -> select(GraphSelection(scene.boxes.keys.toSet()))
            HollowKeys.GROUP.matches(input) -> select(model.groupNodes(selection.nodes))
            HollowKeys.UNGROUP.matches(input) -> select(model.ungroup(selection))
            model.history?.let { UndoKeys.handle(it, input.key, input.modifiers) } == true -> Unit
            input.key == GLFW.GLFW_KEY_ESCAPE -> if (link != null) {
                link = null
                model.endGesture()
            } else {
                select(GraphSelection.None)
            }

            else -> return false
        }
        return true
    }

    fun nodeMenu(node: String, screenX: Float, screenY: Float) {
        if (node !in selection) select(GraphSelection.of(node))
        val chosen = selection
        val own = model.nodeItems(node)
        val edits = if (!model.isRemovable(node)) emptyList() else listOf(
            UiDropdownItem(graphEditorText("duplicate"), shortcut = HollowKeys.DUPLICATE.shortcut, onClick = ::duplicateSelection),
            UiDropdownItem(graphEditorText("delete_node"), shortcut = HollowKeys.DELETE.shortcut, onClick = ::deleteSelection),
        )
        menu = GraphMenu(screenX, screenY, joinItemGroups(own, edits, organizeItems(chosen)))
    }

    fun groupMenu(group: String, screenX: Float, screenY: Float) {
        val members = model.groupMembers(group)
        if (selection.group != group) select(GraphSelection(members, group = group))
        val collapsed = model.isCollapsed(group)
        menu = GraphMenu(
            screenX, screenY,
            listOfNotNull(
                UiDropdownItem(graphEditorText(if (collapsed) "expand_group" else "collapse_group")) { model.toggleGroup(group) },
                UiDropdownItem(
                    graphEditorText("ungroup"), icon = UngroupIcon, shortcut = HollowKeys.UNGROUP.shortcut,
                ) { select(model.ungroup(GraphSelection(members, group = group))) },
                if (collapsed) null else arrangeItem(members),
                UiDropdownItem(graphEditorText("delete_group_nodes"), separatorBefore = true) { deleteGroupWithNodes(group) },
            ),
        )
    }

    fun linkMenu(link: Any, pointer: GraphPointer) {
        select(GraphSelection.ofLink(link))
        menu = GraphMenu(
            pointer.screenX, pointer.screenY,
            listOf(
                graphRerouteItem { select(model.addReroute(link, pointer.graphX, pointer.graphY)) },
                UiDropdownItem(graphEditorText("delete_link"), shortcut = HollowKeys.DELETE.shortcut) {
                    model.removeLink(link)
                    select(GraphSelection.None)
                },
            ),
        )
    }

    fun backgroundMenu(pointer: GraphPointer) {
        menu = GraphMenu(pointer.screenX, pointer.screenY, model.addItems(pointer.graphX, pointer.graphY) + graphViewItems())
    }

    /** Grouping the selection, taking it out of its groups, and lining it up. */
    private fun organizeItems(chosen: GraphSelection): List<UiDropdownItem> {
        val nodes = chosen.nodes
        val grouped = nodes.any(model::isGrouped)
        return listOfNotNull(
            UiDropdownItem(
                graphEditorText("group_nodes"), icon = GroupIcon, shortcut = HollowKeys.GROUP.shortcut,
            ) { select(model.groupNodes(nodes)) }.takeIf { nodes.isNotEmpty() },
            UiDropdownItem(graphEditorText("leave_group")) { model.setMembership(nodes, null) }.takeIf { grouped },
            UiDropdownItem(
                graphEditorText("ungroup"), icon = UngroupIcon, shortcut = HollowKeys.UNGROUP.shortcut,
            ) { select(model.ungroup(chosen)) }.takeIf { grouped },
            arrangeItem(nodes),
        )
    }

    private fun arrangeItem(ids: Collection<String>): UiDropdownItem? {
        val boxes: Map<String, GraphRect> = ids.mapNotNull { id -> scene.boxes[id]?.let { id to it } }.toMap()
        fun move(to: Map<String, Pair<Float, Float>>) {
            if (to.isEmpty()) return
            model.beginGesture()
            model.moveNodes(to)
            model.endGesture()
        }
        return graphArrangeItem(
            boxes.size,
            onAlign = { alignment -> move(GraphArrange.align(boxes, alignment)) },
            onDistribute = { horizontal -> move(GraphArrange.distribute(boxes, horizontal)) },
        )
    }

    private fun joinItemGroups(vararg groups: List<UiDropdownItem>): List<UiDropdownItem> = buildList {
        groups.filter { it.isNotEmpty() }.forEach { group ->
            group.forEachIndexed { index, item ->
                add(if (index == 0 && isNotEmpty()) item.copy(separatorBefore = true) else item)
            }
        }
    }

    private companion object {
        /** How far from a pin, in canvas pixels at zoom 1, a link can be dropped and still land on it. */
        const val PIN_REACH = 12f

        /** How close to a link, in canvas pixels, a lone node has to be dragged to go into it. */
        const val INSERT_REACH = 10f

        /** How far past the canvas, in canvas pixels, nodes stay built: shadows and pins reach out of a node. */
        const val SIGHT_MARGIN = 96f

        const val GroupIcon = "hollowengine:textures/gui/icons/actions/group.svg"
        const val UngroupIcon = "hollowengine:textures/gui/icons/actions/ungroup.svg"
    }
}

/** The item that puts a reroute where a link was dropped, or on the link that was clicked. */
fun graphRerouteItem(onPick: () -> Unit) =
    UiDropdownItem(graphEditorText("reroute"), icon = "hollowengine:textures/gui/icons/actions/reroute.svg", onClick = onPick)
