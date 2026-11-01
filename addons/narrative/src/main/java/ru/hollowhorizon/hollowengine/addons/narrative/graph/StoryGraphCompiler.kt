package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.world.entity.Entity
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/** What is wrong with the node [node], or with the graph as a whole when it is null. A [warning] lets it run. */
data class StoryGraphProblem(val node: String?, val message: String, val warning: Boolean = false)

/** A generated script; [nodes] holds the node each of its lines came from. */
data class GeneratedStory(val code: String, val nodes: List<String?>, val problems: List<StoryGraphProblem>) {
    /** The node behind the 1-based [line] of the script, for reporting what the Kotlin compiler says about it. */
    fun nodeAt(line: Int): String? = nodes.getOrNull(line - 1)
}

/**
 * Generates a script of stages from a [StoryGraph]. Flow runs through a stage of its own from every node several
 * links lead to, named after that node: a loop is then a tail call, and a save keeps pointing at the same stage when
 * nodes around it move. A stage that returned is not repeated after a restart, so steps that can be interrupted get
 * one of their own and those in a row that cannot share one: a story goes on from the line it was at and `x = x + 1`
 * never counts twice.
 */
class StoryGraphCompiler private constructor(
    source: StoryGraph,
    private val functions: StoryFunctions,
    private val detached: Boolean = false,
) {
    /** A value as generated Kotlin; [nullable] when it can be null by what it is rather than by a mistake. */
    private class Value(val code: String, val nullable: Boolean = false) {
        val present: String get() = if (nullable) "($code)!!" else code
    }

    private val graph = source.dissolved().let { plain ->
        plain.copy(nodes = plain.nodes.map { if (it is StoryNode.Call) functions.resolved(it) else it })
    }
    private val problems = ArrayList<StoryGraphProblem>()
    private val nodes = HashMap<String, StoryNode>()
    private val index =
        StoryLinkIndex(graph.links.filter { link -> graph.node(link.from) != null && graph.node(link.to) != null })
    private val flow = index.flow
    private val values = index.values
    private val variables = graph.variables.associateBy { it.name }

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

    /** What each output worked out on the spot reads, in Kotlin that stands on its own outside of the flow. */
    private fun pinValues(): Map<Pair<String, String>, String> {
        graph.nodes.forEach { nodes[it.id] = it }
        return graph.nodes.flatMap { node -> computedPins(node).map { node.id to it } }
            .associateWith { (node, pin) -> value(StoryLink(node, pin, node, pin)).present }
    }

    private fun computedPins(node: StoryNode): List<String> = when (node) {
        is StoryNode.Pure -> StoryOperators.find(node.operator)?.outputs?.map { it.name }.orEmpty()
        is StoryNode.Loop -> listOf(StoryPins.ITEM)
        is StoryNode.Expression -> listOf(StoryPins.VALUE)
        is StoryNode.Code -> if (index.isInFlow(node.id)) emptyList() else listOf(StoryPins.VALUE)
        else -> emptyList()
    }

    private fun index() {
        graph.nodes.forEach { node ->
            if (!IDENTIFIER_PART.matches(node.id)) problem(node.id, "A node id may only hold letters, digits and _")
            if (nodes.put(node.id, node) != null) problem(node.id, "Two nodes share this id")
        }
        graph.links.filter { it.from !in nodes || it.to !in nodes }
            .forEach { problem(null, "A link joins a missing node: $it") }
        graph.links.filter { it.isFlow }.groupBy { it.from to it.fromPin }.filterValues { it.size > 1 }
            .forEach { (pin, _) ->
                problem(pin.first, "Pin '${pin.second}' continues to several nodes")
            }
        graph.links.filter { !it.isFlow }.groupBy { it.to to it.toPin }.filterValues { it.size > 1 }
            .forEach { (pin, _) ->
                problem(pin.first, "Pin '${pin.second}' reads several values")
            }
        graph.nodes.filterIsInstance<StoryNode.Call>().filter { it.function.isEmpty() }.forEach { call ->
            problem(call.id, "There is no command '${call.action}'")
        }
        problems += StoryValueFlow(graph, index).problems()
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
            index.flowIn(id).size > 1 -> {
                if (queued.add(id)) pending += { stage(stageName(id), id) { step(id, 1) } }
                emit("${stageName(id)}()", id, depth)
            }

            else -> step(id, depth)
        }
    }

    private fun step(id: String, depth: Int) {
        when (val node = nodes.getValue(id)) {
            is StoryNode.Start, is StoryNode.Reroute -> Unit
            is StoryNode.Expression, is StoryNode.Pure -> problem(id, "A value has no place in the flow")
            is StoryNode.Say -> {
                boundary(id, depth) { emit(sayOf(node), id, it) }
                chain(flow[id to StoryPins.NEXT], depth)
            }

            is StoryNode.Call -> {
                when {
                    isInstant(node) -> return instantRun(node, depth)
                    functions.kind(node) == StoryCallKind.ACTION -> call(node, depth)
                    else -> boundary(id, depth) { call(node, it) }
                }
                chain(flow[id to StoryPins.NEXT], depth)
            }

            is StoryNode.Assign -> {
                if (isInstant(node)) return instantRun(node, depth)
                boundary(id, depth) { instant(node, it) }
                chain(flow[id to StoryPins.NEXT], depth)
            }

            is StoryNode.Loop -> {
                boundary(id, depth) { at -> loop(node, at) }
                chain(flow[id to StoryPins.NEXT], depth)
            }

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
        is StoryNode.Assign -> !readsSuspending(node.id)
        is StoryNode.Call -> !node.block && functions.kind(node) == StoryCallKind.INSTANT && !readsSuspending(node.id)
        else -> false
    }

    /** Whether working out what [id] reads calls something that can suspend, which a call with no flow does where it is read. */
    private fun readsSuspending(id: String): Boolean {
        val seen = hashSetOf(id)
        val queue = ArrayDeque(index.valuesInto(id))
        while (queue.isNotEmpty()) {
            val source = queue.removeFirst().from
            if (index.isInFlow(source) || !seen.add(source)) continue
            val node = nodes[source]
            if (node is StoryNode.Code) return true
            if (node is StoryNode.Call && (node.block || functions.kind(node) != StoryCallKind.INSTANT)) return true
            queue += index.valuesInto(source)
        }
        return false
    }

    /** Puts [first] and the instant steps right after it in one stage: nothing can interrupt it halfway. */
    private fun instantRun(first: StoryNode, depth: Int) {
        val run = arrayListOf(first)
        var next = flow[first.id to StoryPins.NEXT]
        while (true) {
            val node = next?.let(nodes::get) ?: break
            if (!isInstant(node) || index.flowIn(node.id).size > 1) break
            run += node
            next = flow[node.id to StoryPins.NEXT]
        }
        boundary(first.id, depth) { at -> run.forEach { instant(it, at) } }
        chain(next, depth)
    }

    private fun instant(node: StoryNode, depth: Int) {
        if (node is StoryNode.Call) return call(node, depth)
        node as StoryNode.Assign
        emit("${node.variable} = ${assigned(node)}", node.id, depth)
    }

    /** What the variable of [node] is set to. */
    private fun assigned(node: StoryNode.Assign): String {
        if (node.variable !in variables) problem(node.id, "There is no variable '${node.variable}'")
        val type = variables[node.variable]?.type
        val empty = isNullable(type)
        val value = argument(node.id, StoryPins.VALUE, node.value, empty)
        return if ((node.id to StoryPins.VALUE) in values && !empty) coerced(value, type) else value
    }

    /** The body of a loop is one lambda to the stages around it, so its steps stay in place. */
    private fun loop(node: StoryNode.Loop, depth: Int) {
        emit("for (${loopName(node.id)} in ${argument(node.id, StoryPins.ITEMS, node.items)}) {", node.id, depth)
        blocks++
        chain(flow[node.id to StoryPins.BODY], depth + 1)
        blocks--
        emit("}", node.id, depth)
    }

    /** Whether the Kotlin [code] is a variable that can be empty. */
    private fun readsEmpty(code: String) = isNullable(variables[code.trim()]?.type)

    private fun isNullable(type: String?) = type?.endsWith("?") == true

    /** [value] converted to [type], since a linked number or text is whatever the node before it gives. */
    private fun coerced(value: String, type: String?): String =
        when (type?.removeSuffix("?")?.substringAfterLast('.')) {
            "Int" -> "($value).toInt()"
            "Long" -> "($value).toLong()"
            "Short" -> "($value).toShort()"
            "Byte" -> "($value).toByte()"
            "Float" -> "($value).toFloat()"
            "Double" -> "($value).toDouble()"
            "String" -> "($value).toString()"
            else -> value
        }

    private fun call(node: StoryNode.Call, depth: Int) {
        val call = callOf(node)
        val assigned = if (index.isRead(node.id, StoryPins.VALUE)) {
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
        node.outputs.keys.filter { index.isRead(node.id, it) }
            .forEach { emit("${outputName(node.id, it)} = $it", node.id, depth) }
    }

    private fun sayOf(node: StoryNode.Say): String {
        val link = values[node.id to StoryPins.SPEAKER]
        val written = node.speaker.trim()
        val speaker = when {
            link != null -> value(link).present
            written.isEmpty() -> "null as String?"
            written.startsWith("{") && written.endsWith("}") -> Value(
                written.substring(1, written.length - 1),
                readsEmpty(written.trim('{', '}'))
            ).present

            else -> template(written)
        }
        val text =
            values[node.id to StoryPins.TEXT]?.let { coerced(value(it).present, "String") } ?: template(node.text)
        return "say($speaker, $text)"
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
        val parameters = (node.arguments.keys + values.keys.filter { it.first == node.id }
            .map { it.second }).filter { it != receiverPin }.distinct()
        val direct = parameters.map { "$it = ${parameter(node, it)}" }
        val groups = node.groups.filterValues { it.arguments.isNotEmpty() }.map { (parameter, group) ->
            val fields = group.arguments.entries.joinToString { (field, value) -> "$field = $value" }
            "$parameter = ${group.constructor}($fields)"
        }
        val arguments = (direct + groups).joinToString()
        val given = receiverPin in node.arguments || (node.id to receiverPin) in values
        val implicit = functions.signature(node)?.params?.firstOrNull { it.receiver }?.implicit
        val receiver = if (given) parameter(node, receiverPin) else implicit
        return if (receiver == null) "$name($arguments)" else "($receiver).$name($arguments)"
    }

    private fun parameter(node: StoryNode.Call, name: String): String {
        val type = functions.signature(node)?.params?.firstOrNull { it.name == name }?.type
        val empty = type?.isMarkedNullable != false
        val value = argument(node.id, name, node.arguments[name].orEmpty(), empty)
        if ((node.id to name) !in values) return value
        val kind = type?.classifier as? KClass<*>
        if (kind != null && kind != Entity::class && kind.isSubclassOf(Entity::class)) {
            return "($value as ${kind.qualifiedName}${if (empty) "?" else ""})"
        }
        return if (empty) value else coerced(value, kind?.simpleName)
    }

    /**
     * What [pin] of [node] gets: what is linked to it, or the Kotlin [written] into it.
     *
     * @param empty whether it takes null. When it does not, a value that can be null is asserted to be there.
     */
    private fun argument(node: String, pin: String, written: String, empty: Boolean = false): String {
        val link = values[node to pin]
        if (link != null) return value(link).let { if (empty) it.code else it.present }
        if (written.isBlank()) problem(node, "Pin '$pin' has no value")
        return if (!empty && readsEmpty(written)) Value(written, true).present else written
    }

    private fun value(link: StoryLink): Value {
        val id = link.from
        if (!evaluating.add(id)) {
            problem(id, "This node reads its own value")
            return Value("error(\"cycle\")")
        }
        try {
            return when (val node = nodes.getValue(id)) {
                is StoryNode.Expression -> Value("(${node.code})", readsEmpty(node.code))
                is StoryNode.Pure -> Value(pure(node, link.fromPin))
                is StoryNode.Loop -> Value(
                    if (detached) "(${
                        argument(
                            id,
                            StoryPins.ITEMS,
                            node.items
                        )
                    }).iterator().next()" else loopName(id)
                )

                is StoryNode.Assign -> {
                    val empty = isNullable(variables[node.variable]?.type)
                    Value(
                        if (index.isInFlow(id)) node.variable else "(${assigned(node)}).also { ${node.variable} = it }",
                        empty
                    )
                }

                is StoryNode.Call -> {
                    val empty = isNullable(node.returns)
                    if (index.isInFlow(id)) output(id, StoryPins.VALUE, node.returns) else Value(callOf(node), empty)
                }

                is StoryNode.Code -> when {
                    link.fromPin != StoryPins.VALUE -> {
                        if (link.fromPin !in node.outputs) problem(id, "There is no output '${link.fromPin}'")
                        output(id, link.fromPin, node.outputs[link.fromPin])
                    }

                    index.isInFlow(id) -> {
                        problem(id, "Code in the flow gives values through its outputs")
                        Value("null")
                    }

                    else -> Value(expressionOf(node))
                }

                else -> {
                    problem(id, "This node gives no value")
                    Value("error(\"no value\")")
                }
            }
        } finally {
            evaluating -= id
        }
    }

    /** The Kotlin of [node] as a value: its inputs are locals of a block of their own. */
    private fun expressionOf(node: StoryNode.Code): String {
        val inputs =
            node.inputs.map { (name, value) -> "val $name = ${argument(node.id, name, value)}\n" }.joinToString("")
        return "run {\n$inputs${node.code.trimIndent()}\n}"
    }

    /** What the flow left in the saved output [pin] of [node]: null there only means something if [type] can be null. */
    private fun output(node: String, pin: String, type: String?) = when {
        detached -> Value("(null as ${nullable(type ?: "Any")})", true)
        else -> Value(outputName(node, pin), true)
    }.let { if (isNullable(type)) it else Value(it.present) }

    /** The output [pin] of [node]: its template with what each input reads put in. */
    private fun pure(node: StoryNode.Pure, pin: String): String {
        val operator = StoryOperators.find(node.operator)
        val output = operator?.outputs?.firstOrNull { it.name == pin }
        if (output == null) {
            problem(node.id, "There is no value '$pin' of '${node.operator}'")
            return "error(\"no value\")"
        }
        val inputs = StoryOperators.inputsOf(node)
        val given =
            inputs.filter { it.default.isNotEmpty() || (node.id to it.name) in values || !node.arguments[it.name].isNullOrBlank() }
        if (given.isEmpty() && output.empty != null) return output.empty
        val template = output.template.replace("$*", given.joinToString { "$" + it.name })
        return OPERATOR_INPUT.replace(template) { match ->
            val name = match.groupValues[1]
            val input = inputs.first { it.name == name }
            "(${argument(node.id, name, node.arguments[name] ?: input.default, input.type == StoryPinType.ANY)})"
        }
    }

    /** [text] as a Kotlin string that puts in the variables it names; anything else in braces stays as it is. */
    private fun template(text: String): String {
        val parts = StringBuilder("\"")
        var from = 0
        NAME_IN_BRACES.findAll(text).forEach { match ->
            val value = when (val name = match.groupValues[1]) {
                in variables.keys -> name
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
        val initial = variable.initial.ifBlank { if (isNullable(variable.type)) "null" else "" }
        if (initial.isEmpty()) problem(null, "'${variable.name}' needs a value to start with")
        return "var ${variable.name} by saved$type($initial)"
    }

    /** Results of the flow live in saved variables, because any of them may be read from another stage. */
    private fun outputDeclarations(): List<String> = graph.nodes.sortedBy { it.id }.flatMap { node ->
        val outputs = when (node) {
            is StoryNode.Call -> if (index.isInFlow(node.id)) mapOf(StoryPins.VALUE to node.returns) else emptyMap()
            is StoryNode.Code -> node.outputs
            else -> emptyMap()
        }
        outputs.filter { index.isRead(node.id, it.key) }.map { (pin, type) ->
            "var ${outputName(node.id, pin)} by saved<${nullable(type ?: "Any")}>(null)"
        }
    }

    private fun nullable(type: String) = if (type.endsWith("?")) type else "$type?"

    private fun stageName(node: String) = "block_$node"

    private fun outputName(node: String, pin: String) = "out_${node}_$pin"

    private fun loopName(node: String) = "item_$node"

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
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.phys.Vec3",
            "ru.hollowhorizon.hollowengine.common.entities.NpcEntity",
        )
        private val IDENTIFIER_PART = Regex("[\\p{L}\\p{N}_]+")
        private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
        private val NAME_IN_BRACES = Regex("\\{([\\p{L}_][\\p{L}\\p{N}_]*)}")
        private val OPERATOR_INPUT = Regex("\\$(\\w+)")

        fun compile(graph: StoryGraph, functions: StoryFunctions = StoryFunctions.Builtin): GeneratedStory =
            StoryGraphCompiler(graph, functions).generate()

        /** What the outputs of [graph] worked out on the spot read, by node and pin, for telling their types. */
        fun pins(graph: StoryGraph, functions: StoryFunctions): Map<Pair<String, String>, String> =
            StoryGraphCompiler(graph, functions, detached = true).pinValues()

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
