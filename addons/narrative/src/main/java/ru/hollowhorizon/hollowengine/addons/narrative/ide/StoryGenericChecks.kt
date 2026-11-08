package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperators
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryTypeShape

class StoryBlockSpan(val node: String, val from: Int, val to: Int)

/** A parameter inferred by K2 from one linked input, independently of the other parameters of its shape. */
data class StoryGenericKey(val node: String, val pin: String, val parameter: String)

/**
 * Writes Kotlin constraints for generic pins. The first informative link fixes each parameter independently;
 * output destinations supply parameters the links did not fix. Fields are checked after those choices, so they
 * cannot widen a linked type. K2 performs extraction from shapes and checking, including inherited generics.
 */
internal class StoryGenericChecks(
    private val graph: StoryGraph,
    private val values: Map<Pair<String, String>, String>,
    private val unknown: Set<StoryGenericKey>,
    private val parameters: (String) -> List<Pair<String, String>>,
    private val reads: (String) -> Boolean,
) {
    private class Slot(val index: Int, val pin: String, val shape: StoryTypeShape) {
        val parameters = shape.parameters
    }

    private class Block(val index: Int, val node: StoryNode.Pure, val inputs: List<Slot>, val outputs: List<Slot>) {
        val slots get() = inputs + outputs
    }

    private class Binding(val slot: Slot, val into: String? = null)
    private class Fix(val bindings: Map<String, Binding>) {
        val parameters get() = bindings.keys.toList()
    }

    private val blocks = graph.nodes.filterIsInstance<StoryNode.Pure>().mapNotNull { node ->
        val operator = StoryOperators.find(node.operator) ?: return@mapNotNull null
        val inputs = StoryOperators.inputsOf(node).filter { it.generic }.map { it.name to it.shape!! }
        val outputs = operator.outputs.filter { it.generic }.map { it.name to it.shape!! }
        if (inputs.isEmpty() && outputs.isEmpty()) return@mapNotNull null
        val slots = (inputs + outputs).mapIndexed { index, (pin, shape) -> Slot(index, pin, shape) }
        Triple(node, slots, inputs.size)
    }.mapIndexed { index, (node, slots, inputs) ->
        node.id to Block(index, node, slots.take(inputs), slots.drop(inputs))
    }.toMap()

    private val links = graph.index.values
    private val fixes = HashMap<String, Fix?>()
    private val fixing = HashSet<String>()

    private fun source(block: Block, slot: Slot) =
        links[block.node.id to slot.pin]?.let { it.from to it.fromPin }?.takeIf { it in values }

    private fun fix(block: Block): Fix? {
        if (block.node.id in fixes) return fixes[block.node.id]
        if (!fixing.add(block.node.id)) return null
        val bindings = LinkedHashMap<String, Binding>()
        block.inputs.filter { source(block, it) != null }.forEach { slot ->
            slot.parameters.filter { StoryGenericKey(block.node.id, slot.pin, it) !in unknown }.forEach { name ->
                bindings.putIfAbsent(name, Binding(slot))
            }
        }
        graph.links.filter { it.from == block.node.id }.forEach { link ->
            val slot = block.outputs.firstOrNull { it.pin == link.fromPin } ?: return@forEach
            if (bindings.keys.containsAll(slot.parameters)) return@forEach
            val into = sinkOf(link.to, link.toPin) ?: return@forEach
            slot.parameters.forEach { bindings.putIfAbsent(it, Binding(slot, into)) }
        }
        fixing -= block.node.id
        val found = bindings.takeIf { it.isNotEmpty() }?.let(::Fix)
        fixes[block.node.id] = found
        return found
    }

    private fun sinkOf(node: String, pin: String): String? {
        val target = graph.node(node) ?: return null
        val block = blocks[node]
        val slot = block?.inputs?.firstOrNull { it.pin == pin }
        if (block == null || slot == null) {
            return StoryFieldChecks.place(graph, target, pin)?.takeIf { reads("null as $it") }?.let { "storySink<$it>()" }
        }
        val fix = fix(block) ?: return null
        if (fix.bindings.values.any { it.slot == slot && it.into == null }) return null
        if (!fix.parameters.containsAll(slot.parameters)) return null
        return "${fixed(block)}.sink${slot.index}()"
    }

    private fun arguments(block: Block) = parameters(block.node.id).joinToString { it.first }
    private fun owner(block: Block) = parameters(block.node.id).joinToString { "${it.first}: ${it.second}" }
    private fun fixed(block: Block) = "storyFixed${block.index}(${arguments(block)})"
    private fun shapeIndex(block: Block, slot: Slot) = block.slots.first { it.shape == slot.shape }.index
    private fun shape(block: Block, slot: Slot) = "StoryShape${block.index}_${shapeIndex(block, slot)}"
    private fun read(block: Block, slot: Slot) = "storyRead${block.index}_${shapeIndex(block, slot)}"
    private fun place(block: Block, slot: Slot) = "storyPlace${block.index}_${shapeIndex(block, slot)}"
    private fun typeParameters(names: List<String>) = if (names.isEmpty()) "" else names.joinToString(prefix = "<", postfix = ">")

    private fun typedSource(block: Block, slot: Slot): String? {
        val source = source(block, slot) ?: return null
        val from = blocks[source.first]
        val fix = from?.let(::fix)
        val out = from?.outputs?.firstOrNull { it.pin == source.second }
        if (fix != null && out != null && fix.parameters.containsAll(out.parameters)) {
            return "${fixed(from)}.pin${out.index}()"
        }
        return values.getValue(source)
    }

    class Written(
        val blocks: List<StoryBlockSpan>,
        val fixed: Map<String, Pair<String, String>>,
        val evidence: Map<String, StoryGenericKey>,
    )

    /** Writes checks and properties carrying inferred pins and individual parameter evidence. */
    fun write(
        text: StringBuilder,
        span: (StoryFieldKey, String, IntArray) -> Unit,
        left: (StoryFieldKey) -> Unit,
    ): Written {
        if (blocks.isEmpty()) return Written(emptyList(), emptyMap(), emptyMap())
        text.append("\n\nclass StorySink<in X>\nfun <X> storySink(): StorySink<X> = StorySink()\nclass StoryType<X>")
        val spans = ArrayList<StoryBlockSpan>()
        val fixed = LinkedHashMap<String, Pair<String, String>>()
        val evidence = LinkedHashMap<String, StoryGenericKey>()
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
            text.append("\n\nsuspend fun storyBlock${block.index}(${owner(block)}) {\n")
            linked.forEach { (slot, expression) ->
                text.append("val storyLinked${slot.index} = ($expression)!!\n")
                slot.parameters.forEachIndexed { index, parameter ->
                    val name = "${StoryFieldChecks.PIN}Evidence${block.index}_${slot.index}_$index"
                    // Original expressions keep evidence independent of the previous pass's fixed pins.
                    val original = values.getValue(source(block, slot)!!)
                    text.append("val $name = ${read(block, slot)}(($original)!!).pin$index()\n")
                    evidence[name] = StoryGenericKey(block.node.id, slot.pin, parameter)
                }
            }
            val from = text.length
            if (fix == null) text.append("storyCall${block.index}(") else text.append("${fixed(block)}.call(")
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
        return Written(spans, fixed, evidence)
    }

    private fun declare(text: StringBuilder, block: Block, fix: Fix?, given: List<Slot>) {
        val n = block.index
        fun slots(slots: List<Slot>) = slots.joinToString { "p${it.index}: ${it.shape.kotlin}" }
        block.slots.distinctBy { it.shape }.forEach { slot ->
            val own = typeParameters(slot.parameters)
            val shape = shape(block, slot) + own
            text.append("\nclass $shape {\n")
            slot.parameters.forEachIndexed { index, parameter ->
                text.append("fun pin$index(): $parameter = TODO()\n")
                text.append("fun type$index(): StoryType<$parameter> = StoryType()\n")
            }
            text.append("}\nfun $own ${read(block, slot)}(value: ${slot.shape.kotlin}): $shape = ${shape(block, slot)}()")
            text.append("\nfun $own ${place(block, slot)}(into: StorySink<${slot.shape.nonNullable.kotlin}>): $shape = ${shape(block, slot)}()\n")
        }
        val told = given.flatMap { it.parameters }.distinct()
        if (fix == null) {
            text.append("\nfun ${typeParameters(told)} storyCall$n(${slots(given)}) {}")
            return
        }
        val fixed = fix.parameters
        val own = typeParameters(fixed)
        text.append("\nclass StoryBlock$n$own {\n")
        text.append("fun ${typeParameters(told - fixed.toSet())} call(${slots(given)}) {}\n")
        block.slots.filter { fixed.containsAll(it.parameters) }.forEach { slot ->
            text.append("fun pin${slot.index}(): ${slot.shape.kotlin} = TODO()\n")
            if (slot in block.inputs) text.append("fun sink${slot.index}(): StorySink<${slot.shape.kotlin}> = StorySink()\n")
        }
        text.append("}\nfun $own storyFix$n(")
        text.append(fixed.mapIndexed { index, name -> "t$index: StoryType<$name>" }.joinToString())
        text.append("): StoryBlock$n$own = StoryBlock$n()")
        val sources = fix.bindings.map { (name, binding) ->
            val slot = binding.slot
            val expression = if (binding.into == null) "${read(block, slot)}((${typedSource(block, slot)})!!)"
                else "${place(block, slot)}(${binding.into})"
            "$expression.type${slot.parameters.indexOf(name)}()"
        }
        text.append("\nsuspend fun storyFixed$n(${owner(block)}) = storyFix$n(${sources.joinToString()})")
    }
}
