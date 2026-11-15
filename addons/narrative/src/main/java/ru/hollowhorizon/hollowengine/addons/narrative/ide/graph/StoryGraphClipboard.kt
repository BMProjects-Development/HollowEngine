package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.copyNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.retargetFunctions
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPoint

/** An independent snapshot, shared by open Narrative editors. */
internal object StoryGraphClipboard {
    var content: StoryGraph? = null

    fun snapshot(graph: StoryGraph, nodes: Set<String>, places: Map<String, StoryPoint>): StoryGraph = StoryGraph(
        nodes = graph.nodes.filter { it.id in nodes },
        links = graph.links.filter { it.to in nodes && (it.from in nodes || !it.isFlow) },
        layout = places.filterKeys { it in nodes },
        collapsed = graph.collapsed.intersect(nodes),
    )

    fun paste(source: StoryGraph, destination: StoryGraph, offset: StoryPoint): Pair<StoryGraph, Set<String>> {
        val ids = source.nodes.mapTo(LinkedHashSet()) { it.id }
        val (copied, fresh) = source.copyNodes(ids, offset)
        val newIds = fresh.values.toSet()
        val names = destination.functions.mapTo(HashSet()) { it.name }
        val renamed = HashMap<String, String>()
        val newNodes = copied.nodes.filter { it.id in newIds }.map { node ->
            if (node is StoryNode.Function && node.name in names) {
                val name = StoryGraphEdits.freeFunctionName(names).also(names::add)
                renamed[node.name] = name
                node.copy(name = name)
            } else {
                if (node is StoryNode.Function) names += node.name
                node
            }
        }.map { it.retargetFunctions(renamed) }
        val existing = destination.nodes.mapTo(HashSet()) { it.id }
        return destination.copy(
            nodes = destination.nodes + newNodes,
            links = destination.links + copied.links.filter { it.to in newIds && (it.from in newIds || it.from in existing) },
            layout = destination.layout + copied.layout.filterKeys { it in newIds },
            collapsed = destination.collapsed + copied.collapsed.intersect(newIds),
        ) to newIds
    }
}
