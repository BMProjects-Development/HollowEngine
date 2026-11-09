package ru.hollowhorizon.hollowengine.addons.narrative.graph

internal class StoryGraphChecks(
    private val graph: StoryGraph,
    private val index: StoryLinkIndex,
    private val functions: StoryFunctions,
    private val location: String?,
) {
    private val problems = ArrayList<StoryGraphProblem>()
    private val declared = graph.nodes.filterIsInstance<StoryNode.Function>()

    fun problems(): List<StoryGraphProblem> {
        nodes()
        links()
        calls()
        declared.forEach(::function)
        graph.variables.forEach(::variable)
        problems += StoryValueFlow(graph, index) { invoke, pin ->
            functions.definition(graph, invoke)?.results?.firstOrNull { it.name == pin }?.type
        }.problems()
        return problems
    }

    private fun nodes() {
        val ids = HashSet<String>()
        graph.nodes.forEach { node ->
            if (!IDENTIFIER_PART.matches(node.id)) problem(node.id, "bad_id", "A node id may only hold letters, digits and _")
            if (!ids.add(node.id)) problem(node.id, "shared_id", "Two nodes share this id")
        }
    }

    private fun links() {
        val ids = graph.nodes.mapTo(HashSet()) { it.id }
        graph.links.filter { it.from !in ids || it.to !in ids }
            .forEach { problem(null, "missing_node", "A link joins a missing node: $it", "$it") }
        graph.links.filter { it.isFlow }.groupBy { it.from to it.fromPin }.filterValues { it.size > 1 }.keys.forEach { (node, pin) ->
            problem(node, "many_next", "Pin '$pin' continues to several nodes", pin)
        }
        graph.links.filter { !it.isFlow }.groupBy { it.to to it.toPin }.filterValues { it.size > 1 }.keys.forEach { (node, pin) ->
            problem(node, "many_values", "Pin '$pin' reads several values", pin)
        }
    }

    private fun calls() {
        graph.nodes.filterIsInstance<StoryNode.Call>().filter { it.function.isEmpty() }.forEach { call ->
            problem(call.id, "no_command", "There is no command '${call.action}'", call.action.orEmpty())
        }
        graph.nodes.filterIsInstance<StoryNode.Invoke>().forEach { invoke ->
            if (functions.definition(graph, invoke) == null) {
                problem(invoke.id, "no_function", "There is no function '${invoke.function}'", invoke.function)
            }
            val file = invoke.file ?: return@forEach
            if (location != null && functions.reaches(file, location)) {
                problem(invoke.id, "import_cycle", "Story '$file' already calls this one, so this one cannot call it", file)
            }
        }
    }

    private fun function(function: StoryNode.Function) {
        val id = function.id
        val name = function.name
        if (!StoryScriptNames.isName(name)) problem(id, "bad_function", "'$name' is not a name of a function", name)
        val sameClass = function.results.size > 1 && declared.count {
            it.results.size > 1 && StoryScriptNames.resultClass(it) == StoryScriptNames.resultClass(function)
        } > 1
        if (name == StoryScriptNames.MAIN || declared.count { it.name == name } > 1 || sameClass) {
            problem(id, "shared_function", "Another function is called '$name'", name)
        }
        if (StoryScriptNames.isGenerated(name)) {
            problem(id, "reserved_name", "'$name' is a name the story generates for itself", name)
        }
        (function.params + function.results).forEach { value ->
            if (!StoryScriptNames.isName(value.name)) {
                problem(id, "bad_value_name", "'${value.name}' is not a name of a value", value.name)
            }
            if (value.type.isBlank()) problem(id, "no_type", "'${value.name}' needs a type", value.name)
        }
        function.flows.filterNot(StoryScriptNames::isName).forEach { flow ->
            problem(id, "bad_value_name", "'$flow' is not a name of a value", flow)
        }
        val named = listOf(
            function.params.map { it.name } + function.flows,
            function.results.map { it.name } + function.flows + listOf(StoryPins.IN, StoryPins.NEXT),
        )
        named.flatMapTo(LinkedHashSet()) { names -> names.groupBy { it }.filterValues { it.size > 1 }.keys }.forEach {
            problem(id, "shared_value_name", "Two values are called '$it'", it)
        }
        if (function.body.nodes.count { it is StoryNode.Parameters } != 1) {
            problem(id, "function_entry", "A function needs one block of its parameters to start from")
        }
    }

    private fun variable(variable: StoryVariable) {
        val name = variable.name
        if (!StoryScriptNames.isName(name)) problem(null, "bad_variable", "'$name' is not a name of a variable", name)
        if (StoryScriptNames.isGenerated(name)) {
            problem(null, "reserved_name", "'$name' is a name the story generates for itself", name)
        }
        if (variable.initial.isBlank() && variable.type?.endsWith("?") != true) {
            problem(null, "needs_start", "'$name' needs a value to start with", name)
        }
    }

    private fun problem(node: String?, key: String, message: String, vararg arguments: String) {
        problems += StoryGraphProblem(node, key, message, arguments.toList())
    }

    private companion object {
        val IDENTIFIER_PART = Regex("[\\p{L}\\p{N}_]+")
    }
}

internal object StoryScriptNames {
    const val MAIN = "main"

    private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
        "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
        "val", "var", "when", "while",
    )

    private val GENERATED = listOf("step_", "block_", "out_", "item_", "result_")

    fun isName(name: String) = IDENTIFIER.matches(name) && name !in KEYWORDS

    fun isGenerated(name: String) = GENERATED.any(name::startsWith)

    fun resultClass(function: StoryNode.Function) = function.name.replaceFirstChar(Char::uppercaseChar) + "Result"
}
