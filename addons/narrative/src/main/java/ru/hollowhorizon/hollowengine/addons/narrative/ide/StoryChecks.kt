package ru.hollowhorizon.hollowengine.addons.narrative.ide

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryChecks.param
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryChecks.variable
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.time.Duration

/** Something wrong with the node [node], or with the story as a whole when it is null. [warning] lets the story run all the same. */
internal class StoryCheck(val node: String?, val message: String, val warning: Boolean = false)

/** What the editors can tell is wrong with a story before it is compiled. Every answer is a line for the author. */
internal object StoryChecks {
    private val LOCATION = Regex("""([a-z0-9_.-]+:)?[a-z0-9_./-]+""")
    private val NAME = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

    private fun problem(name: String) = storyLang("problem.$name")

    /** What is wrong with the Kotlin [expression] given to [param], which nothing is linked to. Analysis sees the rest. */
    fun param(param: StoryParam, expression: String): String? {
        if (expression.isBlank()) return if (param.optional) null else problem("required")
        return format(StoryLiterals.text(expression, param.type), param.type) ?: code(expression)
    }

    /** What is wrong with the text [shown] by a field of [type], as Kotlin takes any string where a format is wanted. */
    private fun format(shown: String, type: KType?): String? {
        val kind = type?.classifier as? KClass<*> ?: return null
        if (shown.startsWith("{")) return null
        return when {
            kind == Duration::class -> if (isDuration(shown)) null else problem("duration")
            kind == ResourceLocation::class || kind == ServerLevel::class -> if (LOCATION.matches(shown)) null else problem(
                "location"
            )

            else -> null
        }
    }

    private fun isNumber(text: String) = text.toDoubleOrNull() != null && !text.endsWith('.')

    private fun isDuration(text: String) = isNumber(text) || runCatching { Duration.parse(text) }.isSuccess

    /** What the parser finds wrong with the Kotlin [text]. */
    fun code(text: String): String? = if (StoryKotlin.syntax(text)?.valid == false) problem("code") else null

    /** What keeps [name] from being the name of a variable among those [taken]. */
    fun variableName(name: String, taken: Collection<String>): String? = when {
        !NAME.matches(name) || StoryKotlin.syntax(name)?.tokens?.isNotEmpty() == true -> problem("name")
        name in taken -> problem("name_taken")
        else -> null
    }

    /** What is wrong with what [variable] starts as; a variable that can be empty need not start with anything. */
    fun variable(variable: StoryVariable): String? = when {
        variable.initial.isNotBlank() -> code(variable.initial)
        variable.type?.endsWith("?") == true -> null
        else -> problem("required")
    }

    /** [problem] in the language of the author. */
    fun text(problem: StoryGraphProblem): String =
        storyLang("problem.graph.${problem.key}", *problem.arguments.toTypedArray())

    /** Everything wrong with [graph]: what the compiler says, and what the editors can see. */
    fun all(graph: StoryGraph): List<StoryCheck> {
        val compiled = StoryGraphCompiler.compile(graph, StoryActionFunctions).problems.map {
            StoryCheck(it.node, text(it), it.warning)
        }
        val variables = graph.variables.mapNotNull { variable ->
            variable(variable)?.let {
                StoryCheck(
                    null,
                    "${variable.name}: $it"
                )
            }
        }
        return compiled + variables + nodes(graph)
    }

    private fun warning(node: String, name: String) = StoryCheck(node, problem(name), warning = true)

    /** What is wrong with the nodes of [graph]. */
    private fun nodes(graph: StoryGraph): List<StoryCheck> {
        val plain = graph.flattened().dissolved()
        val reached = reached(plain)
        val fed = plain.links.filter { !it.isFlow }.mapTo(HashSet()) { it.to to it.toPin }
        val read = plain.links.filter { !it.isFlow }.mapTo(HashSet()) { it.from }
        return plain.nodes.flatMap { node ->
            fun free(pin: String) = (node.id to pin) !in fed
            val own = when (node) {
                is StoryNode.Call -> call(node, ::free)
                is StoryNode.Pure -> pure(node, ::free)
                is StoryNode.Say -> emptyList()
                is StoryNode.Branch -> filled(node.condition, free(StoryPins.CONDITION))
                is StoryNode.Loop -> filled(node.items, free(StoryPins.ITEMS))
                is StoryNode.Assign -> filled(
                    node.value,
                    free(StoryPins.VALUE)
                ) + listOfNotNull(problem("no_variable").takeIf { graph.variables.none { it.name == node.variable } })

                is StoryNode.Expression -> filled(node.code, true)
                is StoryNode.Code -> listOfNotNull(code(node.code))
                else -> emptyList()
            }
            val empty = node is StoryNode.Say && node.text.isBlank() && free(StoryPins.TEXT)
            val value =
                node.id in read && !plain.index.isInFlow(node.id) &&
                    (node is StoryNode.Call || node is StoryNode.Assign || node is StoryNode.Code || node is StoryNode.Invoke)
            val lost = node.id !in reached && needsFlow(node) && !value
            own.map { StoryCheck(node.id, it) } + listOfNotNull(
                warning(node.id, "empty_text").takeIf { empty },
                warning(node.id, "unreachable").takeIf { lost },
            )
        }
    }

    private fun filled(code: String, free: Boolean): List<String> = when {
        !free -> emptyList()
        code.isBlank() -> listOf(problem("required"))
        else -> listOfNotNull(code(code))
    }

    private fun call(node: StoryNode.Call, free: (String) -> Boolean): List<String> {
        val signature = StoryCatalog.signatureOf(node) ?: return emptyList()
        return signature.params.filter { it.group != null || free(it.name) }.mapNotNull { param ->
            param(param, node.argument(param).orEmpty())?.let { "${StoryCatalog.paramTitle(signature, param)}: $it" }
        }
    }

    private fun pure(node: StoryNode.Pure, free: (String) -> Boolean): List<String> =
        StoryOperators.inputsOf(node).filter { free(it.name) }.mapNotNull { input ->
            val written = node.arguments[input.name].orEmpty()
            val wrong = if (written.isBlank() && input.default.isBlank()) problem("required") else code(written)
            wrong?.let { "${StoryCatalog.operatorPinTitle(input.name)}: $it" }
        }

    private fun needsFlow(node: StoryNode) =
        node !is StoryNode.Start && node !is StoryNode.Parameters && node !is StoryNode.Function &&
            node !is StoryNode.Pure && node !is StoryNode.Expression

    /** The nodes flow gets to from the start of [graph] and those of its functions. */
    private fun reached(graph: StoryGraph): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque(graph.nodes.filter { it is StoryNode.Start || it is StoryNode.Parameters }.map { it.id })
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (seen.add(at)) queue += graph.index.flowOut(at)
        }
        return seen
    }
}
