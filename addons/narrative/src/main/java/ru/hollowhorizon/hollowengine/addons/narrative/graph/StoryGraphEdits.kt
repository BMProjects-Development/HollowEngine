package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** Where steps can go in the flow. */
sealed interface StoryPlace {
    /** After the step [id], with everything under it. */
    data class After(val id: String) : StoryPlace

    /** Right before the step [id]. */
    data class Before(val id: String) : StoryPlace

    /** First on the [pin] of the node [id]. */
    data class Into(val id: String, val pin: String) : StoryPlace
}

/**
 * The changes editors make to a story. Each returns the graph with the change, or the same one if it cannot be made.
 * Those that go by the steps of the list join reroutes back up, since the list has no place for them.
 */
object StoryGraphEdits {
    /** [node] placed at [place], its way out leading to what followed that place. */
    fun StoryGraph.insert(node: StoryNode, place: StoryPlace): StoryGraph {
        val plain = dissolved()
        val added = plain.copy(nodes = plain.nodes + node)
        return added.attach(node.id, exitsOf(node), place)
    }

    /** The story with the variable [old] called [name], in everything that reads or writes it. */
    fun StoryGraph.renameVariable(old: String, name: String): StoryGraph {
        val word = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|(?<![\\w.])${Regex.escape(old)}(?!\\w)")
        fun code(text: String) = word.replace(text) { if (it.value == old) name else it.value }
        fun line(text: String) = text.replace("{$old}", "{$name}")
        fun Map<String, String>.renamed() = mapValues { code(it.value) }
        return copy(
            variables = variables.map { it.copy(name = if (it.name == old) name else it.name, initial = code(it.initial)) },
            nodes = nodes.map { node ->
                when (node) {
                    is StoryNode.Say -> node.copy(speaker = line(node.speaker), text = line(node.text))
                    is StoryNode.Call -> node.copy(
                        arguments = node.arguments.renamed(),
                        groups = node.groups.mapValues { (_, group) -> group.copy(arguments = group.arguments.renamed()) },
                    )

                    is StoryNode.Choose -> node.copy(
                        options = node.options.map { it.copy(text = line(it.text), condition = it.condition?.let(::code)) },
                    )

                    is StoryNode.Branch -> node.copy(condition = code(node.condition))
                    is StoryNode.Assign -> node.copy(variable = if (node.variable == old) name else node.variable, value = code(node.value))
                    is StoryNode.Code -> node.copy(code = code(node.code), inputs = node.inputs.renamed())
                    is StoryNode.Expression -> node.copy(code = code(node.code))
                    is StoryNode.Pure -> node.copy(arguments = node.arguments.renamed())
                    is StoryNode.Loop -> node.copy(items = code(node.items))
                    is StoryNode.Start, is StoryNode.Reroute -> node
                }
            },
        )
    }

    /** A new story that starts at [name] and says nothing yet. */
    fun StoryGraph.addStart(name: String): Pair<StoryGraph, StoryNode.Start> {
        val start = StoryNode.Start(StoryGraph.newId(), name)
        return copy(nodes = nodes + start) to start
    }

    /** The step [id] gone, with everything under it; what led to it leads to what followed it. */
    fun StoryGraph.remove(id: String): StoryGraph {
        val flow = StoryFlow(this)
        val step = flow.step(id) ?: return without(setOf(id))
        val after = flow.after(step)
        val removed = flow.nodesOf(step).toSet()
        val kept = flow.graph.links.flatMap { link ->
            when {
                link.isFlow && link.to == id && link.from !in removed ->
                    if (after != null) listOf(link.copy(to = after)) else emptyList()

                else -> listOf(link)
            }
        }
        return flow.graph.copy(links = kept).without(removed)
    }

    /** The node [id] alone gone, with its links: what the graph view removes, where nothing is nested. */
    fun StoryGraph.removeNode(id: String): StoryGraph = without(setOf(id))

    /** The nodes [ids] gone with their links; a link through a reroute among them joins back up. */
    fun StoryGraph.removeNodes(ids: Set<String>): StoryGraph =
        ids.mapNotNull { node(it) as? StoryNode.Reroute }.fold(this) { graph, reroute -> graph.bridge(reroute) }.without(ids)

    private fun StoryGraph.bridge(reroute: StoryNode.Reroute): StoryGraph {
        val into = links.filter { it.to == reroute.id }
        val out = links.filter { it.from == reroute.id }
        val joined = into.flatMap { before -> out.map { after -> before.copy(to = after.to, toPin = after.toPin) } }
        return copy(links = links - (into + out).toSet() + joined)
    }

