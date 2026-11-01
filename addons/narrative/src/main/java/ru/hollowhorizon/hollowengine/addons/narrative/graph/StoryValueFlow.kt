package ru.hollowhorizon.hollowengine.addons.narrative.graph

/**
 * Whether what nodes read exists by the time flow gets to them. A link says where a value comes from, not that flow
 * passed the node that gives it: that takes the source being on every way from a start to the reader.
 */
internal class StoryValueFlow(private val graph: StoryGraph, private val index: StoryLinkIndex) {
    private val nodes = graph.nodes.associateBy { it.id }
    private val inFlow = graph.links.filter { it.isFlow }.flatMapTo(HashSet()) { listOf(it.from, it.to) }

    /** Every node reached from a start with the nodes flow cannot get to it without. */
    private val passed: Map<String, Set<String>> = dominators()

    fun problems(): List<StoryGraphProblem> = passed.keys.sorted().flatMap { reader ->
        sources(reader).flatMap { source -> problem(reader, source) }
    }

    private fun problem(reader: String, source: StoryNode): List<StoryGraphProblem> = when (source) {
        is StoryNode.Loop -> if (reader in body(source)) emptyList() else listOf(
            StoryGraphProblem(reader, "loop_item", "The item of a loop only exists in the body of that loop"),
        )

        else -> if (source.id != reader && source.id in passed.getValue(reader)) emptyList() else listOf(
            StoryGraphProblem(
                reader, "other_branch", "Flow can get here without passing the node this value comes from", warning = true
            ),
        )
    }

    /** The nodes of the flow whose results [reader] takes, directly or through values worked out on the way. */
    private fun sources(reader: String): List<StoryNode> {
        val found = LinkedHashSet<StoryNode>()
        val seen = hashSetOf(reader)
        val queue = ArrayDeque(index.valuesInto(reader))
        while (queue.isNotEmpty()) {
            val link = queue.removeFirst()
            val node = nodes[link.from] ?: continue
            when {
                node is StoryNode.Loop || (node.id in inFlow && (node is StoryNode.Call || node is StoryNode.Code)) -> found += node
                node.id !in inFlow && seen.add(node.id) -> queue += index.valuesInto(node.id)
            }
        }
        return found.toList()
    }

    private fun body(loop: StoryNode.Loop): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOfNotNull(index.flow[loop.id to StoryPins.BODY]))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at != loop.id && seen.add(at)) queue += index.flowOut(at)
        }
        return seen
    }

    private fun dominators(): Map<String, Set<String>> {
        val starts = graph.nodes.filterIsInstance<StoryNode.Start>().map { it.id }
        val reached = LinkedHashSet<String>()
        val queue = ArrayDeque(starts)
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (reached.add(at)) queue += index.flowOut(at)
        }
        val before = reached.associateWith { node -> index.flowIn(node).filter { it in reached } }
        val result =
            reached.associateWithTo(HashMap()) { node -> if (node in starts) setOf(node) else reached as Set<String> }
        var changed = true
        while (changed) {
            changed = false
            for (node in reached) {
                if (node in starts) continue
                val common =
                    before.getValue(node).map(result::getValue).reduceOrNull { all, set -> all intersect set }.orEmpty()
                val next = common + node
                if (next != result[node]) {
                    result[node] = next
                    changed = true
                }
            }
        }
        return result
    }
}
