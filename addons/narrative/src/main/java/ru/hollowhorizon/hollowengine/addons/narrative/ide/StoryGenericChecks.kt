package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorDsl
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperators

class StoryBlockSpan(val node: String, val from: Int, val to: Int)

/**
 * The checks of generic blocks, written for the compiler to infer their type parameters the way it infers those of a
 * call.
 *
 * @param graph the story with its functions and reroutes dissolved.
 * @param values the Kotlin each output reads, by node and pin, for the values linked in.
 * @param typeless outputs whose type tells nothing, as `Nothing` or a list of it: a value of such a type fixes nothing.
 * @param parameters the parameters of the function a node is in, as a declaration lists them.
 * @param reads whether Kotlin parses, for leaving out what would break the rest of the script.
 */
internal class StoryGenericChecks(
    private val graph: StoryGraph,
    private val values: Map<Pair<String, String>, String>,
    private val typeless: Set<Pair<String, String>>,
    private val parameters: (String) -> List<Pair<String, String>>,
    private val reads: (String) -> Boolean,
) {
    private class Slot(val index: Int, val pin: String, val shape: String) {
        val parameters = StoryOperatorDsl.parametersOf(shape)
    }

    private class Block(val index: Int, val node: StoryNode.Pure, val inputs: List<Slot>, val outputs: List<Slot>) {
        val slots get() = inputs + outputs
    }

    private sealed interface Fix {
        val parameters: List<String>

        class Linked(val slots: List<Slot>, override val parameters: List<String>) : Fix
        class Placed(val slot: Slot, val into: String, override val parameters: List<String>) : Fix
    }

    private val blocks: Map<String, Block> = graph.nodes.filterIsInstance<StoryNode.Pure>().mapNotNull { node ->
        val operator = StoryOperators.find(node.operator) ?: return@mapNotNull null
        val inputs = StoryOperators.inputsOf(node).filter { it.generic }.map { it.name to it.shape!! }
        val outputs = operator.outputs.filter { it.generic }.map { it.name to it.shape!! }
        if (inputs.isEmpty() && outputs.isEmpty()) return@mapNotNull null
        val slots = (inputs + outputs).mapIndexed { index, (pin, shape) -> Slot(index, pin, shape) }
        node to slots
    }.mapIndexed { index, (node, slots) ->
        val inputs = StoryOperators.inputsOf(node).count { it.generic }
        node.id to Block(index, node, slots.take(inputs), slots.drop(inputs))
    }.toMap()

    private val links = graph.index.values
    private val fixes = HashMap<String, Fix?>()
    private val fixing = HashSet<String>()

    private fun source(block: Block, slot: Slot) =
        links[block.node.id to slot.pin]?.let { it.from to it.fromPin }?.takeIf { it in values }

    private fun linked(block: Block): Fix.Linked? {
        val told = LinkedHashSet<String>()
        val slots = block.inputs.filter { slot ->
            val source = source(block, slot)
            source != null && source !in typeless && told.addAll(slot.parameters)
        }
        return if (slots.isEmpty()) null else Fix.Linked(slots, told.toList())
    }

    private fun fix(block: Block): Fix? {
        if (block.node.id in fixes) return fixes[block.node.id]
        if (!fixing.add(block.node.id)) return null
        val found = linked(block) ?: placed(block)
        fixing -= block.node.id
        fixes[block.node.id] = found
        return found
    }

    private fun placed(block: Block): Fix.Placed? = graph.links.asSequence().mapNotNull { link ->
        if (link.from != block.node.id) return@mapNotNull null
        val slot = block.outputs.firstOrNull { it.pin == link.fromPin } ?: return@mapNotNull null
        val into = sinkOf(link.to, link.toPin) ?: return@mapNotNull null
        Fix.Placed(slot, into, slot.parameters)
    }.firstOrNull()

    private fun sinkOf(node: String, pin: String): String? {
        val target = graph.node(node) ?: return null
        val block = blocks[node]
        val slot = block?.inputs?.firstOrNull { it.pin == pin }
        if (block == null || slot == null) {
            return StoryFieldChecks.place(graph, target, pin)?.takeIf { reads("null as $it") }?.let { "storySink<$it>()" }
        }
        val fix = fix(block) ?: return null
        if (fix is Fix.Linked && slot in fix.slots) return null
        if (!fix.parameters.containsAll(slot.parameters)) return null
        return "${fixed(block)}.sink${slot.index}()"
    }

    private fun fixed(block: Block) =
        "storyFixed${block.index}(${parameters(block.node.id).joinToString { it.first }})"

    private fun typedSource(block: Block, slot: Slot): String? {
        val source = source(block, slot) ?: return null
        val from = blocks[source.first]
        val fix = from?.let(::fix)
        val out = from?.outputs?.firstOrNull { it.pin == source.second }
        if (fix is Fix.Linked && out != null && fix.parameters.containsAll(out.parameters)) {
            return "${fixed(from)}.pin${out.index}()"
        }
        return values.getValue(source)
    }

    /**
     * Writes the checks into [text], each field of a generic input in its place among the arguments.
     *
     * @param span tells where a field went: its key, what it shows, and where its argument and its Kotlin start and end.
     * @param left tells of a field that does not parse and stays out.
     * @return where each check is, and the node and pin of each property telling the type of a fixed pin.
     */
    fun write(
        text: StringBuilder,
        span: (StoryFieldKey, String, IntArray) -> Unit,
        left: (StoryFieldKey) -> Unit,
    ): Pair<List<StoryBlockSpan>, Map<String, Pair<String, String>>> {
        if (blocks.isEmpty()) return emptyList<StoryBlockSpan>() to emptyMap()
        text.append("\n\nclass StorySink<in X>\nfun <X> storySink(): StorySink<X> = StorySink()")
        val spans = ArrayList<StoryBlockSpan>()
        val fixed = LinkedHashMap<String, Pair<String, String>>()
        blocks.values.forEach { block ->
            val fix = fix(block)
            val linked = block.inputs.mapNotNull { slot -> typedSource(block, slot)?.let { slot to it } }.toMap()
            val given = block.inputs.filter { slot ->
                val code = block.node.arguments[slot.pin].orEmpty()
                when {
                    slot in linked -> true
                    code.isBlank() -> false
                    reads(code) -> true
                    else -> false.also { left(StoryFieldKey(block.node.id, slot.pin)) }
                }
            }
            declare(text, block, fix, given)
            val owner = parameters(block.node.id).joinToString { "${it.first}: ${it.second}" }
            text.append("\n\nsuspend fun storyBlock${block.index}($owner) {\n")
            linked.forEach { (slot, source) -> text.append("val storyLinked${slot.index} = ($source)!!\n") }
            val from = text.length
            when (fix) {
                is Fix.Linked -> text.append("storyFix${block.index}(")
                    .append(fix.slots.joinToString { "storyLinked${it.index}" }).append(").call(")

                is Fix.Placed -> text.append("${fixed(block)}.call(")
                null -> text.append("storyCall${block.index}(")
            }
            given.forEachIndexed { index, slot ->
                if (index > 0) text.append(", ")
                text.append("p${slot.index} = ")
                if (slot in linked) {
                    text.append("storyLinked${slot.index}")
                } else {
                    val code = block.node.arguments[slot.pin].orEmpty()
                    val argument = text.length
                    val start = text.append("(\n").length
                    val end = text.append(code).length
                    span(StoryFieldKey(block.node.id, slot.pin), code, intArrayOf(argument, start, end, text.append("\n)").length))
                }
            }
            text.append(")")
            spans += StoryBlockSpan(block.node.id, from, text.length)
            fix?.let { found ->
                block.slots.filter { found.parameters.containsAll(it.parameters) }.forEach { slot ->
                    val name = "${StoryFieldChecks.PIN}Fix${block.index}_${slot.index}"
                    text.append("\nval $name = ${fixed(block)}.pin${slot.index}()")
                    fixed[name] = block.node.id to slot.pin
                }
            }
            text.append("\n}")
        }
        return spans to fixed
    }

    private fun declare(text: StringBuilder, block: Block, fix: Fix?, given: List<Slot>) {
        val n = block.index
        fun typeParameters(names: List<String>) = if (names.isEmpty()) "" else names.joinToString(prefix = "<", postfix = "> ")
        fun slots(slots: List<Slot>) = slots.joinToString { "p${it.index}: ${it.shape}" }
        val told = given.flatMap { it.parameters }.distinct()
        if (fix == null) {
            text.append("\n\nfun ${typeParameters(told)}storyCall$n(${slots(given)}) {}")
            return
        }
        val fixed = fix.parameters
        val own = typeParameters(fixed).trimEnd()
        text.append("\n\nclass StoryBlock$n$own {\n")
        text.append("fun ${typeParameters(told - fixed.toSet())}call(${slots(given)}) {}\n")
        block.slots.filter { fixed.containsAll(it.parameters) }.forEach { slot ->
            text.append("fun pin${slot.index}(): ${slot.shape} = TODO()\n")
            if (slot in block.inputs) text.append("fun sink${slot.index}(): StorySink<${slot.shape}> = StorySink()\n")
        }
        text.append("}")
        val made = "StoryBlock$n$own = StoryBlock$n()"
        val owner = parameters(block.node.id).joinToString { "${it.first}: ${it.second}" }
        when (fix) {
            is Fix.Linked -> {
                text.append("\nfun $own storyFix$n(${slots(fix.slots)}): $made")
                val sources = fix.slots.joinToString { "(${typedSource(block, it)})!!" }
                text.append("\nsuspend fun storyFixed$n($owner) = storyFix$n($sources)")
            }

            is Fix.Placed -> {
                text.append("\nfun $own storyPlace$n(into: StorySink<${fix.slot.shape.removeSuffix("?")}>): $made")
                text.append("\nsuspend fun storyFixed$n($owner) = storyPlace$n(${fix.into})")
            }
        }
    }
}
