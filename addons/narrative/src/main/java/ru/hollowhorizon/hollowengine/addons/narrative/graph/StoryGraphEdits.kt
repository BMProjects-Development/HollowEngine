package ru.hollowhorizon.hollowengine.addons.narrative.graph

/**
 * The changes editors make to a story. Each returns the graph with the change, or the same one if it cannot be made.
 */
object StoryGraphEdits {
    private val INSERT = Regex("""\{([^{}]*)\}""")

    /** The story with the variable [old] called [name], in everything that reads or writes it. */
    fun StoryGraph.renameVariable(old: String, name: String): StoryGraph {
        fun code(text: String) = StoryKotlin.renamed(text, old, name)
        fun line(text: String) = INSERT.replace(text) { "{${code(it.groupValues[1])}}" }
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

    /** The node [id] as [change] leaves it. */
    fun StoryGraph.edit(id: String, change: (StoryNode) -> StoryNode): StoryGraph =
        copy(nodes = nodes.map { if (it.id == id) change(it) else it })

    /** The option [index] of the menu [id] gone with its link; later options keep theirs. */
    fun StoryGraph.removeOption(id: String, index: Int): StoryGraph {
        val menu = node(id) as? StoryNode.Choose ?: return this
        val pin = StoryPins.option(index)
        val shifted = links.filterNot { it.from == id && it.fromPin == pin }.map { link ->
            val at = if (link.from == id) StoryPins.optionIndex(link.fromPin) else null
            if (at != null && at > index) link.copy(fromPin = StoryPins.option(at - 1)) else link
        }
        return copy(links = shifted).edit(id) { menu.copy(options = menu.options.filterIndexed { at, _ -> at != index }) }
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
