package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions

/** A field of Kotlin in a story: [key] of the node or variable [owner]. */
data class StoryFieldKey(val owner: String, val key: String)

/** Where a field sits in a [StoryCheckScript]: its Kotlin from [start] to [end], in a function from [from] to [to]. */
class StoryFieldSpan(
    val field: StoryFieldKey,
    val type: String?,
    val from: Int,
    val start: Int,
    val end: Int,
    val to: Int,
    val shown: String,
)

/** What goes around the Kotlin of a field for it to mean what it means on its node. */
class StoryFieldFrame(val before: String, val after: String)

/**
 * The script of a story, whose first [length] characters it generated, followed by every field as its own function,
 * the checks of generic blocks, and a property per output worked out on the spot.
 *
 * @param graph the story with its functions and reroutes dissolved, as the script checks it.
 * @param spans the fields it holds.
 * @param left the fields left out of it, empty or broken as they are, for completing what is typed there.
 * @param pins the node and pin behind the name of each such property.
 * @param written type behind the name of each property that stands for a type written in the story or by a command.
 * @param blocks where the check of each generic block is.
 * @param fixed the node and pin behind the name of each property telling what a pin of a generic block is once the
 * type parameters it turns on are fixed.
 * @param typeless the outputs taken as telling no type, which fix nothing in a generic block they are linked into.
 */
class StoryCheckScript(
    val graph: StoryGraph,
    val text: String,
    val length: Int,
    val spans: List<StoryFieldSpan>,
    private val left: Map<StoryFieldKey, StoryFieldFrame>,
    val pins: Map<String, Pair<String, String>>,
    val written: Map<String, String>,
    val blocks: List<StoryBlockSpan>,
    val fixed: Map<String, Pair<String, String>>,
    val typeless: Set<Pair<String, String>>,
) {
    private val byField = spans.associateBy { it.field }

    fun span(field: StoryFieldKey): StoryFieldSpan? = byField[field]

    fun at(offset: Int): StoryFieldSpan? = spans.firstOrNull { offset >= it.from && offset < it.to }

    fun frame(field: StoryFieldKey): StoryFieldFrame? = left[field]

    /** The generic block whose check [offset] is in. */
    fun block(offset: Int): StoryBlockSpan? = blocks.firstOrNull { offset >= it.from && offset < it.to }

    /**
     * The outputs whose type tells nothing by the [types] analysis found for the properties of this script: one with
     * an error, `Nothing`, or something of it, as an empty `List<Nothing>`.
     */
    fun typeless(types: Map<String, String>): Set<Pair<String, String>> =
        pins.entries.filter { (name, _) -> types[name]?.let(::tellsNothing) ?: true }.mapTo(HashSet()) { it.value }

    companion object {
        private val NOTHING = Regex("""(?<![\w.])kotlin\.Nothing(?![\w.])""")

        /** Whether a value of [type] tells nothing of what it is: `Nothing`, or something of it, as `List<Nothing>`. */
        fun tellsNothing(type: String): Boolean = NOTHING.containsMatchIn(type)
    }
}

/**
 * Puts every field of a story under the type it has to be and names every output worked out on the spot, as nothing but
 * the Kotlin compiler can tell what they are. This takes one analysis for all of them, in the file the story compiles to,
 * so they get their colors, problems and types at once, on nodes the flow does not reach too.
 */
object StoryFieldChecks {
    /** The start of the names of the properties that give the types of pins, and of the types written. */
    const val PIN = "storyPin"

    /**
     * A field of the story, [code] as its field shows it.
     *
     * @param checked what stands for it in the script, as long as [code] and with each character where it is.
     * @param syntax the Kotlin that has to parse for it to be checked in place.
     */
    private class Entry(
        val field: StoryFieldKey,
        val type: String?,
        val code: String,
        val frame: StoryFieldFrame,
        val checked: String = code,
        val syntax: String = code,
    )

    private const val VALUE_END = "\n)"
    private const val RAW = "\"\"\""

    fun variableOwner(name: String) = "variable:$name"

    private fun value(owner: String, key: String, type: String?, code: String, form: StoryFieldForm = StoryFieldForm.Code): Entry {
        if (form == StoryFieldForm.Template) {
            val content = StoryTemplates.content(code)
            val checked = StoryTemplates.analyzed(content)
            val frame = StoryFieldFrame("val storyValue: String = $RAW", RAW)
            return Entry(StoryFieldKey(owner, key), type, content, frame, checked, RAW + checked + RAW)
        }
        val declared = type?.let { ": $it" }.orEmpty()
        return Entry(StoryFieldKey(owner, key), type, code, StoryFieldFrame("val storyValue$declared = (\n", VALUE_END))
    }