    /** Copies of [ids], [offset] away, linked as they were among themselves; a value they read they keep reading. */
    fun StoryGraph.copyNodes(ids: Set<String>, offset: StoryPoint): Pair<StoryGraph, Map<String, String>> {
        val fresh = ids.associateWith { StoryGraph.newId() }
        val copies = fresh.mapNotNull { (old, new) -> node(old)?.withId(new) }
        val kept = links.filter { it.to in ids && (it.from in ids || !it.isFlow) }
            .map { it.copy(from = fresh[it.from] ?: it.from, to = fresh.getValue(it.to)) }
        val places = fresh.mapNotNull { (old, new) ->
            layout[old]?.let { new to StoryPoint(it.x + offset.x, it.y + offset.y) }
        }
        return copy(nodes = nodes + copies, links = links + kept, layout = layout + places) to fresh
    }

    /** A new group of [ids], taken out of the groups they were in. */
    fun StoryGraph.withGroup(ids: Set<String>, title: String): Pair<StoryGraph, String> {
        val id = StoryGraph.newId()
        val out = withMembership(ids, null)
        return out.copy(groups = out.groups + StoryNodeGroup(id, title, nodes = ids.toList())) to id
    }

    /** [ids] in [group], or in no group when it is null; a group left empty is gone. */
    fun StoryGraph.withMembership(ids: Set<String>, group: String?): StoryGraph = copy(
        groups = groups.map { each ->
            val kept = each.nodes.filter { it !in ids }
            each.copy(nodes = if (each.id == group) kept + ids else kept)
        }.filter { it.nodes.isNotEmpty() },
    )

    fun StoryGraph.withGroupChanged(id: String, change: (StoryNodeGroup) -> StoryNodeGroup): StoryGraph =
        copy(groups = groups.map { if (it.id == id) change(it) else it })

    /** The group [id] gone; its nodes stay. */
    fun StoryGraph.withoutGroup(id: String): StoryGraph = copy(groups = groups.filter { it.id != id })

    /** The link out of [pin] of [from] gone: a jump taken out of the story. */
    fun StoryGraph.unlink(from: String, pin: String): StoryGraph =
        copy(links = links.filterNot { it.isFlow && it.from == from && it.fromPin == pin })

    /** Flow from [pin] of [from] goes to [to], instead of wherever it went. */
    fun StoryGraph.link(from: String, pin: String, to: String): StoryGraph =
        unlink(from, pin).let { it.copy(links = it.links + StoryLink(from, pin, to, StoryPins.IN)) }

    /** [pin] of [to] reads the output [fromPin] of [from], instead of whatever it read. */
    fun StoryGraph.linkValue(from: String, fromPin: String, to: String, pin: String): StoryGraph =
        copy(links = links.filterNot { !it.isFlow && it.to == to && it.toPin == pin } + StoryLink(from, fromPin, to, pin))

    /** [pin] of [node] no longer reads a value, and a call that only gave it that value is gone. */
    fun StoryGraph.unlinkValue(node: String, pin: String): StoryGraph {
        val link = links.firstOrNull { !it.isFlow && it.to == node && it.toPin == pin } ?: return this
        val kept = copy(links = links - link)
        val source = kept.node(link.from)
        val unused = kept.links.none { it.from == link.from || it.to == link.from }
        return if (unused && source !is StoryNode.Code) kept.without(setOf(link.from)) else kept
    }

    /** The step [id] taken out and put at [place]; the same graph when it would land inside itself. */
    fun StoryGraph.move(id: String, place: StoryPlace): StoryGraph {
        if (!canMove(id, place)) return this
        val flow = StoryFlow(this)
        val step = flow.step(id) ?: return this
        val after = flow.after(step)
        val moved = flow.nodesOf(step).toSet()
        val exits = flow.exits(step)
        val detached = flow.graph.links.mapNotNull { link ->
            when {
                link.isFlow && link.to == id && link.from !in moved ->
                    after?.let { link.copy(to = it) }

                link.isFlow && (link.from to link.fromPin) in exits -> null
                else -> link
            }
        }
        return flow.graph.copy(links = detached).attach(id, exits, place)
    }

    /** Whether the step [id] can go to [place]. */
    fun StoryGraph.canMove(id: String, place: StoryPlace): Boolean {
        val flow = StoryFlow(this)
        val step = flow.step(id) ?: return false
        val anchor = when (place) {
            is StoryPlace.After -> place.id
            is StoryPlace.Before -> place.id
            is StoryPlace.Into -> place.id
        }
        return anchor !in flow.nodesOf(step) && place != StoryPlace.Before(id)
    }

