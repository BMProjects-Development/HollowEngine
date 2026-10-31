package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** One place of a story read top to bottom. */
sealed interface StoryStep {
    val id: String

    /** A node with nothing under it. */
    class Plain(val node: StoryNode) : StoryStep {
        override val id: String get() = node.id
    }

    /** A call with the steps of its block under it. */
    class Scoped(val node: StoryNode.Call, val body: List<StoryStep>) : StoryStep {
        override val id: String get() = node.id
    }

    /** A node that splits the flow: the steps of each of its [pins], which meet again after it when they do. */
    class Split(val node: StoryNode, val pins: List<String>, val branches: List<List<StoryStep>>) : StoryStep {
        override val id: String get() = node.id
    }

    /** Flow from [pin] of [from] that continues somewhere shown elsewhere. It has no node of its own. */
    class Jump(val from: String, val pin: String, val target: StoryNode) : StoryStep {
        override val id: String get() = "$from/$pin"
    }
}

/** What flows from one [start], or from a node nothing leads to when it is null. */
class StorySection(val start: StoryNode.Start?, val steps: List<StoryStep>)

/**
 * The flow of a [StoryGraph] as nested steps, which is how the list shows it. Branches that meet again end where they
 * meet and the steps go on at the level of the split. Anything else reached twice is a [StoryStep.Jump].
 */
class StoryFlow(val graph: StoryGraph) {
    private val next = HashMap<Pair<String, String>, String>()
    private val into = HashMap<String, MutableList<StoryLink>>()
    private val visited = HashSet<String>()
    private val steps = HashMap<String, StoryStep>()
    private val owners = HashMap<String, Pair<String, String>>()

    val sections: List<StorySection>

    init {
        graph.links.filter { it.isFlow }.forEach { link ->
            next[link.from to link.fromPin] = link.to
            into.getOrPut(link.to, ::ArrayList) += link
        }
        val starts = graph.nodes.filterIsInstance<StoryNode.Start>()
            .sortedWith(compareBy({ it.name != MAIN }, { it.name }))
        val found = starts.map { start ->
            visited += start.id
            StorySection(start, sequence(next[start.id to StoryPins.NEXT], start.id, StoryPins.NEXT, emptySet()))
        }
        val values = graph.links.filter { !it.isFlow }.mapTo(HashSet()) { it.from }
        val loose = graph.nodes.filter { node ->
            node !is StoryNode.Expression && node.id !in visited && node.id !in into && node.id !in values
        }
        sections = found + loose.sortedBy { it.id }.mapNotNull { node ->
            sequence(node.id, node.id, StoryPins.IN, emptySet()).takeIf { it.isNotEmpty() }?.let { StorySection(null, it) }
        }
    }

    fun target(from: String, pin: String): String? = next[from to pin]

    /** The flow links into [node]. */
    fun incoming(node: String): List<StoryLink> = into[node].orEmpty()

    fun step(id: String): StoryStep? = steps[id]

    /** The pin of the node whose branch [id] is in, or null at the top of a section. */
    fun ownerOf(id: String): Pair<String, String>? = owners[id]

    /** The ids of the nodes that [step] is, with everything under it. */
    fun nodesOf(step: StoryStep): List<String> = when (step) {
        is StoryStep.Plain -> listOf(step.id)
        is StoryStep.Scoped -> listOf(step.id) + step.body.flatMap(::nodesOf)
        is StoryStep.Split -> listOf(step.id) + step.branches.flatten().flatMap(::nodesOf)
        is StoryStep.Jump -> emptyList()
    }

    /** The pins flow leaves [step] by, with the target they share if they have one. */
    fun exits(step: StoryStep): List<Pair<String, String>> = when (step) {
        is StoryStep.Plain, is StoryStep.Scoped -> listOf(step.id to StoryPins.NEXT)
        is StoryStep.Split -> step.pins.indices.flatMap { index ->
            step.branches[index].lastOrNull()?.let(::exits) ?: listOf(step.id to step.pins[index])
        }

        is StoryStep.Jump -> emptyList()
    }

    /** Where flow goes after [step], or null where it ends. */
    fun after(step: StoryStep): String? = exits(step).firstNotNullOfOrNull { (node, pin) -> next[node to pin] }

    private fun sequence(first: String?, fromNode: String, fromPin: String, stops: Set<String>): List<StoryStep> {
        val result = ArrayList<StoryStep>()
        var current = first
        var from = fromNode
        var pin = fromPin
        while (current != null && current !in stops) {
            val node = graph.node(current) ?: break
            if (node is StoryNode.Start || !visited.add(current)) {
                result += StoryStep.Jump(from, pin, node)
                break
            }
            owners[current] = from to pin
            val step = when (node) {
                is StoryNode.Branch -> split(node, listOf(StoryPins.THEN, StoryPins.ELSE), stops)
                is StoryNode.Choose -> split(node, node.options.indices.map(StoryPins::option), stops)
                is StoryNode.Call if node.block ->
                    StoryStep.Scoped(node, sequence(next[node.id to StoryPins.BODY], node.id, StoryPins.BODY, stops))

                else -> StoryStep.Plain(node)
            }
            steps[current] = step
            result += step
            if (step is StoryStep.Split) {
                current = meeting(step.pins.map { next[node.id to it] }, emptySet())
                from = node.id
                pin = step.pins.firstOrNull().orEmpty()
            } else {
                current = next[node.id to StoryPins.NEXT]
                from = node.id
                pin = StoryPins.NEXT
            }
        }
        return result
    }

    private fun split(node: StoryNode, pins: List<String>, stops: Set<String>): StoryStep.Split {
        val targets = pins.map { next[node.id to it] }
        val meeting = meeting(targets, stops)
        val inner = if (meeting != null) stops + meeting else stops
        val branches = pins.mapIndexed { index, pin -> sequence(targets[index], node.id, pin, inner) }
        return StoryStep.Split(node, pins, branches)
    }

    /** The first node every one of [targets] leads to; none when one of them ends the story. */
    private fun meeting(targets: List<String?>, stops: Set<String>): String? {
        if (targets.isEmpty() || targets.any { it == null }) return null
        val reachable = targets.map { reachableFrom(it!!, stops) }
        return reachable.first().keys.firstOrNull { node -> reachable.all { node in it } }
    }

    /** What flow from [start] reaches without passing a node already placed, nearest first. */
    private fun reachableFrom(start: String, stops: Set<String>): LinkedHashMap<String, Unit> {
        val seen = LinkedHashMap<String, Unit>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node in seen || node in visited) continue
            seen[node] = Unit
            if (node in stops || graph.node(node) is StoryNode.Start) continue
            queue += next.entries.filter { it.key.first == node && it.key.second != StoryPins.BODY }.map { it.value }
        }
        return seen
    }

    private companion object {
        const val MAIN = "main"
    }
}
