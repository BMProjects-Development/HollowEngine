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
            variables = variables.map {
                it.copy(
                    name = if (it.name == old) name else it.name,
                    initial = code(it.initial)
                )
            },
            nodes = nodes.map { node ->
                when (node) {
                    is StoryNode.Say -> node.copy(speaker = line(node.speaker), text = line(node.text))
                    is StoryNode.Call -> node.copy(
                        arguments = node.arguments.renamed(),
                        groups = node.groups.mapValues { (_, group) -> group.copy(arguments = group.arguments.renamed()) },
                    )

                    is StoryNode.Choose -> node.copy(
                        options = node.options.map {
                            it.copy(
                                text = line(it.text),
                                condition = it.condition?.let(::code)
                            )
                        },
                    )

                    is StoryNode.Branch -> node.copy(condition = code(node.condition))
                    is StoryNode.Assign -> node.copy(
                        variable = if (node.variable == old) name else node.variable,
                        value = code(node.value)
                    )

                    is StoryNode.Code -> node.copy(code = code(node.code), inputs = node.inputs.renamed())
                    is StoryNode.Expression -> node.copy(code = code(node.code))
                    is StoryNode.Pure -> node.copy(arguments = node.arguments.renamed())
                    is StoryNode.Loop -> node.copy(items = code(node.items))
                    is StoryNode.Return -> node.copy(values = node.values.renamed())
                    is StoryNode.Invoke -> node.copy(arguments = node.arguments.renamed())
                    is StoryNode.Function -> node.copy(body = node.body.renameVariable(old, name))
                    is StoryNode.Start, is StoryNode.Parameters, is StoryNode.Signal, is StoryNode.Reroute -> node
                }
            },
        )
    }

    /** The nodes [ids] gone with their links; a link through a reroute among them joins back up. */
    fun StoryGraph.removeNodes(ids: Set<String>): StoryGraph =
        ids.mapNotNull { node(it) as? StoryNode.Reroute }.fold(this) { graph, reroute -> graph.bridge(reroute) }
            .without(ids)

    private fun StoryGraph.bridge(reroute: StoryNode.Reroute): StoryGraph {
        val into = links.filter { it.to == reroute.id }
        val out = links.filter { it.from == reroute.id }
        val joined = into.flatMap { before -> out.map { after -> before.copy(to = after.to, toPin = after.toPin) } }
        return copy(links = links - (into + out).toSet() + joined)
    }

    /** A name for a function that none of [taken] has: two functions of one name would be one stage. */
    fun freeFunctionName(taken: Set<String>): String =
        generateSequence(1) { it + 1 }.map { "function$it" }.first { it !in taken }

    /** A new function, whose body starts at the block of its parameters. */
    fun StoryGraph.newFunction(): StoryNode.Function {
        val entry = StoryNode.Parameters(StoryGraph.newId())
        val body = StoryGraph(nodes = listOf(entry), layout = mapOf(entry.id to StoryPoint(0f, 0f)))
        return StoryNode.Function(StoryGraph.newId(), freeFunctionName(functions.mapTo(HashSet()) { it.name }), body = body)
    }

    /**
     * The story with a value of the function [id] called [name] instead of [old], in everything that reads or gives it:
     * the pins of its body and of its calls in the story.
     *
     * @param result whether it is a result rather than a parameter.
     */
    fun StoryGraph.renameValue(id: String, old: String, name: String, result: Boolean): StoryGraph =
        retargetValue(id, old, name, result)

    /** The story without the value [name] of the function [id], and without what was linked to it. */
    fun StoryGraph.removeValue(id: String, name: String, result: Boolean): StoryGraph =
        retargetValue(id, name, null, result)

    /** A new parameter or result of the function [id], of a name it does not have yet. */
    fun StoryGraph.addValue(id: String, result: Boolean): StoryGraph = edit(id) { node ->
        node as StoryNode.Function
        val value = StoryTypedName(freeValueName(node, "value"), "Int")
        if (result) node.copy(results = node.results + value) else node.copy(params = node.params + value)
    }

    /** A new flow of the function [id], which its calls link on from. */
    fun StoryGraph.addFlow(id: String): StoryGraph = edit(id) { node ->
        node as StoryNode.Function
        node.copy(flows = node.flows + freeValueName(node, "flow"))
    }

    /**
     * The story with the flow [old] of the function [id] called [name], or gone when it is null, in the signals of its
     * body and the links out of its calls.
     */
    fun StoryGraph.renameFlow(id: String, old: String, name: String?): StoryGraph {
        val function = node(id) as? StoryNode.Function ?: return this
        val body = function.body.copy(
            nodes = function.body.nodes.map { if (it is StoryNode.Signal && it.flow == old && name != null) it.copy(flow = name) else it },
        )
        val changed = function.copy(flows = function.flows.mapNotNull { if (it == old) name else it }, body = body)
        val calls = callsOf(function)

        fun StoryGraph.callsChanged(): StoryGraph = copy(
            nodes = nodes.map { if (it is StoryNode.Function) it.copy(body = it.body.callsChanged()) else it },
            links = links.mapNotNull { link ->
                when {
                    link.from !in calls || link.fromPin != old -> link
                    name == null -> null
                    else -> link.copy(fromPin = name)
                }
            },
        )
        return edit(id) { changed }.callsChanged()
    }

    /** A name of [prefix] and a number that no parameter, result or flow of [function] has. */
    private fun freeValueName(function: StoryNode.Function, prefix: String): String {
        val taken = (function.params + function.results).mapTo(HashSet()) { it.name } + function.flows
        return generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in taken }
    }

    /** The ids of the calls of [function] in the story. */
    private fun StoryGraph.callsOf(function: StoryNode.Function): Set<String> = flattened().nodes
        .filterIsInstance<StoryNode.Invoke>().filter { it.file == null && it.function == function.name }.mapTo(HashSet()) { it.id }

    /** The story with the function [id] called [name], and the calls of it in the story too. */
    fun StoryGraph.renameFunction(id: String, name: String): StoryGraph {
        val old = (node(id) as? StoryNode.Function)?.name ?: return this
        fun StoryGraph.callsRenamed(): StoryGraph = copy(
            nodes = nodes.map { node ->
                when {
                    node is StoryNode.Invoke && node.file == null && node.function == old -> node.copy(function = name)
                    node is StoryNode.Function -> node.copy(
                        name = if (node.id == id) name else node.name,
                        body = node.body.callsRenamed(),
                    )

                    else -> node
                }
            },
        )
        return callsRenamed()
    }

    /** The value [old] of the function [id] called [name], or gone when it is null, with its pins wherever they are. */
    private fun StoryGraph.retargetValue(id: String, old: String, name: String?, result: Boolean): StoryGraph {
        val function = node(id) as? StoryNode.Function ?: return this
        fun List<StoryTypedName>.renamed() =
            mapNotNull { if (it.name != old) it else name?.let { renamed -> it.copy(name = renamed) } }

        fun <T> Map<String, T>.renamed(): Map<String, T> {
            val value = this[old] ?: return this
            return if (name == null) this - old else this - old + (name to value)
        }

        fun List<StoryLink>.renamed(ends: Set<String>, output: Boolean): List<StoryLink> = mapNotNull { link ->
            val end = if (output) link.from to link.fromPin else link.to to link.toPin
            when {
                end.first !in ends || end.second != old -> link
                name == null -> null
                output -> link.copy(fromPin = name)
                else -> link.copy(toPin = name)
            }
        }

        val entries = function.body.nodes.filter { it is StoryNode.Parameters || it is StoryNode.Return }
            .mapTo(HashSet()) { it.id }
        val body = function.body.copy(
            nodes = function.body.nodes.map { if (it is StoryNode.Return && result) it.copy(values = it.values.renamed()) else it },
            links = function.body.links.renamed(entries, output = !result),
        )
        val changed = function.copy(
            params = if (result) function.params else function.params.renamed(),
            results = if (result) function.results.renamed() else function.results,
            body = body,
        )
        val calls = callsOf(function)

        fun StoryGraph.callsChanged(): StoryGraph = copy(
            nodes = nodes.map { node ->
                when {
                    node is StoryNode.Invoke && node.id in calls && !result -> node.copy(arguments = node.arguments.renamed())
                    node is StoryNode.Function -> node.copy(body = node.body.callsChanged())
                    else -> node
                }
            },
            links = links.renamed(calls, output = result),
        )
        return edit(id) { changed }.callsChanged()
    }

    /**
     * Copies of [ids], [offset] away, linked as they were among themselves; a value they read they keep reading. They
     * stay collapsed if they were, and a function gets a name of its own.
     */
    fun StoryGraph.copyNodes(ids: Set<String>, offset: StoryPoint): Pair<StoryGraph, Map<String, String>> {
        val fresh = ids.associateWith { StoryGraph.newId() }
        val taken = functions.mapTo(HashSet()) { it.name }
        val copies = fresh.mapNotNull { (old, new) -> node(old)?.withId(new) }.map { copy ->
            if (copy is StoryNode.Function) copy.copy(name = freeFunctionName(taken).also(taken::add)) else copy
        }
        val kept = links.filter { it.to in ids && (it.from in ids || !it.isFlow) }
            .map { it.copy(from = fresh[it.from] ?: it.from, to = fresh.getValue(it.to)) }
        val places = fresh.mapNotNull { (old, new) ->
            layout[old]?.let { new to StoryPoint(it.x + offset.x, it.y + offset.y) }
        }
        val folded = fresh.filterKeys { it in collapsed }.values
        return copy(
            nodes = nodes + copies,
            links = links + kept,
            layout = layout + places,
            collapsed = collapsed + folded,
        ) to fresh
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
        copy(links = links.filterNot { !it.isFlow && it.to == to && it.toPin == pin } + StoryLink(
            from,
            fromPin,
            to,
            pin
        ))

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

    /** One more input on the variadic operator of [id]. */
    fun StoryGraph.addInput(id: String): StoryGraph = edit(id) { node ->
        node as StoryNode.Pure
        node.copy(count = StoryOperators.inputsOf(node).size + 1)
    }

    /** The node [id] without its input at [index]; those after it move up with what they hold and read. */
    fun StoryGraph.removeInput(id: String, index: Int): StoryGraph {
        val node = node(id) as? StoryNode.Pure ?: return this
        val size = StoryOperators.inputsOf(node).size
        val moved =
            ((index + 1) until size).associate { StoryOperators.inputName(it) to StoryOperators.inputName(it - 1) }
        val gone = StoryOperators.inputName(index)
        val shifted = links.filterNot { it.to == id && it.toPin == gone }.map { link ->
            if (link.to == id) link.copy(toPin = moved[link.toPin] ?: link.toPin) else link
        }
        val held = (node.arguments - gone).mapKeys { (name, _) -> moved[name] ?: name }
        return copy(links = shifted).edit(id) { node.copy(arguments = held, count = size - 1) }
    }

    private fun StoryGraph.without(ids: Set<String>): StoryGraph = copy(
        nodes = nodes.filterNot { it.id in ids },
        links = links.filterNot { it.from in ids || it.to in ids },
        layout = layout - ids,
        collapsed = collapsed - ids,
    ).withMembership(ids, null)

    /** The graph with a new id for every node, linked, laid out and grouped as before: ids are unique in a whole file. */
    private fun StoryGraph.reidentified(): StoryGraph {
        val fresh = nodes.associate { it.id to StoryGraph.newId() }
        fun id(old: String) = fresh[old] ?: old
        return copy(
            nodes = nodes.map { it.withId(id(it.id)) },
            links = links.map { it.copy(from = id(it.from), to = id(it.to)) },
            layout = layout.mapKeys { id(it.key) },
            groups = groups.map { group -> group.copy(id = StoryGraph.newId(), nodes = group.nodes.map(::id)) },
            collapsed = collapsed.mapTo(HashSet(), ::id),
        )
    }

    private fun StoryNode.withId(id: String): StoryNode = when (this) {
        is StoryNode.Start -> copy(id = id)
        is StoryNode.Function -> copy(id = id, body = body.reidentified())
        is StoryNode.Parameters -> copy(id = id)
        is StoryNode.Return -> copy(id = id)
        is StoryNode.Signal -> copy(id = id)
        is StoryNode.Invoke -> copy(id = id)
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
