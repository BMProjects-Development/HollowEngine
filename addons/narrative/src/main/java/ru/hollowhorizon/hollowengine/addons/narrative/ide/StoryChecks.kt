package ru.hollowhorizon.hollowengine.addons.narrative.ide

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
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
    private val WHOLE = setOf(Int::class, Long::class, Short::class, Byte::class)
    private val FRACTION = setOf(Float::class, Double::class)
    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
        "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "val", "var",
        "when", "while",
    )

    private fun problem(name: String) = storyLang("problem.$name")

    /** What is wrong with the Kotlin [expression] given to [param], which nothing is linked to. */
    fun param(param: StoryParam, expression: String): String? {
        if (expression.isBlank()) return if (param.optional) null else problem("required")
        val helper = StoryLiterals.vectorHelper(param.type)
        if (helper != null) return vector(expression, helper)
        val shown = StoryLiterals.text(expression, param.type)
        val kind = param.type?.classifier as? KClass<*>
        if (kind != null && kind.java.isEnum && shown.lowercase() !in param.suggestions) return problem("choice")
        return value(shown, param.type) ?: code(expression)
    }

    /** What is wrong with [shown], as a field of [type] holds it. */
    private fun value(shown: String, type: KType?): String? {
        val kind = type?.classifier as? KClass<*> ?: return null
        if (shown.startsWith("{")) return null
        return when {
            kind in WHOLE -> if (isCode(shown) || shown.toLongOrNull() != null) null else problem("whole")
            kind in FRACTION -> if (isCode(shown) || isNumber(shown)) null else problem("number")
            kind == Duration::class -> if (isDuration(shown)) null else problem("duration")
            kind == ResourceLocation::class || kind == ServerLevel::class ->
                if (LOCATION.matches(shown)) null else problem("location")

            else -> null
        }
    }

    private fun isNumber(text: String) = text.toDoubleOrNull() != null && !text.endsWith('.')

    private fun isDuration(text: String) = isNumber(text) || runCatching { Duration.parse(text) }.isSuccess

    /** Whether [text] is an expression rather than a number with a mistake in it. */
    private fun isCode(text: String) = text.any { it.isLetter() || it == '(' }

    /** What is wrong with the Kotlin of a vector written by [helper]. */
    fun vector(expression: String, helper: String): String? {
        val parts = StoryLiterals.vectorParts(expression, helper) ?: return code(expression)
        if (expression.isBlank()) return null
        val wrong = parts.any { it.isBlank() || !(isCode(it) || isNumber(it)) }
        return if (wrong) problem("vector") else parts.firstNotNullOfOrNull(::code)
    }

    /** What the parser finds wrong with the Kotlin [text]. */
    fun code(text: String): String? = if (StoryKotlin.syntax(text)?.valid == false) problem("code") else null

    /** What keeps [name] from being the name of a variable among those [taken]. */
    fun variableName(name: String, taken: Collection<String>): String? = when {
        !NAME.matches(name) || name in KEYWORDS -> problem("name")
        name in taken -> problem("name_taken")
        else -> null
    }

    /** What is wrong with what [variable] starts as. */
    fun variable(variable: StoryVariable): String? {
        if (StoryVariableTypes.startsEmpty(variable.type)) return null
        if (variable.initial.isBlank()) return problem("required")
        val pin = StoryPinType.ofName(variable.type)
        return when (StoryVariableTypes.nameOf(variable.type)) {
            StoryVariableTypes.LIST -> elements(StoryVariableTypes.listParts(variable.initial), variable.initial, listElement(variable.type))
            StoryVariableTypes.MAP -> entries(variable)
            "vector" -> vector(variable.initial, StoryLiterals.vectorHelper(pin.type).orEmpty())
            "bool" -> flag(variable.initial)
            else -> if (pin.type == null) code(variable.initial) else value(pin.text(variable.initial), pin.type) ?: code(variable.initial)
        }
    }

    private fun listElement(type: String?) = StoryPinType.ofName(type?.substringAfter('<')?.removeSuffix(">"))

    private fun flag(code: String) = if (code == "true" || code == "false" || isCode(code)) code(code) else problem("bool")

    private fun elements(parts: List<String>?, written: String, element: StoryPinType): String? =
        if (parts == null) code(written) else parts.firstNotNullOfOrNull { check(it, element) }

    private fun check(written: String, pin: StoryPinType): String? = when (pin) {
        StoryPinType.VECTOR -> vector(written, StoryLiterals.vectorHelper(pin.type).orEmpty())
        StoryPinType.BOOL -> flag(written)
        else -> value(pin.text(written), pin.type) ?: code(written)
    }

    private fun entries(variable: StoryVariable): String? {
        val entries = StoryVariableTypes.mapParts(variable.initial) ?: return code(variable.initial)
        val inner = variable.type?.substringAfter('<')?.removeSuffix(">")
        val key = StoryPinType.ofName(inner?.substringBefore(','))
        val value = StoryPinType.ofName(inner?.substringAfter(',')?.trim())
        return entries.firstNotNullOfOrNull { (at, held) -> check(at, key) ?: check(held, value) }
    }

    /** Everything wrong with [graph]: what the compiler says, and what the editors can see. */
    fun all(graph: StoryGraph): List<StoryCheck> {
        val compiled = StoryGraphCompiler.compile(graph, StoryActionFunctions).problems.map { StoryCheck(it.node, it.message, it.warning) }
        val variables = graph.variables.mapNotNull { variable -> variable(variable)?.let { StoryCheck(null, "${variable.name}: $it") } }
        return compiled + variables + nodes(graph)
    }

    private fun warning(node: String, name: String) = StoryCheck(node, problem(name), warning = true)

    /** What is wrong with the nodes of [graph]. */
    private fun nodes(graph: StoryGraph): List<StoryCheck> {
        val plain = graph.dissolved()
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
                is StoryNode.Assign -> filled(node.value, free(StoryPins.VALUE)) +
                        listOfNotNull(problem("no_variable").takeIf { graph.variables.none { it.name == node.variable } })

                is StoryNode.Expression -> filled(node.code, true)
                is StoryNode.Code -> listOfNotNull(code(node.code))
                else -> emptyList()
            }
            val empty = node is StoryNode.Say && node.text.isBlank() && free(StoryPins.TEXT)
            val lost = node.id !in reached && needsFlow(node) && node.id !in read
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
        return signature.params.filter { it.implicit == null && (it.group != null || free(it.name)) }.mapNotNull { param ->
            param(param, node.argument(param).orEmpty())?.let { "${StoryCatalog.paramTitle(signature, param)}: $it" }
        }
    }

    private fun pure(node: StoryNode.Pure, free: (String) -> Boolean): List<String> =
        StoryOperators.find(node.operator)?.inputs.orEmpty().filter { free(it.name) }.mapNotNull { input ->
            val written = node.arguments[input.name].orEmpty()
            val wrong = if (written.isBlank() && input.default.isBlank()) problem("required") else code(written)
            wrong?.let { "${StoryCatalog.operatorPinTitle(input.name)}: $it" }
        }

    private fun needsFlow(node: StoryNode) = node !is StoryNode.Start && node !is StoryNode.Pure && node !is StoryNode.Expression

    /** The nodes flow gets to from the starts of [graph]. */
    private fun reached(graph: StoryGraph): Set<String> {
        val seen = HashSet<String>()
        val queue = ArrayDeque(graph.nodes.filterIsInstance<StoryNode.Start>().map { it.id })
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (seen.add(at)) queue += graph.index.flowOut(at)
        }
        return seen
    }
}