    /** A field naming a type, checked as the type of a cast. */
    private fun typeField(owner: String, key: String, code: String) =
        Entry(StoryFieldKey(owner, key), null, code, StoryFieldFrame("val storyValue = (null as ", "?)"))

    /** Kotlin with [locals] in reach; what it leaves in the variables [outputs] is read after it, as the outputs of its node are. */
    private fun block(owner: String, key: String, code: String, locals: String, outputs: Set<String>): Entry {
        val read = if (outputs.isEmpty()) "" else outputs.joinToString(prefix = "\nlistOf<Any?>(", postfix = ")")
        return Entry(StoryFieldKey(owner, key), null, code, StoryFieldFrame("${locals}run {\n", "\n}$read"))
    }

    private fun inputType(input: StoryOperatorInput): String? =
        if (input.type == StoryPinType.NUMBER) "Number" else input.type.type?.let(StoryKotlinTypes::name)

    /**
     * The type a value linked into [pin] of [node] has to be, when that does not turn on what is linked.
     */
    fun place(graph: StoryGraph, node: StoryNode, pin: String): String? = when (node) {
        is StoryNode.Branch -> "Boolean".takeIf { pin == StoryPins.CONDITION }
        is StoryNode.Assign -> graph.variables.firstOrNull { it.name == node.variable }?.type
        is StoryNode.Loop -> "Iterable<*>".takeIf { pin == StoryPins.ITEMS }
        is StoryNode.Call -> StoryActionFunctions.signature(StoryActionFunctions.resolved(node))?.params
            ?.firstOrNull { it.name == pin }?.type?.let(StoryKotlinTypes::name)

        is StoryNode.Invoke -> StoryActionFunctions.definition(graph, node)?.params?.firstOrNull { it.name == pin }?.type
        is StoryNode.Return -> graph.owners()[node.id]?.results?.firstOrNull { it.name == pin }?.type
        is StoryNode.Pure -> StoryOperators.inputsOf(node).firstOrNull { it.name == pin }
            ?.takeUnless { it.generic || it.typeName }?.let(::inputType)

        else -> null
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
                graph.variables.firstOrNull { it.name == node.variable }?.type.let { type ->
                    value(node.id, "value", type, node.value, StoryFieldForm.ofName(type))
                }.takeIf { free(StoryPins.VALUE) },
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
                .map { value(node.id, it.name, it.type, node.arguments[it.name].orEmpty(), StoryFieldForm.ofName(it.type)) }

            is StoryNode.Return -> graph.owners()[node.id]?.results.orEmpty().filter { free(it.name) }
                .map { value(node.id, it.name, it.type, node.values[it.name].orEmpty(), StoryFieldForm.ofName(it.type)) }

            is StoryNode.Pure -> StoryOperators.inputsOf(node).filter { free(it.name) && !it.generic }.map { input ->
                if (input.typeName) return@map typeField(node.id, input.name, node.arguments[input.name].orEmpty())
                val code = node.arguments[input.name].orEmpty()
                val form = StoryFieldForm.of(input.param?.type ?: input.type.type)
                value(node.id, input.name, inputType(input), code, form)
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
                ?.let { value(node.id, param.name, it, call.argument(param).orEmpty(), StoryFieldForm.of(param.type)) }
        }
    }

    private fun reads(code: String) = code.isNotBlank() && StoryKotlin.syntax(code)?.valid == true

    /**
     * The types written in [graph] or by what its nodes call, and those of what a wire can make that is not in it yet.
     */
    private fun written(graph: StoryGraph): List<String> = graph.nodes.filterIsInstance<StoryNode.Call>().flatMap { node ->
        StoryActionFunctions.signature(StoryActionFunctions.resolved(node))?.params.orEmpty()
            .mapNotNull { it.type?.let(StoryKotlinTypes::name) }
    } + graph.nodes.filterIsInstance<StoryNode.Invoke>().flatMap { node ->
        StoryActionFunctions.definition(graph, node)?.params.orEmpty().map { it.type }
    } + graph.nodes.filterIsInstance<StoryNode.Pure>().flatMap { node ->
        StoryOperators.inputsOf(node).mapNotNull { input -> input.param?.type?.let(StoryKotlinTypes::name) }
    } + graph.functions.flatMap { function -> (function.params + function.results).map { it.type } } +
        graph.variables.mapNotNull { it.type } + StoryOperators.all.flatMap { it.outputs }.mapNotNull { it.kotlin }

