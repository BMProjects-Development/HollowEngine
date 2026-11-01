package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions

/** A field of Kotlin in a story: [key] of the node or variable [owner]. */
internal data class StoryFieldKey(val owner: String, val key: String)

/** Where a field sits in a [StoryCheckScript]: its Kotlin from [start] to [end], in a function from [from] to [to]. */
internal class StoryFieldSpan(
    val field: StoryFieldKey,
    val type: String?,
    val from: Int,
    val start: Int,
    val end: Int,
    val to: Int,
)

/** What goes around the Kotlin of a field for it to mean what it means on its node. */
internal class StoryFieldFrame(val before: String, val after: String)

/**
 * The script of a story, whose first [length] characters it generated, followed by every field as its own function and
 * a property per output worked out on the spot.
 *
 * @param spans the fields it holds.
 * @param left the fields left out of it, empty or broken as they are, for completing what is typed there.
 * @param pins the node and pin behind the name of each such property.
 */
internal class StoryCheckScript(
    val text: String,
    val length: Int,
    val spans: List<StoryFieldSpan>,
    private val left: Map<StoryFieldKey, StoryFieldFrame>,
    val pins: Map<String, Pair<String, String>>,
) {
    private val byField = spans.associateBy { it.field }

    fun span(field: StoryFieldKey): StoryFieldSpan? = byField[field]

    fun at(offset: Int): StoryFieldSpan? = spans.firstOrNull { offset >= it.from && offset < it.to }

    fun frame(field: StoryFieldKey): StoryFieldFrame? = left[field]
}

/**
 * Puts every field of a story under the type it has to be and names every output worked out on the spot, as nothing but
 * the Kotlin compiler can tell what they are. This takes one analysis for all of them, in the file the story compiles to,
 * so they get their colors, problems and types at once, on nodes the flow does not reach too.
 */
internal object StoryFieldChecks {
    /** The start of the names of the properties that give the types of pins. */
    const val PIN = "storyPin"

    private class Entry(val field: StoryFieldKey, val type: String?, val code: String, val frame: StoryFieldFrame)

    private const val VALUE_END = "\n)"

    fun variableOwner(name: String) = "variable:$name"

    private fun value(owner: String, key: String, type: String?, code: String): Entry {
        val declared = type?.let { ": $it" }.orEmpty()
        return Entry(StoryFieldKey(owner, key), type, code, StoryFieldFrame("val storyValue$declared = (\n", VALUE_END))
    }

    /** Kotlin with [locals] in reach; what it leaves in the variables [outputs] is read after it, as the outputs of its node are. */
    private fun block(owner: String, key: String, code: String, locals: String, outputs: Set<String>): Entry {
        val read = if (outputs.isEmpty()) "" else outputs.joinToString(prefix = "\nlistOf<Any?>(", postfix = ")")
        return Entry(StoryFieldKey(owner, key), null, code, StoryFieldFrame("${locals}run {\n", "\n}$read"))
    }

    private fun nodeFields(graph: StoryGraph, node: StoryNode): List<Entry> {
        fun free(pin: String) = (node.id to pin) !in graph.index.values
        return when (node) {
            is StoryNode.Branch -> listOfNotNull(value(node.id, "condition", "Boolean", node.condition).takeIf {
                free(
                    StoryPins.CONDITION
                )
            })

            is StoryNode.Assign -> listOfNotNull(
                value(
                    node.id,
                    "value",
                    graph.variables.firstOrNull { it.name == node.variable }?.type,
                    node.value
                ).takeIf { free(StoryPins.VALUE) },
            )

            is StoryNode.Loop -> listOfNotNull(value(node.id, "items", "Iterable<*>", node.items).takeIf {
                free(
                    StoryPins.ITEMS
                )
            })

            is StoryNode.Expression -> listOf(value(node.id, "code", null, node.code))
            is StoryNode.Choose -> node.options.mapIndexedNotNull { index, option ->
                option.condition?.let { value(node.id, "condition-$index", "Boolean", it) }
            }

            is StoryNode.Code -> code(graph, node)
            is StoryNode.Call -> call(node, ::free)
            is StoryNode.Pure -> StoryOperators.inputsOf(node).filter { free(it.name) }.map { input ->
                val type =
                    if (input.type == StoryPinType.NUMBER) "Number" else input.type.type?.let(StoryKotlinTypes::name)
                value(node.id, input.name, type, node.arguments[input.name].orEmpty())
            }

            else -> emptyList()
        }
    }

    private fun code(graph: StoryGraph, node: StoryNode.Code): List<Entry> {
        val inputs = node.inputs.keys.filter { (node.id to it) !in graph.index.values }
        val written = inputs.map { value(node.id, "in-$it", null, node.inputs[it].orEmpty()) }
        if (inputs.size != node.inputs.size) return written
        val locals =
            node.inputs.entries.joinToString("") { (name, code) -> "val $name = (\n$code\n)\n" } + node.outputs.entries.joinToString(
                ""
            ) { (name, type) -> "var $name: $type? = null\n" }
        return written + block(node.id, "code", node.code, locals, node.outputs.keys)
    }

    private fun call(node: StoryNode.Call, free: (String) -> Boolean): List<Entry> {
        val call = StoryActionFunctions.resolved(node)
        val signature = StoryActionFunctions.signature(call) ?: return emptyList()
        return signature.params.filter { free(it.name) }.mapNotNull { param ->
            param.type?.let(StoryKotlinTypes::name)
                ?.let { value(node.id, param.name, it, call.argument(param).orEmpty()) }
        }
    }

    private fun reads(code: String) = code.isNotBlank() && StoryKotlin.syntax(code)?.valid == true

    /** [code], the script generated from [graph], with what is checked added. Broken Kotlin stays out, not to break the rest. */
    fun script(graph: StoryGraph, code: String): StoryCheckScript {
        val plain = graph.dissolved()
        val variables = plain.variables.map { value(variableOwner(it.name), "initial", it.type, it.initial) }
        val (kept, left) = (variables + plain.nodes.flatMap { nodeFields(plain, it) }).partition { reads(it.code) }
        val text = StringBuilder(code.trimEnd())
        val length = text.length
        val spans = kept.mapIndexed { index, entry ->
            val from = text.append("\n\nsuspend fun storyCheck$index() {\n").length
            val start = text.append(entry.frame.before).length
            val end = text.append(entry.code).length
            val to = text.append(entry.frame.after).append("\n}").length
            StoryFieldSpan(entry.field, entry.type?.let(StoryPinType::shortName), from, start, end, to)
        }
        val pins = StoryGraphCompiler.pins(graph, StoryActionFunctions).filterValues(::reads).entries.withIndex()
            .associate { (index, pin) ->
                text.append("\n\nsuspend fun storyPinOf$index() {\nval $PIN$index = (\n").append(pin.value)
                    .append("\n)\n}")
                "$PIN$index" to pin.key
            }
        return StoryCheckScript(text.toString(), length, spans, left.associate { it.field to it.frame }, pins)
    }
}
