package ru.hollowhorizon.hollowengine.addons.narrative.graph

/**
 * Whether what nodes read exists by the time flow gets to them. A link says where a value comes from, not that flow
 * passed the node that gives it: that takes the source being on every way from a start to the reader.
 */
internal class StoryValueFlow(
    private val graph: StoryGraph,
    private val index: StoryLinkIndex,
    private val resultType: (StoryNode.Invoke, String) -> String? = { _, _ -> null },
) {
    private val nodes = graph.nodes.associateBy { it.id }
    private val inFlow = graph.links.filter { it.isFlow }.flatMapTo(HashSet()) { listOf(it.from, it.to) }

    /** Every node reached from a start with the nodes flow cannot get to it without. */
    private val passed: Map<String, Set<String>> = dominators()

    fun problems(): List<StoryGraphProblem> = passed.keys.sorted().flatMap { reader ->
        sources(reader).mapNotNull { source -> problem(reader, source) }
    }.distinct()

    private fun problem(reader: String, source: StoryLink): StoryGraphProblem? {
        val node = nodes.getValue(source.from)
        return if (node is StoryNode.Loop) itemProblem(reader, node) else resultProblem(reader, source)
    }

    /** The item is a local of the `for`, which only holds the nodes that are not stages of their own. */
    private fun itemProblem(reader: String, loop: StoryNode.Loop): StoryGraphProblem? {
        val inFor = body(loop.id) { index.isMerge(it) || isEntry(nodes[it]) }
        return when (reader) {
            in inFor -> null
            in body(loop.id) -> StoryGraphProblem(
                reader, "loop_item_merged",
                "Flow gets here through a node several links lead to, which runs apart from the loop and cannot read its item: put the item into a variable first",
            )

            else -> StoryGraphProblem(reader, "loop_item", "The item of a loop only exists in the body of that loop")
        }
    }

    /** A result nothing has set yet is null, which is an error unless it can be null anyway. */
    private fun resultProblem(reader: String, source: StoryLink): StoryGraphProblem? {
        val node = source.from
        if (node != reader && node in passed.getValue(reader) && reader !in body(node)) return null
        return StoryGraphProblem(
            reader, "other_branch", "Flow can get here without passing the node this value comes from",
            warning = isOptional(source),
        )
    }

    private fun isOptional(source: StoryLink): Boolean = when (val node = nodes[source.from]) {
        is StoryNode.Call -> node.returns
        is StoryNode.Code -> node.outputs[source.fromPin]
        is StoryNode.Invoke -> resultType(node, source.fromPin)
        else -> null
    }?.endsWith("?") == true

    /** The links that bring [reader] results of nodes of the flow, directly or through values worked out on the way. */
    private fun sources(reader: String): List<StoryLink> {
        val found = LinkedHashSet<StoryLink>()
        val seen = hashSetOf(reader)
        val queue = ArrayDeque(index.valuesInto(reader))
        while (queue.isNotEmpty()) {
            val link = queue.removeFirst()
            val node = nodes[link.from] ?: continue
            when {
                node is StoryNode.Loop || (node.id in inFlow && node.givesResults()) -> found += link
                node.id !in inFlow && seen.add(node.id) -> queue += index.valuesInto(node.id)
            }
        }
        return found.toList()
    }

    private fun StoryNode.givesResults() =
        this is StoryNode.Call || this is StoryNode.Code || this is StoryNode.Invoke

    /** Where flow starts: the start of the story, and the parameters of each function. */
    private fun isEntry(node: StoryNode?) = node is StoryNode.Start || node is StoryNode.Parameters

    /** What flow goes through from the body of [node] until it gets to where [stops] says. */
    private fun body(node: String, stops: (String) -> Boolean = { it == node }): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOfNotNull(index.flow[node to StoryPins.BODY]))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (!stops(at) && seen.add(at)) queue += index.flowOut(at)
        }
        return seen
    }

    private fun dominators(): Map<String, Set<String>> {
        val starts = graph.nodes.filter(::isEntry).map { it.id }
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