    /** The places the generic outputs of blocks go into, for their types to be read as the parameters are worked out. */
    private fun places(graph: StoryGraph): List<String> = graph.nodes.filterIsInstance<StoryNode.Pure>().flatMap { node ->
        val generic = StoryOperators.find(node.operator)?.outputs.orEmpty().filter { it.generic }.mapTo(HashSet()) { it.name }
        graph.links.filter { it.from == node.id && it.fromPin in generic }
            .mapNotNull { link -> graph.node(link.to)?.let { place(graph, it, link.toPin) } }
    }

    /** The classes of the generic inputs and outputs of blocks, with stars for their parameters, as `List<*>`. */
    private fun shapes(): List<String> = StoryOperators.all.flatMap { operator ->
        operator.inputs.mapNotNull { it.shape } + operator.outputs.mapNotNull { it.shape }
    }.mapNotNull(StoryOperatorDsl::starred)

    /**
     * [code], the script generated from [graph], with what is checked added. Broken Kotlin stays out, not to break the rest.
     *
     * @param typeless outputs an analysis before found to tell no type.
     */
    fun script(graph: StoryGraph, code: String, typeless: Set<Pair<String, String>> = emptySet()): StoryCheckScript {
        val plain = graph.flattened().dissolved()
        val owners = graph.owners()

        /** The parameters of the function [node] is in, for what is written in it to see them. */
        fun declared(node: String) = owners[node]?.params.orEmpty().map { it.name to it.type }
        fun parameters(node: String) = declared(node).joinToString { "${it.first}: ${it.second}" }

        val variables = plain.variables.map { value(variableOwner(it.name), "initial", it.type, it.initial) }
        val fields = variables + plain.nodes.flatMap { nodeFields(plain, it) }
        val (kept, broken) = fields.partition { reads(it.syntax) }
        val left = broken.associate { it.field to it.frame }.toMutableMap()
        val text = StringBuilder(code.trimEnd())
        val length = text.length
        val spans = kept.mapIndexedTo(ArrayList()) { index, entry ->
            val from = text.append("\n\nsuspend fun storyCheck$index(${parameters(entry.field.owner)}) {\n").length
            val start = text.append(entry.frame.before).length
            val end = text.append(entry.checked).length
            val to = text.append(entry.frame.after).append("\n}").length
            StoryFieldSpan(entry.field, entry.type?.let(StoryPinType::shortName), from, start, end, to, entry.code)
        }
        val values = StoryGraphCompiler.pins(graph, StoryActionFunctions).filterValues(::reads)
        val pins = values.entries.withIndex().associate { (index, pin) ->
            text.append("\n\nsuspend fun storyPinOf$index(${parameters(pin.key.first)}) {\nval $PIN$index = (\n")
                .append(pin.value)
                .append("\n)\n}")
            "$PIN$index" to pin.key
        }
        val generic = StoryGenericChecks(plain, values, typeless, ::declared, ::reads)
        val (blocks, fixed) = generic.write(
            text,
            span = { field, shown, at -> spans += StoryFieldSpan(field, null, at[0], at[1], at[2], at[3], shown) },
            left = { field -> left[field] = StoryFieldFrame("val storyValue = (\n", VALUE_END) },
        )
        val types = (written(plain) + places(plain) + shapes()).filterTo(LinkedHashSet()) { reads("null as $it") }
        val written = types.withIndex().associate { (index, type) -> "${PIN}Type$index" to type }
        written.entries.joinTo(text, "", "\n\nsuspend fun storyTypes() {\n", "}") { (name, type) ->
            "val $name: $type = (null as $type)\n"
        }
        val frames = left.mapValues { (field, frame) ->
            val locals = declared(field.owner).joinToString("") { "val ${it.first}: ${it.second} = TODO()\n" }
            StoryFieldFrame(locals + frame.before, frame.after)
        }
        return StoryCheckScript(plain, text.toString(), length, spans, frames, pins, written, blocks, fixed, typeless)
    }
}
