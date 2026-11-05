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
 * @param places the type behind the name of each property that stands for what links go into.
 */
internal class StoryCheckScript(
    val text: String,
    val length: Int,
    val spans: List<StoryFieldSpan>,
    private val left: Map<StoryFieldKey, StoryFieldFrame>,
    val pins: Map<String, Pair<String, String>>,
    val places: Map<String, String>,
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

    /** The start of the names of the properties that give what links go into. */
    const val PLACE = "storyPlace"

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
            is StoryNode.Invoke -> StoryActionFunctions.definition(graph, node)?.params.orEmpty().filter { free(it.name) }
                .map { value(node.id, it.name, it.type, node.arguments[it.name].orEmpty()) }

            is StoryNode.Return -> graph.owners()[node.id]?.results.orEmpty().filter { free(it.name) }
                .map { value(node.id, it.name, it.type, node.values[it.name].orEmpty()) }

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

    /** The types the nodes of [graph] and those a wire can make for its variables want what is linked to them to be of. */
    private fun places(graph: StoryGraph): Set<String> = (graph.nodes.filterIsInstance<StoryNode.Call>().flatMap { node ->
        StoryActionFunctions.signature(StoryActionFunctions.resolved(node))?.params.orEmpty()
            .mapNotNull { it.type?.let(StoryKotlinTypes::name) }
    } + graph.nodes.filterIsInstance<StoryNode.Invoke>().flatMap { node ->
        StoryActionFunctions.definition(graph, node)?.params.orEmpty().map { it.type }
    } + graph.functions.flatMap { function -> function.results.map { it.type } } + graph.variables.mapNotNull { it.type })
        .filterTo(LinkedHashSet()) { reads("null as $it") }

    /** The types of what a wire can make that is not in [graph] yet can give. */
    private fun values(graph: StoryGraph): Set<String> =
        (graph.variables.mapNotNull { it.type } + StoryOperators.all.flatMap { it.outputs }.mapNotNull { it.kotlin } +
            graph.functions.flatMap { function -> (function.params + function.results).map { it.type } })
            .toSet().filterTo(LinkedHashSet()) { reads("null as $it") }

    /** [code], the script generated from [graph], with what is checked added. Broken Kotlin stays out, not to break the rest. */
    fun script(graph: StoryGraph, code: String): StoryCheckScript {
        val plain = graph.flattened().dissolved()
        val owners = graph.owners()

        /** The parameters of the function [node] is in, for what is written in it to see them. */
        fun parameters(node: String) = owners[node]?.params.orEmpty().joinToString { "${it.name}: ${it.type}" }

        val variables = plain.variables.map { value(variableOwner(it.name), "initial", it.type, it.initial) }
        val (kept, left) = (variables + plain.nodes.flatMap { nodeFields(plain, it) }).partition { reads(it.code) }
        val text = StringBuilder(code.trimEnd())
        val length = text.length
        val spans = kept.mapIndexed { index, entry ->
            val from = text.append("\n\nsuspend fun storyCheck$index(${parameters(entry.field.owner)}) {\n").length
            val start = text.append(entry.frame.before).length
            val end = text.append(entry.code).length
            val to = text.append(entry.frame.after).append("\n}").length
            StoryFieldSpan(entry.field, entry.type?.let(StoryPinType::shortName), from, start, end, to)
        }
        val pins = StoryGraphCompiler.pins(graph, StoryActionFunctions).filterValues(::reads).entries.withIndex()
            .associate { (index, pin) ->
                text.append("\n\nsuspend fun storyPinOf$index(${parameters(pin.key.first)}) {\nval $PIN$index = (\n")
                    .append(pin.value)
                    .append("\n)\n}")
                "$PIN$index" to pin.key
            }
        val wanted = places(plain).withIndex().associate { (index, type) -> "$PLACE$index" to type }
        wanted.entries.joinTo(text, "", "\n\nsuspend fun storyPlaces() {\n", "}") { (name, type) ->
            "val $name: $type = (null as $type)\n"
        }
        values(plain).withIndex().joinTo(text, "", "\n\nsuspend fun storyValues() {\n", "}") { (index, type) ->
            "val ${PIN}Value$index: $type = (null as $type)\n"
        }
        val placed = wanted.mapValues { StoryPinType.shortName(it.value) }
        val frames = left.associate { entry ->
            val locals = owners[entry.field.owner]?.params.orEmpty()
                .joinToString("") { "val ${it.name}: ${it.type} = TODO()\n" }
            entry.field to StoryFieldFrame(locals + entry.frame.before, entry.frame.after)
        }
        return StoryCheckScript(text.toString(), length, spans, frames, pins, placed)
    }
}
