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

    var pointer: GraphPointer? = null

    var search by mutableStateOf<GraphSearch?>(null)
        private set

    val found: List<String> by derivedStateOf {
        val words = search?.query?.trim().orEmpty()
        if (words.isEmpty()) return@derivedStateOf emptyList()
        val boxes = model.scene.boxes
        boxes.keys.filter { model.searchText(it).contains(words, ignoreCase = true) }
            .sortedWith(compareBy({ boxes.getValue(it).y }, { boxes.getValue(it).x }))
    }

    var link by mutableStateOf<GraphPendingLink?>(null)
        private set
    var menu by mutableStateOf<GraphMenu?>(null)
    var insertInto by mutableStateOf<Any?>(null)
        private set

    /** Whether a gesture is open in [model]. There is one at a time, whatever began it. */
    private var gesture = false

    private val dragOrigins = HashMap<String, Pair<Float, Float>>()

    /** The node pressed last and when, so that a second press on it soon after reads as a double click. */
    private var lastPress: Pair<String, Long>? = null

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

    private fun beginGesture() {
        endGesture()
        model.beginGesture()
        gesture = true
    }

    private fun endGesture() {
        if (!gesture) return
        gesture = false
        model.endGesture()
    }

    /** Takes back the gesture under way, the link it drew and the nodes it dragged; false when there is none. */
    fun cancelGesture(): Boolean {
        if (!gesture) return false
        gesture = false
        link = null
        insertInto = null
        dragOrigins.clear()
        model.cancelGesture()
        return true
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
            cardAt(pending.x, pending.y) != null || pending.detached -> Unit
            else -> {
                val items = model.offerNodes(pending, view.toGraphX(pending.x), view.toGraphY(pending.y))
                menu = GraphMenu(pending.screenX, pending.screenY, items, search = true)
            }
        }
        endGesture()
    }

    /** Ends a drag of nodes: one dropped on a link goes into it, and the whole drag is one step back. */
    fun finishDrag() {
        if (dragOrigins.isEmpty()) return
        insertInto?.let { link -> dragOrigins.keys.singleOrNull()?.let { model.insertIntoLink(link, it) } }
        insertInto = null
        joinFrame()
        dragOrigins.clear()
        endGesture()
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
        if (dragOrigins.isEmpty()) endGesture() else beginGesture()
    }

    /** A click with Shift or Control adds to the selection and takes away what was in it. */
    fun pressNode(node: String, gesture: GraphNodeGesture) {
        menu = null
        val now = System.currentTimeMillis()
        val repeated = lastPress?.let { (pressed, at) -> pressed == node && now - at <= DOUBLE_PRESS_MILLIS } == true
        lastPress = if (repeated) null else node to now
        if (repeated && !gesture.shift && !gesture.control && model.openNode(node)) return
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
        beginGesture()
        val x = view.toCanvasX(pin.x)
        val y = view.toCanvasY(pin.y)
        val source = if (pin.output) null else model.detach(pin)
        link = when {
            pin.output -> GraphPendingLink(pin.node, pin.name, true, x, y, screenX, screenY)
            source != null -> GraphPendingLink(source.node, source.name, true, x, y, screenX, screenY, detached = true)
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
            menu != null -> if (input.key == GLFW.GLFW_KEY_ESCAPE) menu = null else return false
            HollowKeys.ADD_NODE.matches(input) -> addMenu()
            HollowKeys.FIND.matches(input) -> openSearch()
            HollowKeys.NEXT_FOUND.matches(input) && search != null -> stepSearch(1)
            HollowKeys.PREVIOUS_FOUND.matches(input) && search != null -> stepSearch(-1)
            HollowKeys.DELETE.matches(input) -> deleteSelection()
            HollowKeys.DUPLICATE.matches(input) -> duplicateSelection()
            HollowKeys.SELECT_ALL.matches(input) ->
                select(GraphSelection(scene.boxes.keys + scene.cards.flatMap { model.groupMembers(it.id) }))

            HollowKeys.GROUP.matches(input) -> select(model.groupNodes(selection.nodes))
            HollowKeys.UNGROUP.matches(input) -> select(model.ungroup(selection))
            model.history?.let { UndoKeys.handle(it, input.key, input.modifiers) } == true -> Unit
            input.key == GLFW.GLFW_KEY_ESCAPE -> if (!cancelGesture()) select(GraphSelection.None)

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
        menu = GraphMenu(
            pointer.screenX, pointer.screenY,
            model.addItems(pointer.graphX, pointer.graphY) + graphViewItems(),
            search = true,
        )
    }

    /** The menu of what can be added, under the pointer, or in the middle of the canvas while the pointer is away. */
    fun addMenu() {
        val at = pointer ?: GraphPointer(
            canvas.x + canvas.width / 2f, canvas.y + canvas.height / 2f,
            canvas.width / 2f, canvas.height / 2f,
            view.toGraphX(canvas.width / 2f), view.toGraphY(canvas.height / 2f),
        )
        menu = GraphMenu(at.screenX, at.screenY, model.addItems(at.graphX, at.graphY), search = true)
    }

    fun openSearch() {
        val open = search ?: GraphSearch().also { search = it }
        open.focusRequests++
    }

    fun closeSearch() {
        search = null
    }

    /** Looks for [query] anew and brings the first node it finds into view. */
    fun searchFor(query: String) {
        val open = search ?: return
        open.query = query
        open.index = 0
        found.firstOrNull()?.let(::reveal)
    }

    /** Brings the next node found into view, or the one before it when [step] is negative. */
    fun stepSearch(step: Int) {
        val open = search ?: return
        if (found.isEmpty()) return
        open.index = (open.index + step).mod(found.size)
        reveal(found[open.index])
    }

    /** Selects [node] and brings it to the middle of the canvas. */
    fun reveal(node: String) {
        val rect = scene.boxes[node] ?: return
        select(GraphSelection.of(node))
        view.centerOn(rect.centerX, rect.centerY, canvas.width, canvas.height)
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

        /** How soon a second press on a node has to follow the first to open it. */
        const val DOUBLE_PRESS_MILLIS = 400L

        const val GroupIcon = "hollowengine:textures/gui/icons/actions/group.svg"
        const val UngroupIcon = "hollowengine:textures/gui/icons/actions/ungroup.svg"
    }
}

/** The item that puts a reroute where a link was dropped, or on the link that was clicked. */
fun graphRerouteItem(onPick: () -> Unit) =
    UiDropdownItem(graphEditorText("reroute"), icon = "hollowengine:textures/gui/icons/actions/reroute.svg", onClick = onPick)