    /** A copy of the step [id] with everything under it, right after it. */
    fun StoryGraph.duplicate(id: String): StoryGraph {
        val flow = StoryFlow(this)
        val step = flow.step(id) ?: return this
        val ids = flow.nodesOf(step).associateWith { StoryGraph.newId() }
        val plain = flow.graph
        val copies = ids.mapNotNull { (old, new) -> plain.node(old)?.withId(new) }
        val inner = plain.links.filter { it.from in ids && (it.to in ids || !it.isFlow) }
            .map { it.copy(from = ids.getValue(it.from), to = ids[it.to] ?: it.to) }
        val outer = plain.links.filter { !it.isFlow && it.to in ids && it.from !in ids }
            .map { it.copy(to = ids.getValue(it.to)) }
        val copied = plain.copy(nodes = plain.nodes + copies, links = plain.links + inner + outer)
        val exits = flow.exits(step).mapNotNull { (node, pin) -> ids[node]?.let { it to pin } }
        return copied.attach(ids.getValue(id), exits, StoryPlace.After(id))
    }

    /** The node [id] as [change] leaves it. */
    fun StoryGraph.edit(id: String, change: (StoryNode) -> StoryNode): StoryGraph =
        copy(nodes = nodes.map { if (it.id == id) change(it) else it })

    /** The option [index] of the menu [id] gone, with the steps only it leads to; later options keep their steps. */
    fun StoryGraph.removeOption(id: String, index: Int): StoryGraph {
        val menu = node(id) as? StoryNode.Choose ?: return this
        val flow = StoryFlow(this)
        val branch = (flow.step(id) as? StoryStep.Split)?.branches?.getOrNull(index).orEmpty()
        val removed = branch.flatMap(flow::nodesOf).toSet()
        val pin = StoryPins.option(index)
        val shifted = flow.graph.links.filterNot { it.from == id && it.fromPin == pin }.map { link ->
            val at = if (link.from == id) StoryPins.optionIndex(link.fromPin) else null
            if (at != null && at > index) link.copy(fromPin = StoryPins.option(at - 1)) else link
        }
        return flow.graph.copy(links = shifted).without(removed)
            .edit(id) { menu.copy(options = menu.options.filterIndexed { at, _ -> at != index }) }
    }

    /** The pins a new [node] leaves by. */
    private fun exitsOf(node: StoryNode): List<Pair<String, String>> = when (node) {
        is StoryNode.Branch -> listOf(node.id to StoryPins.THEN, node.id to StoryPins.ELSE)
        is StoryNode.Choose -> node.options.indices.map { node.id to StoryPins.option(it) }
        else -> listOf(node.id to StoryPins.NEXT)
    }

    /** Leads flow at [place] into [head] and out of [exits] to what was there. */
    private fun StoryGraph.attach(head: String, exits: List<Pair<String, String>>, place: StoryPlace): StoryGraph {
        val flow = StoryFlow(this)
        val entrances: List<Pair<String, String>>
        val following: String?
        when (place) {
            is StoryPlace.After -> {
                val step = flow.step(place.id)
                entrances = step?.let(flow::exits) ?: listOf(place.id to StoryPins.NEXT)
                following = step?.let(flow::after) ?: flow.target(place.id, StoryPins.NEXT)
            }

            is StoryPlace.Before -> {
                entrances = flow.incoming(place.id).map { it.from to it.fromPin }
                following = place.id
            }

            is StoryPlace.Into -> {
                entrances = listOf(place.id to place.pin)
                following = flow.target(place.id, place.pin)
            }
        }
        var result = this
        entrances.forEach { (node, pin) -> result = result.link(node, pin, head) }
        if (following != null) exits.forEach { (node, pin) -> result = result.link(node, pin, following) }
        return result
    }

    private fun StoryGraph.without(ids: Set<String>): StoryGraph = copy(
        nodes = nodes.filterNot { it.id in ids },
        links = links.filterNot { it.from in ids || it.to in ids },
        layout = layout - ids,
        collapsed = collapsed - ids,
    ).withMembership(ids, null)

    private fun StoryNode.withId(id: String): StoryNode = when (this) {
        is StoryNode.Start -> copy(id = id)
        is StoryNode.Say -> copy(id = id)
        is StoryNode.Call -> copy(id = id)
        is StoryNode.Choose -> copy(id = id)
        is StoryNode.Branch -> copy(id = id)
        is StoryNode.Assign -> copy(id = id)
        is StoryNode.Code -> copy(id = id)
        is StoryNode.Expression -> copy(id = id)
        is StoryNode.Pure -> copy(id = id)
        is StoryNode.Loop -> copy(id = id)
        is StoryNode.Reroute -> copy(id = id)
    }
}
