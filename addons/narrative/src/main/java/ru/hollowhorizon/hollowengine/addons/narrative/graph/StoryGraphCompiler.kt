package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** What is wrong with the node [node], or with the graph as a whole when it is null. */
data class StoryGraphProblem(val node: String?, val message: String)

/** A generated script; [nodes] holds the node each of its lines came from. */
data class GeneratedStory(val code: String, val nodes: List<String?>, val problems: List<StoryGraphProblem>) {
    /** The node behind the 1-based [line] of the script, for reporting what the Kotlin compiler says about it. */
    fun nodeAt(line: Int): String? = nodes.getOrNull(line - 1)
}

/** How a call behaves when a restart interrupts the story. */
enum class StoryCallKind {
    /** Cannot suspend, so nothing can happen between it and the steps next to it. */
    INSTANT,

    /** An `@Action`: the stage compiler already keeps it from repeating once done. */
    ACTION,

    /** May suspend with nothing to mark how far it got, such as `say` waiting to be read. */
    SUSPENDING,
}

/**
 * Generates a script of stages from a [StoryGraph]. Flow runs through a stage of its own from every node several
 * links lead to, named after that node: a loop is then a tail call, and a save keeps pointing at the same stage when
 * nodes around it move. A stage that returned is not repeated after a restart, so steps that can be interrupted get
 * one of their own and those in a row that cannot share one: a story goes on from the line it was at and `x = x + 1`
 * never counts twice.
 */
