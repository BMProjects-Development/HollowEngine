package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.delay
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeShaderGraphDocument
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxGraphMaterials
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import ru.hollowhorizon.hollowengine.client.history.UndoKeys

internal const val ShaderGraphStylesheet = "hollowengine:ui/styles/shader-graph.hss"
private const val AutoSaveDelayMillis = 900L

/**
 * Editor of material file: nodes with pins on a canvas, links drawn from pin to pin, and
 * what each node computes drawn right on it.
 */
@Composable
internal fun ShaderGraphEditor(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeShaderGraphDocument
    val state = remember(document) { document.editorState { ShaderGraphEditorState() } }
    val view = state.view
    val previews = remember(document) { ShaderGraphPreviews() }
    DisposableEffect(previews) { onDispose { RenderSystem.recordRenderCall(previews::release) } }

    var link by remember(document) { mutableStateOf<PendingLink?>(null) }
    var menu by remember(document) { mutableStateOf<GraphMenu?>(null) }
    var insertInto by remember(document) { mutableStateOf<Int?>(null) }
    var previewError by remember(document) { mutableStateOf<ShaderDiagnostic?>(null) }
    val dragOrigins = remember(document) { HashMap<String, Pair<Float, Float>>() }

    /** The node whose corner lands on the grid while a drag snaps; the rest keep their distance to it. */
    val dragAnchor = remember(document) { arrayOfNulls<String>(1) }

    LaunchedEffect(document.revision) {
        file.updateDirty(document.isModified)
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified) file.save()
    }
    LaunchedEffect(document, document.isModified) {
        if (document.isModified) return@LaunchedEffect
        val location = VfxGraphMaterials.locationOf(file.path)
        RenderSystem.recordRenderCall { VfxGraphMaterials.changed(location) }
    }
    LaunchedEffect(previews) {
        while (true) {
            withFrameNanos { }
            val error = previews.error
            if (error != previewError) previewError = error
        }
    }

    val graph = document.graph
    val kinds = ShaderNodeTypes.revision
    val code = remember(document.revision, kinds) { ShaderGraphCompiler.compile(graph) }
    val diagnostics = code.diagnostics + listOfNotNull(previewError)
    val problems = diagnostics.filter { it.node != null }.groupBy { it.node!! }
    previews.show(graph, document.revision to kinds)

    // A group taken apart, from the inspector or by undo, leaves its nodes selected on their own.
    val selection = state.selection.let { current ->
        if (current.group != null && graph.group(current.group) == null) current.copy(group = null) else current
    }
    SideEffect {
        state.diagnostics = diagnostics
        if (state.selection != selection) state.selection = selection
    }
    PublishInspector(source = "shadergraph-${file.path}", key = selection) {
        shaderGraphInspectorTarget(document, selection, state)
    }

    val all = LinkedHashMap<String, ShaderNodeBox>()
    graph.nodes.forEach { node ->
        ShaderNodeTypes.of(node.type)?.let { all[node.id] = ShaderNodeLayout.of(graph, node, it, code.types) }
    }
    val scene = ShaderGraphScene.of(graph, all)
    val boxes = scene.boxes
    val pins = scene.pins
    val curves = graph.links.mapIndexedNotNull { index, each ->
        val from = pins.firstOrNull { it.node == each.from && it.output && it.name == each.output }
            ?: return@mapIndexedNotNull null
        val to = pins.firstOrNull { it.node == each.to && !it.output && it.name == each.input }
            ?: return@mapIndexedNotNull null
        index to GraphCurves.betweenPins(
            view.toCanvasX(from.x), view.toCanvasY(from.y), view.toCanvasX(to.x), view.toCanvasY(to.y), view.zoom,
        )
    }

    fun select(next: ShaderGraphSelection?) {
        if (next != null) state.selection = next
    }

    fun membersOf(group: String): Set<String> = graph.group(group)?.nodes?.filter { it in all }?.toSet().orEmpty()

    fun pinAt(x: Float, y: Float, outputs: Boolean): ShaderPin? =
        pins.filter { it.output == outputs }.map { it to hypot(view.toCanvasX(it.x) - x, view.toCanvasY(it.y) - y) }
            .filter { it.second <= PIN_REACH * view.zoom.coerceAtLeast(1f) }.minByOrNull { it.second }?.first

    fun nodeAt(x: Float, y: Float): ShaderNodeBox? = boxes.values.lastOrNull { it.rect.toCanvas(view).contains(x, y) }

    fun cardAt(x: Float, y: Float): ShaderGroupCard? = scene.cards.values.lastOrNull { it.rect.toCanvas(view).contains(x, y) }

    /** A node of [kind] added at ([x], [y]) of the graph and selected. */
    fun addNode(kind: ShaderNodeType, x: Float, y: Float): ShaderGraphNode {
        val node = ShaderGraphNode(document.graph.freeNodeId(kind.id), kind.id, x, y)
        document.edit { it.withNode(node) }
        select(ShaderGraphSelection.of(node.id))
        return node
    }

    /**
     * A link dropped on nothing: the add menu, showing only what can take the link, and the node
     * picked from it placed where the link was dropped and linked. A reroute can always take it.
     */
    fun offerNodeFor(pending: PendingLink) {
        val graphX = view.toGraphX(pending.x)
        val graphY = view.toGraphY(pending.y)
        val half = ShaderNodeLayout.REROUTE / 2f
        val items = if (pending.fromOutput) {
            val carried = code.types.output(pending.node, pending.pin) ?: ShaderType.FLOAT
            val reroute = rerouteItem {
                val node = addNode(ShaderNodeTypes.of(ShaderNodeLibrary.REROUTE) ?: return@rerouteItem, graphX - half, graphY - half)
                document.edit { it.withLink(pending.node, pending.pin, node.id, ShaderNodeLibrary.REROUTE_INPUT) }
            }
            listOf(reroute) + addNodeItems(graph.target, { it.inputFor(carried) != null }) { kind ->
                val node = addNode(kind, graphX, graphY - ShaderNodeLayout.HEADER)
                kind.inputFor(carried, node)
                    ?.let { pin -> document.edit { it.withLink(pending.node, pending.pin, node.id, pin.name) } }
            }
        } else {
            val into =
                graph.node(pending.node)?.let { node -> ShaderNodeTypes.of(node.type)?.input(node, pending.pin)?.type }
                    ?: return
            val reroute = rerouteItem {
                val node = addNode(ShaderNodeTypes.of(ShaderNodeLibrary.REROUTE) ?: return@rerouteItem, graphX - half, graphY - half)
                document.edit { it.withLink(node.id, ShaderNodeLibrary.REROUTE_OUTPUT, pending.node, pending.pin) }
            }
            listOf(reroute) + addNodeItems(graph.target, { it.outputFor(into) != null }) { kind ->
                val node = addNode(kind, graphX - ShaderNodeLayout.WIDTH, graphY - ShaderNodeLayout.HEADER)
                kind.outputFor(into)
                    ?.let { output -> document.edit { it.withLink(node.id, output.name, pending.node, pending.pin) } }
            }
        }
        menu = GraphMenu(pending.screenX, pending.screenY, items.mapIndexed { index, item -> if (index == 1) item.copy(separatorBefore = true) else item })
    }

    /**
     * Ends a link being drawn: dropped on a pin, it links to it; on a node, to the first of its pins
     * that takes it; on a collapsed group, nowhere; on nothing, it offers the nodes that could.
     */
    fun finishLink() {
        val pending = link ?: return
        link = null
        val pin = pinAt(pending.x, pending.y, outputs = !pending.fromOutput)
        val node = nodeAt(pending.x, pending.y)?.takeIf { it.node.id != pending.node }
        when {
            pin != null -> document.edit {
                if (pending.fromOutput) it.withLink(pending.node, pending.pin, pin.node, pin.name)
                else it.withLink(pin.node, pin.name, pending.node, pending.pin)
            }

            node != null && pending.fromOutput -> {
                val carried = code.types.output(pending.node, pending.pin) ?: ShaderType.FLOAT
                val free = node.kind.inputs(node.node).filter { graph.linkInto(node.node.id, it.name) == null }
                val target =
                    free.firstOrNull { it.type.fixed == carried } ?: free.firstOrNull { it.type.accepts(carried) }
                if (target != null) document.edit { it.withLink(pending.node, pending.pin, node.node.id, target.name) }
            }

            node != null -> {
                val into = graph.node(pending.node)
                    ?.let { owner -> ShaderNodeTypes.of(owner.type)?.input(owner, pending.pin)?.type }
                val output = into?.let { node.kind.outputFor(it) }
                if (output != null) document.edit { it.withLink(node.node.id, output.name, pending.node, pending.pin) }
            }

            cardAt(pending.x, pending.y) != null -> Unit
            else -> offerNodeFor(pending)
        }
        document.endGesture()
    }

    /** Puts [nodeId] into the link at [index]: whatever fed the link feeds the node, and the node feeds where the link went. */
    fun insert(index: Int, nodeId: String) {
        val current = document.graph
        val target = current.links.getOrNull(index) ?: return
        val node = current.node(nodeId) ?: return
        val kind = ShaderNodeTypes.of(node.type) ?: return
        val input = kind.inputFor(code.types.output(target.from, target.output) ?: ShaderType.FLOAT, node) ?: return
        val into = current.node(target.to)?.let { ShaderNodeTypes.of(it.type)?.input(it, target.input)?.type } ?: return
        val output = kind.outputFor(into) ?: return
        document.edit {
            it.withoutLinkAt(index).withLink(target.from, target.output, nodeId, input.name)
                .withLink(nodeId, output.name, target.to, target.input)
        }
    }

    /** Ends a drag of nodes: one dropped on a link goes into it, and the whole drag is one step back. */
    fun finishDrag() {
        if (dragOrigins.isEmpty()) return
        insertInto?.let { index -> dragOrigins.keys.singleOrNull()?.let { insert(index, it) } }
        insertInto = null
        joinFrame(document, scene, all, dragOrigins)
        dragOrigins.clear()
        document.endGesture()
    }

    /** Takes hold of the selected nodes for a drag that [anchor] leads. */
    fun beginDrag(anchor: String?) {
        dragOrigins.clear()
        state.selection.nodes.forEach { id -> document.graph.node(id)?.let { dragOrigins[id] = it.x to it.y } }
        dragAnchor[0] = anchor
        document.beginGesture()
    }

    /** A group taken by its title bar leaves the nodes; the nodes themselves go, the output node excepted. */
    fun deleteSelection() {
        val index = selection.link
        val group = selection.group
        when {
            index != null -> document.edit { it.withoutLinkAt(index) }
            group != null -> document.edit { it.withoutGroup(group) }
            else -> {
                val removable = selection.nodes.filter { all[it]?.kind?.master == null }.toSet()
                if (removable.isEmpty()) return
                document.edit { it.withoutNodes(removable) }
            }
        }
        select(ShaderGraphSelection.None)
    }

    fun deleteGroupWithNodes(group: String) {
        val removable = membersOf(group).filter { all[it]?.kind?.master == null }.toSet()
        document.edit { it.withoutGroup(group).withoutNodes(removable) }
        select(ShaderGraphSelection.None)
    }

    fun duplicateSelection() {
        val copied = selection.nodes.filter { all[it]?.kind?.master == null }.toSet()
        if (copied.isEmpty()) return
        val (next, copies) = document.graph.withDuplicates(copied)
        document.edit { next }
        select(ShaderGraphSelection(copies.toSet()))
    }

    fun flipGroup(group: String) = document.edit { graph -> graph.withGroupChanged(group) { it.copy(collapsed = !it.collapsed) } }

    fun handleKey(input: UiKeyInput): Boolean {
        when {
            input.key == GLFW.GLFW_KEY_DELETE -> deleteSelection()
            input.control && input.key == GLFW.GLFW_KEY_D -> duplicateSelection()
            input.control && input.key == GLFW.GLFW_KEY_A -> select(ShaderGraphSelection(all.keys.toSet()))
            input.control && input.key == GLFW.GLFW_KEY_G -> select(
                if (input.shift) document.ungroup(selection) else document.groupNodes(selection.nodes)
            )

            UndoKeys.handle(document.history, input.key, input.modifiers) -> Unit
            input.key == GLFW.GLFW_KEY_ESCAPE -> if (link != null) {
                link = null
                document.endGesture()
            } else {
                select(ShaderGraphSelection.None)
            }

            else -> return false
        }
        return true
    }

    val actions = object : ShaderNodeActions {
        override fun pressNode(node: String, gesture: GraphNodeGesture) {
            menu = null
            when {
                gesture.shift || gesture.control -> {
                    val nodes = if (node in selection) selection.nodes - node else selection.nodes + node
                    select(ShaderGraphSelection(nodes))
                }

                node !in selection || selection.group != null -> select(ShaderGraphSelection.of(node))
            }
            beginDrag(node)
        }

        override fun pressGroup(group: String, gesture: GraphNodeGesture) {
            menu = null
            val members = membersOf(group)
            when {
                gesture.shift || gesture.control -> {
                    val nodes = if (members.all { it in selection }) selection.nodes - members else selection.nodes + members
                    select(ShaderGraphSelection(nodes))
                }

                selection.group != group -> select(ShaderGraphSelection(members, group = group))
            }
            beginDrag(members.firstOrNull())
        }

        override fun dragNode(node: String, gesture: GraphNodeGesture) {
            if (link != null || dragOrigins.isEmpty()) return
            val anchor = dragAnchor[0]?.let(dragOrigins::get) ?: dragOrigins.values.first()
            val dx = GraphPreferences.place(anchor.first + gesture.graphDeltaX) - anchor.first
            val dy = GraphPreferences.place(anchor.second + gesture.graphDeltaY) - anchor.second
            document.edit { graph ->
                graph.withNodesAt(dragOrigins.mapValues { (_, at) -> at.first + dx to at.second + dy })
            }
            val lone = dragOrigins.keys.singleOrNull()
                ?.takeIf { id -> document.graph.links.none { it.from == id || it.to == id } }
            insertInto = lone?.let { GraphCurves.nearest(curves, gesture.canvasX, gesture.canvasY, INSERT_REACH) }
        }

        override fun releaseNode(node: String) = finishDrag()

        override fun nodeMenu(node: String, screenX: Float, screenY: Float) {
            if (node !in selection) select(ShaderGraphSelection.of(node))
            val box = all[node] ?: return
            val chosen = state.selection
            menu = GraphMenu(
                screenX, screenY,
                nodeItems(
                    box.node, box.kind,
                    onPreview = { shown -> document.edit { it.withPreview(node, shown) } },
                    onDuplicate = ::duplicateSelection,
                    onDelete = ::deleteSelection,
                    organize = organizeItems(
                        canGroup = chosen.nodes.isNotEmpty(),
                        grouped = chosen.nodes.any { graph.groupOf(it) != null },
                        onGroup = { select(document.groupNodes(chosen.nodes)) },
                        onUngroup = { select(document.ungroup(chosen)) },
                        onLeave = { document.edit { it.withMembership(chosen.nodes, group = null) } },
                        arrange = document.arrangeItem(boxes, chosen.nodes),
                    ),
                ),
            )
        }

        override fun groupMenu(group: String, screenX: Float, screenY: Float) {
            val found = graph.group(group) ?: return
            val members = membersOf(group)
            if (selection.group != group) select(ShaderGraphSelection(members, group = group))
            menu = GraphMenu(
                screenX, screenY,
                groupItems(
                    found,
                    onToggle = { flipGroup(group) },
                    onUngroup = { select(document.ungroup(ShaderGraphSelection(members, group = group))) },
                    onDelete = { deleteGroupWithNodes(group) },
                    arrange = if (found.collapsed) null else document.arrangeItem(boxes, members),
                ),
            )
        }

        override fun toggleCollapsed(node: String) {
            val collapsed = document.graph.node(node)?.collapsed ?: return
            document.edit { it.withCollapsed(node, !collapsed) }
        }

        override fun toggleGroup(group: String) = flipGroup(group)

        override fun pressPin(pin: ShaderPin, screenX: Float, screenY: Float) {
            menu = null
            document.beginGesture()
            val x = view.toCanvasX(pin.x)
            val y = view.toCanvasY(pin.y)
            val existing = if (pin.output) null else graph.linkInto(pin.node, pin.name)
            link = when {
                pin.output -> PendingLink(pin.node, pin.name, true, x, y, screenX, screenY)
                existing != null -> {
                    document.edit { it.withoutLinkInto(pin.node, pin.name) }
                    PendingLink(existing.from, existing.output, true, x, y, screenX, screenY)
                }

                else -> PendingLink(pin.node, pin.name, false, x, y, screenX, screenY)
            }
        }

        override fun dragPin(canvasX: Float, canvasY: Float, screenX: Float, screenY: Float) {
            link = link?.copy(x = canvasX, y = canvasY, screenX = screenX, screenY = screenY)
        }

        override fun releasePin() = finishLink()

        override fun setValue(node: String, pin: String, values: List<Float>) =
            document.edit { it.withValue(node, pin, values) }

        override fun setOption(node: String, option: String, value: String) =
            document.edit { it.withOption(node, option, value) }

        override fun openMenu(screenX: Float, screenY: Float, items: List<UiDropdownItem>) {
            menu = GraphMenu(screenX, screenY, items)
        }

        override fun beginGesture() = document.beginGesture()
        override fun endGesture() = document.endGesture()
    }

    Box(
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).style(ShaderGraphStylesheet).focusScope()
            .drawBehind(key = previews) { drawGl { previews.render() } },
    ) {
        GraphCanvas(
            id = ShaderGraphCanvasId,
            view = view,
            modifier = Modifier.size(100.percent, 100.percent),
            edges = curves.map { (index, curve) ->
                val each = graph.links[index]
                GraphEdge(
                    key = index,
                    curve = curve,
                    color = code.types.output(each.from, each.output)?.color() ?: GraphLinkColor,
                    selected = selection.link == index || insertInto == index,
                )
            },
            link = link?.let { pending ->
                val pin =
                    pins.firstOrNull { it.node == pending.node && it.name == pending.pin && it.output == pending.fromOutput }
                        ?: return@let null
                val fromX = view.toCanvasX(pin.x)
                val fromY = view.toCanvasY(pin.y)
                val type = if (pending.fromOutput) code.types.output(pin.node, pin.name) else code.types.input(
                    pin.node, pin.name
                )
                val curve =
                    if (pending.fromOutput) GraphCurves.betweenPins(fromX, fromY, pending.x, pending.y, view.zoom)
                    else GraphCurves.betweenPins(pending.x, pending.y, fromX, fromY, view.zoom)
                GraphLinkPreview(fromX, fromY, pending.x, pending.y, type?.color() ?: GraphLinkColor, curve)
            },
            frames = scene.frames.mapNotNull { (id, rect) ->
                graph.group(id)?.let { GraphFrame(rect, GraphGroupColors.of(it.color)) }
            },
            minimap = boxes.values.map { box ->
                GraphMiniMapItem(
                    box.rect, if (box.node.id in selection) GraphSelectedColor else GraphLinkColor, box.kind.title()
                )
            } + scene.cards.values.map { card ->
                GraphMiniMapItem(card.rect, GraphGroupColors.of(card.group.color), card.group.title.ifBlank { graphText("group") })
            },
            onBackgroundClick = {
                menu = null
                select(ShaderGraphSelection.None)
            },
            onEdgeClick = { index -> select(ShaderGraphSelection.ofLink(index as Int)) },
            onEdgeDoubleClick = { index, pointer -> select(document.addReroute(index as Int, pointer.graphX, pointer.graphY)) },
            onContextMenu = { pointer, edge ->
                menu = if (edge is Int) {
                    select(ShaderGraphSelection.ofLink(edge))
                    GraphMenu(
                        pointer.screenX,
                        pointer.screenY,
                        listOf(
                            rerouteItem { select(document.addReroute(edge, pointer.graphX, pointer.graphY)) },
                            UiDropdownItem(graphText("delete_link"), shortcut = "Del") {
                                document.edit { it.withoutLinkAt(edge) }
                                select(ShaderGraphSelection.None)
                            },
                        ),
                    )
                } else {
                    GraphMenu(
                        pointer.screenX,
                        pointer.screenY,
                        addNodeItems(graph.target) { kind -> addNode(kind, pointer.graphX, pointer.graphY) } + graphViewItems(),
                    )
                }
            },
            onRelease = {
                finishLink()
                finishDrag()
            },
            onSelectArea = { area, modifiers ->
                val covered = boxes.values.filter { it.rect.intersects(area) }.map { it.node.id } +
                        scene.cards.values.filter { it.rect.intersects(area) }.flatMap { membersOf(it.group.id) }
                val additive = modifiers and (GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL) != 0
                select(ShaderGraphSelection(if (additive) selection.nodes + covered else covered.toSet()))
            },
            onKey = ::handleKey,
        ) {
            ShaderGraphSceneView(graph, scene, code.types, view, selection, problems, previews, actions)
        }

        menu?.let { open ->
            ContextMenu(
                id = "shader-graph-menu",
                anchorBounds = UiRect(open.x, open.y, 0f, 0f),
                items = open.items,
                onExpandedChange = { if (!it) menu = null },
            )
        }
    }
}

internal fun graphText(name: String): String = "hollowengine.gui.shadergraph.$name".lang

/** How far from a pin, in canvas pixels at zoom 1, a link can be dropped and still land on it. */
private const val PIN_REACH = 12f

/** How close to a link, in canvas pixels, a lone node has to be dragged to go into it. */
private const val INSERT_REACH = 10f
