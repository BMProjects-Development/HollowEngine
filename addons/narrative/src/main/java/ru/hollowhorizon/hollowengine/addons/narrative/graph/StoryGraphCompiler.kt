package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import net.minecraft.world.entity.Entity
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

/**
 * What is wrong with the node [node], or with the graph as a whole when it is null. A [warning] lets it run.
 *
 * @param key its name among the translations, which take [arguments].
 * @param message the same in English, for a server, which has no translations of an addon.
 */
data class StoryGraphProblem(
    val node: String?,
    val key: String,
    val message: String,
    val arguments: List<String> = emptyList(),
    val warning: Boolean = false,
)

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
    private val location: String? = null,
) {
    /** A value as generated Kotlin; [nullable] when it can be null by what it is rather than by a mistake. */
    private class Value(val code: String, val nullable: Boolean = false) {
        val present: String get() = if (nullable) "($code)!!" else code
    }

    private val graph = source.flattened().dissolved().let { plain ->
        plain.copy(nodes = plain.nodes.map { if (it is StoryNode.Call) functions.resolved(it) else it })
    }
    private val owners = source.owners()
    private val declared = graph.nodes.filterIsInstance<StoryNode.Function>()
    private val problems = ArrayList<StoryGraphProblem>()
    private val nodes = HashMap<String, StoryNode>()
    private val index =
        StoryLinkIndex(graph.links.filter { link -> graph.node(link.from) != null && graph.node(link.to) != null })
    private val flow = index.flow
    private val values = index.values
    private val variables = graph.variables.associateBy { it.name }

    /**
     * Writes of variables left out of the flow that take their value straight from a node in it, by that node: each
     * runs right after it, so a result goes into a variable without a write of its own in the flow.
     */
    private val writes: Map<String, List<StoryNode.Assign>> =
        graph.nodes.filterIsInstance<StoryNode.Assign>().filter { !index.isInFlow(it.id) }.mapNotNull { assign ->
                values[assign.id to StoryPins.VALUE]?.from?.takeIf(index::isInFlow)?.let { it to assign }
            }.groupBy({ it.first }, { it.second })
    private val attached = writes.values.flatten().mapTo(HashSet()) { it.id }

    private val lines = ArrayList<String>()
    private val origins = ArrayList<String?>()
    private val imports = (DEFAULT_IMPORTS + graph.imports).toSortedSet()
    private val stories = sortedSetOf<String>()
    private val pending = ArrayDeque<() -> Unit>()

    /** The stages of nodes several links lead to, and whether flow gets to each outside of a block. */
    private val queued = HashMap<String, Boolean>()
    private val evaluating = HashSet<String>()

    /** How many blocks the flow is inside of: a block is one lambda, which steps cannot be split out of. */
    private var blocks = 0

    /** How many blocks with an ordinary lambda for a body, such as `temporary { }`, the flow is inside of. */
    private var plain = 0

    /** How many flows passed to a function the flow is inside of: a stage of its own, which cannot return for the one around it. */
    private var lambdas = 0

    /** The function whose own flow the stage being emitted goes on with: only there a [StoryNode.Return] ends it. */
    private var continuing: StoryNode.Function? = null

    private fun generate(): GeneratedStory {
        index()
        val body = render()
        val classes = resultClasses()
        val header = stories.map { "@file:Import(${literal(it)})" } + listOfNotNull(location?.let {
            "package ${
                StoryPackages.of(it)
            }"
        }) + imports.map { "import $it" } + graph.variables.map(::declaration) + classes + outputDeclarations()
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
        is StoryNode.Code -> if (index.isInFlow(node.id)) node.outputs.keys.toList() else listOf(StoryPins.VALUE)
        is StoryNode.Call -> listOfNotNull(StoryPins.VALUE.takeIf { node.returns != null })
        is StoryNode.Invoke -> definition(node)?.results?.map { it.name }.orEmpty()
        is StoryNode.Parameters -> owners[node.id]?.params?.map { it.name }.orEmpty()
        else -> emptyList()
    }

    private fun index() {
        graph.nodes.forEach { node ->
            if (!IDENTIFIER_PART.matches(node.id)) problem(
                node.id,
                "bad_id",
                "A node id may only hold letters, digits and _"
            )
            if (nodes.put(node.id, node) != null) problem(node.id, "shared_id", "Two nodes share this id")
        }
        graph.links.filter { it.from !in nodes || it.to !in nodes }
            .forEach { problem(null, "missing_node", "A link joins a missing node: $it", "$it") }
        graph.links.filter { it.isFlow }.groupBy { it.from to it.fromPin }.filterValues { it.size > 1 }
            .forEach { (pin, _) ->
                problem(pin.first, "many_next", "Pin '${pin.second}' continues to several nodes", pin.second)
            }
        graph.links.filter { !it.isFlow }.groupBy { it.to to it.toPin }.filterValues { it.size > 1 }
            .forEach { (pin, _) ->
                problem(pin.first, "many_values", "Pin '${pin.second}' reads several values", pin.second)
            }
        graph.nodes.filterIsInstance<StoryNode.Call>().filter { it.function.isEmpty() }.forEach { call ->
            problem(call.id, "no_command", "There is no command '${call.action}'", call.action.orEmpty())
        }
        graph.nodes.filterIsInstance<StoryNode.Invoke>().filter { definition(it) == null }.forEach { invoke ->
            problem(invoke.id, "no_function", "There is no function '${invoke.function}'", invoke.function)
        }
        if (location != null) graph.nodes.filterIsInstance<StoryNode.Invoke>().forEach { invoke ->
            val file = invoke.file ?: return@forEach
            if (functions.reaches(file, location)) {
                problem(
                    invoke.id,
                    "import_cycle",
                    "Story '$file' already calls this one, so this one cannot call it",
                    file
                )
            }
        }
        declared.forEach(::checkFunction)
        problems += StoryValueFlow(graph, index) { invoke, pin ->
            definition(invoke)?.results?.firstOrNull { it.name == pin }?.type
        }.problems()
    }

    private fun checkFunction(function: StoryNode.Function) {
        val id = function.id
        if (!isName(function.name)) problem(
            id,
            "bad_function",
            "'${function.name}' is not a name of a function",
            function.name
        )
        if (function.name == MAIN || declared.count { it.name == function.name } > 1) {
            problem(id, "shared_function", "Another function is called '${function.name}'", function.name)
        } else if (function.results.size > 1 && declared.count {
                it.results.size > 1 && resultClass(it) == resultClass(
                    function
                )
            } > 1) {
            problem(id, "shared_function", "Another function is called '${function.name}'", function.name)
        }
        if (isGenerated(function.name)) problem(
            id,
            "reserved_name",
            "'${function.name}' is a name the story generates for itself",
            function.name
        )
        (function.params + function.results).forEach { value ->
            if (!isName(value.name)) problem(
                id,
                "bad_value_name",
                "'${value.name}' is not a name of a value",
                value.name
            )
            if (value.type.isBlank()) problem(id, "no_type", "'${value.name}' needs a type", value.name)
        }
        function.flows.forEach { flow ->
            if (!isName(flow)) problem(id, "bad_value_name", "'$flow' is not a name of a value", flow)
        }
        val reserved = listOf(StoryPins.IN, StoryPins.NEXT)
        val named = listOf(
            function.params.map { it.name } + function.flows,
            function.results.map { it.name } + function.flows + reserved,
        )
        named.flatMapTo(LinkedHashSet()) { names -> names.groupBy { it }.filterValues { it.size > 1 }.keys }.forEach {
            problem(id, "shared_value_name", "Two values are called '$it'", it)
        }
        if (function.body.nodes.count { it is StoryNode.Parameters } != 1) {
            problem(id, "function_entry", "A function needs one block of its parameters to start from")
        }
    }

    private fun render(): Pair<List<String>, List<String?>> {
        val starts = graph.nodes.filterIsInstance<StoryNode.Start>().sortedBy { it.id }
        starts.filter { it.id in owners }
            .forEach { problem(it.id, "start_in_function", "A function starts at its parameters") }
        val main = starts.filter { it.id !in owners }
        main.drop(1).forEach { problem(it.id, "many_starts", "A story has one start") }
        main.firstOrNull()?.let { start -> stage(MAIN, start.id, null) { next(start.id, StoryPins.NEXT, 1) } }
        declared.sortedBy { it.id }.forEach { function ->
            val entry = function.body.nodes.firstOrNull { it is StoryNode.Parameters }
            stage(function.name, function.id, function) { entry?.let { next(it.id, StoryPins.NEXT, 1) } }
        }
        while (pending.isNotEmpty()) pending.removeFirst()()
        return lines to origins
    }

    /**
     * Emits a stage of the flow of [node], which takes the parameters of the function [node] is in.
     *
     * @param continues the function whose own flow the stage goes on with, which it then returns from.
     */
    private fun stage(name: String, node: String, continues: StoryNode.Function?, body: () -> Unit) {
        val function = scopeOf(node)
        val parameters = (function?.params.orEmpty().map { "${it.name}: ${it.type}" } + function?.flows.orEmpty()
            .map { "$it: $FLOW_TYPE" }).joinToString()
        val result = continues?.let(::resultType)?.let { ": $it" }.orEmpty()
        emit("", null)
        emit("@State", node)
        emit("suspend fun $name($parameters)$result {", node)
        continuing = continues
        body()
        continuing = null
        emit("}", node)
    }

    /** The function [node] is in, or that it is. */
    private fun scopeOf(node: String): StoryNode.Function? = owners[node] ?: nodes[node] as? StoryNode.Function

    /** The parameters and flows of the function [node] is in, passed on to a stage of it. */
    private fun passed(node: String) =
        scopeOf(node)?.let { function -> function.params.map { it.name } + function.flows }.orEmpty().joinToString()

    /** Whether the flow is in the stage it began in, and not inside a block or a flow passed to a function. */
    private fun atTop() = blocks == 0 && lambdas == 0

    /** Whether the stage being emitted has to give a result back, and is not inside a block where it cannot. */
    private fun returnsHere() = atTop() && continuing?.results?.isNotEmpty() == true

    /** Goes on with the flow out of [pin] of [id]; a function that has to give a result back cannot just end. */
    private fun next(id: String, pin: String, depth: Int) {
        val target = flow[id to pin]
        if (target != null || !returnsHere()) return chain(target, depth)
        problem(id, "no_return", "The function ends here without giving back its result")
        emit("error(\"The function ends without a result\")", id, depth)
    }

    /** Emits the flow from [start]. A node reached by several links is entered through its stage. */
    private fun chain(start: String?, depth: Int) {
        val id = start ?: return
        val node = nodes[id] ?: return
        when {
            node is StoryNode.Start -> emit("$MAIN()", id, depth)
            index.isMerge(id) -> {
                if (inPlainBlock(id)) return
                val outside = atTop()
                if (queued.putIfAbsent(id, outside) == null) {
                    val continues = scopeOf(id)?.takeIf { outside }
                    pending += { stage(stageName(id), id, continues) { step(id, 1) } }
                }
                val call = "${stageName(id)}(${passed(id)})"
                emit(if (returnsHere() && queued[id] == true) "return $call" else call, id, depth)
            }

            else -> step(id, depth)
        }
    }

    private fun step(id: String, depth: Int) {
        when (val node = nodes.getValue(id)) {
            is StoryNode.Start, is StoryNode.Reroute, is StoryNode.Parameters, is StoryNode.Function -> Unit
            is StoryNode.Expression, is StoryNode.Pure -> problem(
                id,
                "value_in_flow",
                "A value has no place in the flow"
            )

            is StoryNode.Say -> {
                boundary(id, depth) { emit(sayOf(node), id, it) }
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Call -> {
                when {
                    isInstant(node) -> return instantRun(node, depth)
                    functions.kind(node) == StoryCallKind.ACTION || hasStageBody(node) -> call(node, depth)
                    else -> boundary(id, depth) { call(node, it) }
                }
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Invoke -> {
                invoke(node, depth)
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Return -> giveBack(node, depth)

            is StoryNode.Signal -> {
                if (node.flow !in owners[id]?.flows.orEmpty()) {
                    problem(id, "no_flow", "The function has no flow '${node.flow}'", node.flow)
                } else {
                    emit("${node.flow}()", id, depth)
                }
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Assign -> {
                if (isInstant(node)) return instantRun(node, depth)
                boundary(id, depth) { instant(node, it) }
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Loop -> {
                boundary(id, depth) { at -> loop(node, at) }
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Code -> {
                boundary(id, depth) { code(node, it) }
                next(id, StoryPins.NEXT, depth)
            }

            is StoryNode.Branch -> {
                emit("if (${argument(id, StoryPins.CONDITION, node.condition)}) {", id, depth)
                next(id, StoryPins.THEN, depth + 1)
                if (flow[id to StoryPins.ELSE] != null || returnsHere()) {
                    emit("} else {", id, depth)
                    next(id, StoryPins.ELSE, depth + 1)
                }
                emit("}", id, depth)
            }

            is StoryNode.Choose -> {
                emit("when (${chooseOf(node)}) {", id, depth)
                node.options.indices.forEach { index ->
                    emit("$index -> {", id, depth + 1)
                    next(id, StoryPins.option(index), depth + 2)
                    emit("}", id, depth + 1)
                }
                if (returnsHere()) emit("else -> error(\"No such option\")", id, depth + 1)
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
        emit("$name(${passed(node)})", node, depth)
        pending += { stage(name, node, null) { body(1) } }
    }

    /**
     * Whether [node] takes a body that is a stage of its own, such as `async { }`. It is called right in the stage
     * around it: what it starts belongs to the stage it is called in and would end with a step made just for it.
     */
    private fun hasStageBody(node: StoryNode.Call) = node.block && functions.signature(node)?.plainBlock != true

    private fun isInstant(node: StoryNode): Boolean = when (node) {
        is StoryNode.Assign -> !readsSuspending(node.id)
        is StoryNode.Call -> !node.block && functions.kind(node) == StoryCallKind.INSTANT && !readsSuspending(node.id)
        else -> false
    }

    /** Whether what [node] gives back is kept for later: it is read somewhere. */
    private fun keepsResult(node: StoryNode.Call): Boolean = index.isRead(node.id, StoryPins.VALUE)

    /** Whether working out what [id] reads calls something that can suspend, which a call with no flow does where it is read. */
    private fun readsSuspending(id: String): Boolean {
        val seen = hashSetOf(id)
        val queue = ArrayDeque(index.valuesInto(id))
        while (queue.isNotEmpty()) {
            val source = queue.removeFirst().from
            if (index.isInFlow(source) || !seen.add(source)) continue
            val node = nodes[source]
            if (node is StoryNode.Code || node is StoryNode.Invoke) return true
            if (node is StoryNode.Call && (node.block || functions.kind(node) != StoryCallKind.INSTANT)) return true
            queue += index.valuesInto(source)
        }
        return false
    }

    /** Puts [first] and the instant steps right after it in one stage: nothing can interrupt it halfway. */
    private fun instantRun(first: StoryNode, depth: Int) {
        val run = arrayListOf(first)
        while (true) {
            val node = flow[run.last().id to StoryPins.NEXT]?.let(nodes::get) ?: break
            if (!isInstant(node) || index.isMerge(node.id)) break
            run += node
        }
        boundary(first.id, depth) { at -> run.forEach { instant(it, at) } }
        next(run.last().id, StoryPins.NEXT, depth)
    }

    private fun instant(node: StoryNode, depth: Int) {
        if (node is StoryNode.Call) return call(node, depth)
        node as StoryNode.Assign
        emit("${node.variable} = ${assigned(node)}", node.id, depth)
    }

    /** What the variable of [node] is set to. */
    private fun assigned(node: StoryNode.Assign): String {
        if (node.variable !in variables) problem(
            node.id,
            "no_variable",
            "There is no variable '${node.variable}'",
            node.variable
        )
        val type = variables[node.variable]?.type
        val empty = isNullable(type)
        val value = argument(node.id, StoryPins.VALUE, node.value, empty, StoryLiterals.zeroNamed(type))
        return if ((node.id to StoryPins.VALUE) in values && !empty) coerced(value, type) else value
    }

    /** The body of a loop is one lambda to the stages around it, so its steps stay in place. */
    private fun loop(node: StoryNode.Loop, depth: Int) {
        emit("for (${loopName(node.id)} in ${argument(node.id, StoryPins.ITEMS, node.items)}) {", node.id, depth)
        write(node.id, depth + 1)
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
        val assigned = if (keepsResult(node)) {
            if (node.returns == null) problem(
                node.id,
                "needs_return",
                "'${node.function}' is read later, so it needs its return type",
                node.function
            )
            "${outputName(node.id, StoryPins.VALUE)} = $call"
        } else call
        if (!node.block) {
            emit(assigned, node.id, depth)
            return write(node.id, depth)
        }
        emit("$assigned {", node.id, depth)
        val stage = hasStageBody(node)
        val flat = !stage && functions.signature(node)?.plainBlock == true
        if (stage) lambdas++ else blocks++
        if (flat) plain++
        chain(flow[node.id to StoryPins.BODY], depth + 1)
        if (flat) plain--
        if (stage) lambdas-- else blocks--
        // A body of the graph gives nothing back; without this its last call would be the result `async` saves.
        emit("Unit", node.id, depth + 1)
        emit("}", node.id, depth)
        write(node.id, depth)
    }

    /** The writes of variables that take their value from what [node] just gave. */
    private fun write(node: String, depth: Int) {
        writes[node].orEmpty().forEach { emit("${it.variable} = ${assigned(it)}", it.id, depth) }
    }

    /** Whether the flow is in a block such as `temporary { }`, whose body is an ordinary lambda no stage can be called in. */
    private fun inPlainBlock(node: String): Boolean {
        if (plain == 0) return false
        if (problems.none { it.node == node && it.key == "stage_in_block" }) problem(
            node,
            "stage_in_block",
            "This node runs apart from the steps around it, which a block such as 'temporary' cannot hold"
        )
        return true
    }

    /** A call of a function: one result goes where it is read, several are taken out of the class that holds them. */
    private fun invoke(node: StoryNode.Invoke, depth: Int) {
        if (inPlainBlock(node.id)) return
        val function = definition(node) ?: return
        val read = function.results.filter { index.isRead(node.id, it.name) }
        when {
            read.isEmpty() -> invocation(node, function, "", depth)
            function.results.size == 1 -> invocation(
                node,
                function,
                "${outputName(node.id, read.single().name)} = ",
                depth
            )

            else -> {
                val result = "result_${node.id}"
                invocation(node, function, "val $result = ", depth)
                read.forEach { emit("${outputName(node.id, it.name)} = $result.${it.name}", node.id, depth) }
            }
        }
        write(node.id, depth)
    }

    /**
     * Emits the call of [function] after [prefix]. Each of its flows is a lambda passed to it, with the flow linked on
     * from the pin of that name as its body.
     */
    private fun invocation(node: StoryNode.Invoke, function: StoryNode.Function, prefix: String, depth: Int) {
        if (function.flows.isEmpty()) return emit("$prefix${invocationOf(node, function)}", node.id, depth)
        val arguments = argumentsOf(node, function)
        var opening = "$prefix${
            calleeOf(
                node,
                function
            )
        }(${arguments.joinToString(postfix = if (arguments.isEmpty()) "" else ", ")}"
        function.flows.forEach { name ->
            emit("$opening$name = {", node.id, depth)
            lambdas++
            chain(flow[node.id to name], depth + 1)
            lambdas--
            opening = "}, "
        }
        emit("})", node.id, depth)
    }

    /** What [node] calls: a function of this story, or of the story it names, which is then imported. */
    private fun definition(node: StoryNode.Invoke): StoryNode.Function? = functions.definition(graph, node)

    /** A call of [function] in one expression, which passes flows that do nothing: as a value, a call has no flow. */
    private fun invocationOf(node: StoryNode.Invoke, function: StoryNode.Function): String =
        "${calleeOf(node, function)}(${
            (argumentsOf(
                node,
                function
            ) + function.flows.map { "$it = {}" }).joinToString()
        })"

    private fun argumentsOf(node: StoryNode.Invoke, function: StoryNode.Function): List<String> =
        function.params.map { "${it.name} = ${typed(node.id, it, node.arguments[it.name])}" }

    /** The name [function] is called by: as it is in this story, or qualified by the story it is imported from. */
    private fun calleeOf(node: StoryNode.Invoke, function: StoryNode.Function): String {
        val file = node.file ?: return function.name
        stories += file
        return "${StoryPackages.of(file)}.${function.name}"
    }

    /** What the input [value] of [node] gets as the type it is declared of: what is linked to it, or [written]. */
    private fun typed(node: String, value: StoryTypedName, written: String?): String {
        val empty = isNullable(value.type)
        val given = argument(node, value.name, written.orEmpty(), empty, StoryLiterals.zeroNamed(value.type))
        return if ((node to value.name) in values && !empty) coerced(given, value.type) else given
    }

    /** Ends the function the flow is in with what [node] gives back. */
    private fun giveBack(node: StoryNode.Return, depth: Int) {
        val function = continuing
        if (function == null || !atTop() || owners[node.id]?.id != function.id) {
            return problem(
                node.id,
                "return_place",
                "A function returns only from its own flow, outside of loops and blocks"
            )
        }
        val given = function.results.map { it.name to typed(node.id, it, node.values[it.name]) }
        val result = when (given.size) {
            0 -> ""
            1 -> " ${given.single().second}"
            else -> " ${resultClass(function)}(${given.joinToString { (name, value) -> "$name = $value" }})"
        }
        emit("return$result", node.id, depth)
    }

    /** The type [function] gives back: its one result, or the class that holds several. */
    private fun resultType(function: StoryNode.Function): String? = when (function.results.size) {
        0 -> null
        1 -> function.results.single().type
        else -> resultClass(function)
    }

    private fun resultClass(function: StoryNode.Function) =
        function.name.replaceFirstChar(Char::uppercaseChar) + "Result"

    /** A class per function of this story that gives several results; stories save it through [ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValues]. */
    private fun resultClasses(): List<String> =
        declared.filter { it.results.size > 1 }.sortedBy { it.id }.map { function ->
            val fields = function.results.joinToString { "val ${it.name}: ${it.type}" }
            "data class ${resultClass(function)}($fields) : StoryRecord"
        }

    /**
     * Emits the code of [node] with its inputs and outputs as locals. Inside a block, steps share one body, so the code
     * gets a scope of its own there: `if (true)`, as a lambda would keep it from calling stages.
     */
    private fun code(node: StoryNode.Code, depth: Int) {
        val scoped = blocks > 0
        if (scoped) emit("if (true) {", node.id, depth)
        val at = if (scoped) depth + 1 else depth
        node.inputs.forEach { (name, value) -> emit("val $name = ${argument(node.id, name, value)}", node.id, at) }
        node.outputs.forEach { (name, type) -> emit("var $name: ${nullable(type)} = null", node.id, at) }
        node.code.trimIndent().lines().forEach { emit(it, node.id, at) }
        node.outputs.keys.filter { index.isRead(node.id, it) }
            .forEach { emit("${outputName(node.id, it)} = $it", node.id, at) }
        write(node.id, at)
        if (scoped) emit("}", node.id, depth)
    }

    private fun sayOf(node: StoryNode.Say): String {
        val link = values[node.id to StoryPins.SPEAKER]
        val written = node.speaker.trim()
        val speaker = when {
            link != null -> value(link).present
            written.isEmpty() -> "null as String?"
            written.startsWith("{") && written.endsWith("}") -> Value(
                written.substring(1, written.length - 1), readsEmpty(written.trim('{', '}'))
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
        val signature = functions.signature(node)
        val keyed = signature?.params.orEmpty().filter { it.group?.keyed == true }
        val parameters = (node.arguments.keys + values.keys.filter { it.first == node.id }
            .map { it.second }).filter { it != receiverPin && keyed.none { param -> param.name == it } }.distinct()
        val bound = signature?.bound.orEmpty().map { (parameter, value) -> "$parameter = $value" }
        val direct = parameters.map { "$it = ${parameter(node, it)}" }
        val groups = node.groups.filterValues { it.arguments.isNotEmpty() }.filterKeys { parameter ->
            keyed.none { it.group?.parameter == parameter }
        }.map { (parameter, group) ->
            val fields = group.arguments.entries.joinToString { (field, value) -> "$field = $value" }
            "$parameter = ${group.constructor}($fields)"
        }
        val keyedGroups =
            keyed.groupBy { it.group }.mapNotNull { (group, params) -> group?.let { keyedGroupOf(node, it, params) } }
        val arguments = (bound + direct + groups + keyedGroups).joinToString()
        val given = receiverPin in node.arguments || (node.id to receiverPin) in values
        val implicit = signature?.params?.firstOrNull { it.receiver }?.implicit
        val receiver = if (given) parameter(node, receiverPin) else implicit
        return if (receiver == null) "$name($arguments)" else "($receiver).$name($arguments)"
    }

    /** The argument of a [StoryParamGroup.keyed] group: each field written or linked, as `"name" to value`. */
    private fun keyedGroupOf(node: StoryNode.Call, group: StoryParamGroup, params: List<StoryParam>): String {
        val written = node.groups[group.parameter]?.arguments.orEmpty()
        val fields = params.filter { it.name in written || (node.id to it.name) in values }.joinToString { param ->
            "${literal(param.name)} to ${argument(node.id, param.name, written[param.name].orEmpty(), empty = true)}"
        }
        return "${group.parameter} = ${group.constructor}($fields)"
    }

    private fun parameter(node: StoryNode.Call, name: String): String = typed(
        node.id,
        name,
        node.arguments[name].orEmpty(),
        functions.signature(node)?.params?.firstOrNull { it.name == name }?.type
    )

    /** What [pin] of [node] gets as a value of [type]: a linked entity cast to its class, a linked number converted. */
    private fun typed(node: String, pin: String, written: String, type: KType?): String {
        val empty = type?.isMarkedNullable != false
        val value = argument(node, pin, written, empty, StoryLiterals.zero(type))
        if ((node to pin) !in values) return value
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
     * @param fallback what it reads with nothing written, when its type has such a value; without one that is a problem.
     */
    private fun argument(node: String, pin: String, written: String, empty: Boolean = false, fallback: String? = null): String {
        val link = values[node to pin]
        if (link != null) return value(link).let { if (empty) it.code else it.present }
        if (written.isBlank()) {
            fallback?.let { return it }
            problem(node, "no_value", "Pin '$pin' has no value", pin)
        }
        return if (!empty && readsEmpty(written)) Value(written, true).present else written
    }

    private fun value(link: StoryLink): Value {
        val id = link.from
        if (!evaluating.add(id)) {
            problem(id, "own_value", "This node reads its own value")
            return Value("error(\"cycle\")")
        }
        try {
            return when (val node = nodes.getValue(id)) {
                is StoryNode.Expression -> Value("(${node.code})", readsEmpty(node.code))
                is StoryNode.Pure -> Value(pure(node, link.fromPin), givesEmpty(node, link.fromPin))
                is StoryNode.Loop -> Value(
                    if (detached) "(${
                        argument(
                            id, StoryPins.ITEMS, node.items
                        )
                    }).iterator().next()" else loopName(id)
                )

                is StoryNode.Assign -> {
                    val empty = isNullable(variables[node.variable]?.type)
                    Value(
                        if (index.isInFlow(id) || id in attached) node.variable else "(${assigned(node)}).also { ${node.variable} = it }",
                        empty
                    )
                }

                is StoryNode.Call -> {
                    val empty = isNullable(node.returns)
                    if (index.isInFlow(id)) output(id, StoryPins.VALUE, node.returns) else Value(callOf(node), empty)
                }

                is StoryNode.Parameters -> {
                    val param = owners[id]?.params?.firstOrNull { it.name == link.fromPin }
                    if (param == null) problem(id, "no_output", "There is no output '${link.fromPin}'", link.fromPin)
                    Value(link.fromPin, isNullable(param?.type))
                }

                is StoryNode.Invoke -> {
                    if (!index.isInFlow(id) && inPlainBlock(id)) return Value("error(\"no value\")")
                    val function = definition(node)
                    val result = function?.results?.firstOrNull { it.name == link.fromPin }
                    when {
                        function == null -> Value("error(\"no function\")")
                        result == null -> {
                            problem(id, "no_output", "There is no output '${link.fromPin}'", link.fromPin)
                            Value("error(\"no value\")")
                        }

                        index.isInFlow(id) -> output(id, result.name, result.type)
                        function.results.size == 1 -> Value(invocationOf(node, function), isNullable(result.type))
                        else -> Value("${invocationOf(node, function)}.${result.name}", isNullable(result.type))
                    }
                }

                is StoryNode.Code -> when {
                    link.fromPin != StoryPins.VALUE -> {
                        if (link.fromPin !in node.outputs) problem(
                            id,
                            "no_output",
                            "There is no output '${link.fromPin}'",
                            link.fromPin
                        )
                        output(id, link.fromPin, node.outputs[link.fromPin])
                    }

                    index.isInFlow(id) -> {
                        problem(id, "code_outputs", "Code in the flow gives values through its outputs")
                        Value("null")
                    }

                    else -> Value(expressionOf(node))
                }

                else -> {
                    problem(id, "gives_nothing", "This node gives no value")
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
            problem(node.id, "no_operator_value", "There is no value '$pin' of '${node.operator}'", pin, node.operator)
            return "error(\"no value\")"
        }
        if (TypeOperators.isGeneric(node.operator)) {
            problem(node.id, "untyped_block", "Link the block to a value to tell its type")
            return "error(\"no type\")"
        }
        operator.function?.let { return valueCall(node, operator, it) }
        val inputs = StoryOperators.inputsOf(node)
        val given =
            inputs.filter { it.default.isNotEmpty() || (node.id to it.name) in values || !node.arguments[it.name].isNullOrBlank() }
        if (given.isEmpty() && output.empty != null) return output.empty
        val template = output.template.replace("$*", given.joinToString { "$" + it.name })
        return OPERATOR_INPUT.replace(template) { match ->
            val name = match.groupValues[1]
            val input = inputs.first { it.name == name }
            if (input.typeName) return@replace typeName(node, name)
            val written = node.arguments[name]?.takeIf { it.isNotBlank() } ?: input.default
            val zero = StoryLiterals.zero(input.param?.type ?: input.type.type)
            "(${argument(node.id, name, written, input.type == StoryPinType.ANY, zero)})"
        }
    }

    /** The Kotlin type the field [name] of [node] names, as it is written. */
    private fun typeName(node: StoryNode.Pure, name: String): String {
        val written = node.arguments[name].orEmpty().trim()
        if (TYPE_NAME.matches(written)) return written
        problem(node.id, "no_type_name", "Write the Kotlin type to work with", name)
        return "Nothing"
    }

    private fun givesEmpty(node: StoryNode.Pure, pin: String): Boolean =
        isNullable(StoryOperators.find(node.operator)?.outputs?.firstOrNull { it.name == pin }?.kotlin)

    /** The call of the [function] of [operator]: inputs by name, and those with nothing written or linked left out. */
    private fun valueCall(node: StoryNode.Pure, operator: StoryOperator, function: String): String {
        imports += function
        var receiver: String? = null
        val arguments = arguments(node, operator.inputs, "") { receiver = it }
        val call = "${function.substringAfterLast('.')}(${arguments.joinToString()})"
        return receiver?.let { "($it).$call" } ?: call
    }

    private fun arguments(
        node: StoryNode.Pure,
        inputs: List<StoryOperatorInput>,
        path: String,
        receive: (String) -> Unit,
    ): List<String> {
        val arguments = ArrayList<String>()
        inputs.filter { it.path.startsWith(path) && '.' !in it.path.removePrefix(path) }.forEach { input ->
            val name = input.path.removePrefix(path)
            val written = node.arguments[input.name]?.takeIf { it.isNotBlank() } ?: input.default
            val given = written.isNotBlank() || (node.id to input.name) in values
            val parts = inputs.filter { it.path.startsWith(input.path + ".") }
            val kind = (input.param?.type?.classifier as? KClass<*>)?.qualifiedName
            if (!given && parts.isNotEmpty() && kind != null) {
                val made = arguments(node, parts, input.path + ".", receive)
                if (made.isEmpty() && input.param?.optional == true) return@forEach
                imports += kind
                arguments += "$name = ${kind.substringAfterLast('.')}(${made.joinToString()})"
                return@forEach
            }
            if (!given && input.param?.optional == true) return@forEach
            val code = typed(node.id, input.name, written, input.param?.type)
            if (input.param?.receiver == true) receive(code) else arguments += "$name = $code"
        }
        return arguments
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
        if (!isName(variable.name)) problem(
            null,
            "bad_variable",
            "'${variable.name}' is not a name of a variable",
            variable.name
        )
        if (isGenerated(variable.name)) problem(
            null,
            "reserved_name",
            "'${variable.name}' is a name the story generates for itself",
            variable.name
        )
        val type = variable.type?.let { "<$it>" }.orEmpty()
        val initial = variable.initial.ifBlank { if (isNullable(variable.type)) "null" else "" }
        if (initial.isEmpty()) problem(
            null,
            "needs_start",
            "'${variable.name}' needs a value to start with",
            variable.name
        )
        return "var ${variable.name} by saved$type($initial)"
    }

    /** Results of the flow live in saved variables, because any of them may be read from another stage. */
    private fun outputDeclarations(): List<String> = graph.nodes.sortedBy { it.id }.flatMap { node ->
        val outputs = when (node) {
            is StoryNode.Call -> if (index.isInFlow(node.id)) mapOf(StoryPins.VALUE to node.returns) else emptyMap()
            is StoryNode.Code -> node.outputs
            is StoryNode.Invoke -> if (index.isInFlow(node.id)) {
                definition(node)?.results.orEmpty().associate { it.name to it.type }
            } else emptyMap()

            else -> emptyMap()
        }
        val kept = outputs.filter { (pin, _) ->
            when (node) {
                is StoryNode.Call -> keepsResult(node)
                else -> index.isRead(node.id, pin)
            }
        }
        kept.map { (pin, type) ->
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

    private fun problem(node: String?, key: String, message: String, vararg arguments: String) {
        problems += StoryGraphProblem(node, key, message, arguments.toList())
    }

    companion object {
        private const val MAIN = "main"
        private const val FLOW_TYPE = "@State suspend () -> Unit"
        private const val PLAYER = "player"
        private const val NARRATIVE = "ru.hollowhorizon.hollowengine.addons.narrative"
        private val DEFAULT_IMPORTS = listOf(
            "$NARRATIVE.story.*",
            "$NARRATIVE.npcs.*",
            "$NARRATIVE.speech.*",
            "$NARRATIVE.poll.*",
            "$NARRATIVE.scene.commands.*",
            "$NARRATIVE.items.*",
            "$NARRATIVE.entities.*",
            "$NARRATIVE.waits.*",
            "$NARRATIVE.world.*",
            "$NARRATIVE.nbt.*",
            "net.minecraft.nbt.Tag",
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.world.level.block.state.BlockState",
            "net.minecraft.world.phys.Vec3",
            "ru.hollowhorizon.hollowengine.common.entities.NpcEntity",
            "ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.pos",
        )
        private val IDENTIFIER_PART = Regex("[\\p{L}\\p{N}_]+")
        private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

        /** Words of Kotlin that cannot name anything. */
        private val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
            "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
            "val", "var", "when", "while",
        )

        /** What names of stages, outputs and loop items the generated script starts with. */
        private val GENERATED = listOf("step_", "block_", "out_", "item_", "result_")

        /** Whether [name] can name a function, a value or a variable in the generated script. */
        private fun isName(name: String) = IDENTIFIER.matches(name) && name !in KEYWORDS

        private fun isGenerated(name: String) = GENERATED.any(name::startsWith)
        private val NAME_IN_BRACES = Regex("\\{([\\p{L}_][\\p{L}\\p{N}_]*)}")
        private val OPERATOR_INPUT = Regex("\\$(\\w+)")

        /**
         * The script of [graph].
         *
         * @param location the qualified script id of the story, which gives it the package others import it by.
         */
        fun compile(
            graph: StoryGraph,
            functions: StoryFunctions = StoryFunctions.Builtin,
            location: String? = null,
        ): GeneratedStory = StoryGraphCompiler(graph, functions, location = location).generate()

        /** What the outputs of [graph] worked out on the spot read, by node and pin, for telling their types. */
        fun pins(graph: StoryGraph, functions: StoryFunctions): Map<Pair<String, String>, String> =
            StoryGraphCompiler(graph, functions, detached = true).pinValues()

        /** [text] as a Kotlin string literal. */
        fun literal(text: String): String = "\"${escape(text)}\""

        /** A name of a type: dotted, with type arguments and a question mark, as Kotlin writes it in `as`. */
        private val TYPE_NAME = Regex("""[A-Za-z_][\w.]*(<[\w.<>, ?*]*>)?\??""")

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