class StoryGraphCompiler private constructor(
    private val graph: StoryGraph,
    private val kindOf: (StoryNode.Call) -> StoryCallKind,
) {
    private val problems = ArrayList<StoryGraphProblem>()
    private val nodes = HashMap<String, StoryNode>()
    private val flow = HashMap<Pair<String, String>, String>()
    private val incoming = HashMap<String, Int>()
    private val values = HashMap<Pair<String, String>, StoryLink>()
    private val read = HashSet<Pair<String, String>>()
    private val variables = graph.variables.mapTo(HashSet()) { it.name }

    private val lines = ArrayList<String>()
    private val origins = ArrayList<String?>()
    private val imports = (DEFAULT_IMPORTS + graph.imports).toSortedSet()
    private val pending = ArrayDeque<() -> Unit>()
    private val queued = HashSet<String>()
    private val evaluating = HashSet<String>()

    /** How many blocks the flow is inside of: a block is one lambda, which steps cannot be split out of. */
    private var blocks = 0

    private fun generate(): GeneratedStory {
        index()
        val body = render()
        val header = imports.map { "import $it" } + graph.variables.map(::declaration) + outputDeclarations()
        return GeneratedStory(
            code = (header + body.first).joinToString("\n", postfix = "\n"),
            nodes = header.map { null } + body.second,
            problems = problems,
        )
    }

    private fun index() {
        graph.nodes.forEach { node ->
            if (!IDENTIFIER_PART.matches(node.id)) problem(node.id, "A node id may only hold letters, digits and _")
            if (nodes.put(node.id, node) != null) problem(node.id, "Two nodes share this id")
        }
        graph.links.forEach { link ->
            when {
                link.from !in nodes || link.to !in nodes -> problem(null, "A link joins a missing node: $link")
                link.isFlow -> {
                    if (flow.put(link.from to link.fromPin, link.to) != null) {
                        problem(link.from, "Pin '${link.fromPin}' continues to several nodes")
                    }
                    incoming.merge(link.to, 1, Int::plus)
                }

                else -> {
                    if (values.put(link.to to link.toPin, link) != null) {
                        problem(link.to, "Pin '${link.toPin}' reads several values")
                    }
                    read += link.from to link.fromPin
                }
            }
        }
    }

    private fun render(): Pair<List<String>, List<String?>> {
        val starts = graph.nodes.filterIsInstance<StoryNode.Start>().sortedBy { it.id }
        if (starts.map { it.name }.distinct().size != starts.size) problem(null, "Two starts share a name")
        starts.forEach { start ->
            if (!IDENTIFIER.matches(start.name)) problem(start.id, "'${start.name}' is not a name of a stage")
            stage(start.name, start.id) { chain(flow[start.id to StoryPins.NEXT], 1) }
        }
        while (pending.isNotEmpty()) pending.removeFirst()()
        return lines to origins
    }

    private fun stage(name: String, node: String, body: () -> Unit) {
        emit("", null)
        emit("@State", node)
        emit("suspend fun $name() {", node)
        body()
        emit("}", node)
    }

    /** Emits the flow from [start]. A node reached by several links is entered through its stage. */
    private fun chain(start: String?, depth: Int) {
        val id = start ?: return
        val node = nodes[id] ?: return
        when {
            node is StoryNode.Start -> emit("${node.name}()", id, depth)
            incoming.getOrDefault(id, 0) > 1 -> {
                if (queued.add(id)) pending += { stage(stageName(id), id) { step(id, 1) } }
                emit("${stageName(id)}()", id, depth)
            }

            else -> step(id, depth)
        }
    }

    private fun step(id: String, depth: Int) {
        when (val node = nodes.getValue(id)) {
            is StoryNode.Start -> Unit
            is StoryNode.Expression -> problem(id, "An expression has no place in the flow")
            is StoryNode.Say -> {
                boundary(id, depth) { emit(sayOf(node), id, it) }
                chain(flow[id to StoryPins.NEXT], depth)
            }

            is StoryNode.Call -> {
                when {
                    isInstant(node) -> return instantRun(node, depth)
                    kindOf(node) == StoryCallKind.ACTION -> call(node, depth)
                    else -> boundary(id, depth) { call(node, it) }
                }
                chain(flow[id to StoryPins.NEXT], depth)
            }

            is StoryNode.Assign -> instantRun(node, depth)

            is StoryNode.Code -> {
                boundary(id, depth) { code(node, it) }
                chain(flow[id to StoryPins.NEXT], depth)
            }

            is StoryNode.Branch -> {
                emit("if (${argument(id, StoryPins.CONDITION, node.condition)}) {", id, depth)
                chain(flow[id to StoryPins.THEN], depth + 1)
                val otherwise = flow[id to StoryPins.ELSE]
                if (otherwise != null) {
                    emit("} else {", id, depth)
                    chain(otherwise, depth + 1)
                }
                emit("}", id, depth)
            }

            is StoryNode.Choose -> {
                emit("when (${chooseOf(node)}) {", id, depth)
                node.options.indices.forEach { index ->
                    emit("$index -> {", id, depth + 1)
                    chain(flow[id to StoryPins.option(index)], depth + 2)
                    emit("}", id, depth + 1)
                }
                emit("}", id, depth)
            }
        }
    }

    /**
     * Puts what [body] emits in a stage of its own and calls it; inside a block it stays in place. [body] gets the
     * depth it is emitted at.
     */
    private fun boundary(node: String, depth: Int, body: (Int) -> Unit) {
        if (blocks > 0) return body(depth)
        val name = "step_$node"
        emit("$name()", node, depth)
        pending += { stage(name, node) { body(1) } }
    }

    private fun isInstant(node: StoryNode): Boolean = when (node) {
        is StoryNode.Assign -> true
        is StoryNode.Call -> !node.block && kindOf(node) == StoryCallKind.INSTANT
        else -> false
    }

    /** Puts [first] and the instant steps right after it in one stage: nothing can interrupt it halfway. */
    private fun instantRun(first: StoryNode, depth: Int) {
        val run = arrayListOf(first)
        var next = flow[first.id to StoryPins.NEXT]
        while (true) {
            val node = next?.let(nodes::get) ?: break
            if (!isInstant(node) || incoming.getOrDefault(node.id, 0) > 1) break
            run += node
            next = flow[node.id to StoryPins.NEXT]
        }
        boundary(first.id, depth) { at -> run.forEach { instant(it, at) } }
        chain(next, depth)
    }

    private fun instant(node: StoryNode, depth: Int) {
        if (node is StoryNode.Call) return call(node, depth)
        node as StoryNode.Assign
        if (node.variable !in variables) problem(node.id, "There is no variable '${node.variable}'")
        emit("${node.variable} = ${argument(node.id, StoryPins.VALUE, node.value)}", node.id, depth)
    }

    private fun call(node: StoryNode.Call, depth: Int) {
        val call = callOf(node)
        val assigned = if (isRead(node.id, StoryPins.VALUE)) {
            if (node.returns == null) problem(node.id, "'${node.function}' is read later, so it needs its return type")
            "${outputName(node.id, StoryPins.VALUE)} = $call"
        } else call
        if (!node.block) return emit(assigned, node.id, depth)
        emit("$assigned {", node.id, depth)
        blocks++
        chain(flow[node.id to StoryPins.BODY], depth + 1)
        blocks--
        emit("}", node.id, depth)
    }

    private fun code(node: StoryNode.Code, depth: Int) {
        node.inputs.forEach { (name, value) -> emit("val $name = ${argument(node.id, name, value)}", node.id, depth) }
        node.outputs.forEach { (name, type) -> emit("var $name: ${nullable(type)} = null", node.id, depth) }
        node.code.trimIndent().lines().forEach { emit(it, node.id, depth) }
        node.outputs.keys.filter { isRead(node.id, it) }
            .forEach { emit("${outputName(node.id, it)} = $it", node.id, depth) }
    }

    private fun sayOf(node: StoryNode.Say): String {
        val link = values[node.id to StoryPins.SPEAKER]
        val written = node.speaker.trim()
        val speaker = when {
            link != null -> value(link)
            written.isEmpty() -> "null as String?"
            written.startsWith("{") && written.endsWith("}") -> written.substring(1, written.length - 1)
            else -> template(written)
        }
        return "say($speaker, ${template(node.text)})"
    }

    private fun chooseOf(node: StoryNode.Choose): String {
        if (node.options.none { it.condition != null }) {
            return "choose(listOf(${node.options.joinToString { template(it.text) }}))"
        }
        return "chooseIf(listOf(${
            node.options.joinToString { "${template(it.text)} to (${it.condition?.ifBlank { null } ?: "true"})" }
        }))"
    }

    private fun callOf(node: StoryNode.Call): String {
        val name = node.function.substringAfterLast('.')
        if ('.' in node.function) imports += node.function
        val receiverPin = node.receiver ?: StoryPins.RECEIVER
        val parameters = (node.arguments.keys + values.keys.filter { it.first == node.id }.map { it.second })
            .filter { it != receiverPin }.distinct()
        val direct = parameters.map { "$it = ${argument(node.id, it, node.arguments[it].orEmpty())}" }
        val groups = node.groups.filterValues { it.arguments.isNotEmpty() }.map { (parameter, group) ->
            val fields = group.arguments.entries.joinToString { (field, value) -> "$field = $value" }
            "$parameter = ${group.constructor}($fields)"
        }
        val arguments = (direct + groups).joinToString()
        val receiver = node.arguments[receiverPin] ?: values[node.id to receiverPin]?.let(::value)
        return if (receiver == null) "$name($arguments)" else "($receiver).$name($arguments)"
    }

    private fun argument(node: String, pin: String, written: String): String {
        val link = values[node to pin]
        if (link != null) return value(link)
        if (written.isBlank()) problem(node, "Pin '$pin' has no value")
        return written
    }

    private fun value(link: StoryLink): String {
        val id = link.from
        if (!evaluating.add(id)) {
            problem(id, "This node reads its own value")
            return "error(\"cycle\")"
        }
        try {
            return when (val node = nodes.getValue(id)) {
                is StoryNode.Expression -> "(${node.code})"
                is StoryNode.Call -> if (isInFlow(id)) "${outputName(id, StoryPins.VALUE)}!!" else callOf(node)
                is StoryNode.Code -> {
                    if (link.fromPin !in node.outputs) problem(id, "There is no output '${link.fromPin}'")
                    "${outputName(id, link.fromPin)}!!"
                }

                else -> {
                    problem(id, "This node gives no value")
                    "error(\"no value\")"
                }
            }
        } finally {
            evaluating -= id
        }
    }

    /** [text] as a Kotlin string that puts in the variables it names; anything else in braces stays as it is. */
    private fun template(text: String): String {
        val parts = StringBuilder("\"")
        var from = 0
        NAME_IN_BRACES.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val value = when (name) {
                in variables -> name
                PLAYER -> "playerName()"
                else -> return@forEach
            }
            parts.append(escape(text.substring(from, match.range.first))).append("\${").append(value).append('}')
            from = match.range.last + 1
        }
        return parts.append(escape(text.substring(from))).append('"').toString()
    }

    private fun declaration(variable: StoryVariable): String {
        if (!IDENTIFIER.matches(variable.name)) problem(null, "'${variable.name}' is not a name of a variable")
        val type = variable.type?.let { "<$it>" }.orEmpty()
        return "var ${variable.name} by saved$type(${variable.initial})"
    }

    /** Results of the flow live in saved variables, because any of them may be read from another stage. */
    private fun outputDeclarations(): List<String> = graph.nodes.sortedBy { it.id }.flatMap { node ->
        val outputs = when (node) {
            is StoryNode.Call -> if (isInFlow(node.id)) mapOf(StoryPins.VALUE to node.returns) else emptyMap()
            is StoryNode.Code -> node.outputs
            else -> emptyMap()
        }
        outputs.filter { isRead(node.id, it.key) }.map { (pin, type) ->
            "var ${outputName(node.id, pin)} by saved<${nullable(type ?: "Any")}>(null)"
        }
    }

    private fun isInFlow(node: String) = node in incoming || flow.keys.any { it.first == node }

    private fun isRead(node: String, pin: String) = (node to pin) in read

    private fun nullable(type: String) = if (type.endsWith("?")) type else "$type?"

    private fun stageName(node: String) = "block_$node"

    private fun outputName(node: String, pin: String) = "out_${node}_$pin"

    private fun emit(line: String, node: String?, depth: Int = 0) {
        lines += if (line.isEmpty()) "" else "    ".repeat(depth) + line
        origins += node
    }

    private fun problem(node: String?, message: String) {
        problems += StoryGraphProblem(node, message)
    }

    companion object {
        private const val PLAYER = "player"
        private const val NARRATIVE = "ru.hollowhorizon.hollowengine.addons.narrative"
        private val DEFAULT_IMPORTS = listOf(
            "$NARRATIVE.story.*",
            "$NARRATIVE.npcs.*",
            "$NARRATIVE.speech.*",
            "$NARRATIVE.poll.*",
            "$NARRATIVE.scene.commands.*",
        )
        private val IDENTIFIER_PART = Regex("[\\p{L}\\p{N}_]+")
        private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
        private val NAME_IN_BRACES = Regex("\\{([\\p{L}_][\\p{L}\\p{N}_]*)}")

        /** @param kindOf what is known of a call; one nothing is known of is treated as able to suspend. */
        fun compile(
            graph: StoryGraph,
            kindOf: (StoryNode.Call) -> StoryCallKind = { StoryCallKind.SUSPENDING },
        ): GeneratedStory = StoryGraphCompiler(graph, kindOf).generate()

        /** [text] as a Kotlin string literal. */
        fun literal(text: String): String = "\"${escape(text)}\""

        private fun escape(text: String): String = buildString {
            text.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
