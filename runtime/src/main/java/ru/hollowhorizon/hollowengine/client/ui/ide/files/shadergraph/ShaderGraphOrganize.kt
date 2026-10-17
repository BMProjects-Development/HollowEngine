package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphArrange
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphRect
import ru.hollowhorizon.hollowengine.client.ui.graph.graphArrangeItem
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeShaderGraphDocument
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem

/** Puts [nodes] in a new group, which is then what is selected. */
internal fun HollowIdeShaderGraphDocument.groupNodes(nodes: Set<String>): ShaderGraphSelection? {
    if (nodes.isEmpty()) return null
    val (next, id) = graph.withGroup(nodes, graphText("group"))
    if (id.isEmpty()) return null
    edit { next }
    return ShaderGraphSelection(nodes, group = id)
}

/** Takes apart the group of [selection], or every group its nodes are in; the nodes stay selected. */
internal fun HollowIdeShaderGraphDocument.ungroup(selection: ShaderGraphSelection): ShaderGraphSelection? {
    val groups = selection.group?.let(::listOf) ?: selection.nodes.mapNotNull { graph.groupOf(it)?.id }.distinct()
    if (groups.isEmpty()) return null
    edit { graph -> groups.fold(graph) { current, id -> current.withoutGroup(id) } }
    return ShaderGraphSelection(selection.nodes)
}

/**
 * Nodes that were in no group and have been dragged into the frame of one join it, the way they would
 * be put in a box. A frame fits itself around its nodes, so a node leaves it by the node's menu instead.
 */
internal fun joinFrame(
    document: HollowIdeShaderGraphDocument,
    scene: ShaderGraphScene,
    boxes: Map<String, ShaderNodeBox>,
    origins: Map<String, Pair<Float, Float>>,
) {
    val graph = document.graph
    val loose = origins.keys.filter { id ->
        val node = graph.node(id) ?: return@filter false
        graph.groupOf(id) == null && origins.getValue(id) != (node.x to node.y)
    }
    if (loose.isEmpty()) return
    val target = scene.frames.entries.firstOrNull { (_, frame) ->
        loose.all { id ->
            val node = graph.node(id) ?: return@all false
            val box = boxes[id] ?: return@all false
            frame.contains(node.x + box.width / 2f, node.y + box.height / 2f)
        }
    }?.key ?: return
    document.edit { it.withMembership(loose.toSet(), target) }
}

/** A reroute put into the link at [index], centered on ([x], [y]) of the graph; the reroute is selected. */
internal fun HollowIdeShaderGraphDocument.addReroute(index: Int, x: Float, y: Float): ShaderGraphSelection {
    val id = graph.freeNodeId(ShaderNodeLibrary.REROUTE)
    val half = ShaderNodeLayout.REROUTE / 2f
    edit { it.withReroute(index, id, x - half, y - half) }
    return ShaderGraphSelection.of(id)
}

/** Lining up and spacing out those of [ids] that are on the canvas, as boxes of it. */
internal fun HollowIdeShaderGraphDocument.arrangeItem(boxes: Map<String, ShaderNodeBox>, ids: Collection<String>): UiDropdownItem? {
    val selected: Map<String, GraphRect> = ids.mapNotNull { id -> boxes[id]?.let { id to it.rect } }.toMap()
    fun move(to: Map<String, Pair<Float, Float>>) {
        if (to.isNotEmpty()) edit { it.withNodesAt(to) }
    }
    return graphArrangeItem(
        selected.size,
        onAlign = { alignment -> move(GraphArrange.align(selected, alignment)) },
        onDistribute = { horizontal -> move(GraphArrange.distribute(selected, horizontal)) },
    )
}
