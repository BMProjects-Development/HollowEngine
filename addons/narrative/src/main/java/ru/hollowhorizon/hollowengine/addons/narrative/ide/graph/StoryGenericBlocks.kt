package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperators
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorState
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators

/**
 * Blocks of types follow what they are linked to, after every change of a graph rather than only as a link is made:
 * a block whose value is linked becomes the block of the type at the other end, with its pins, and one linked to
 * nothing at all goes back to `<T>`. A block keeps its type while something still hangs on its other pins.
 */
internal object StoryGenericBlocks {
    private const val ROUNDS = 4

    fun settled(graph: StoryGraph, state: StoryEditorState): StoryGraph {
        var current = graph
        repeat(ROUNDS) {
            val boxes = StoryGraphBoxes(current, state)
            val next = current.nodes.filterIsInstance<StoryNode.Pure>().fold(current) { each, node ->
                val family = TypeOperators.familyOf(node.operator) ?: return@fold each
                val operator = settledOperator(current, boxes, node, family)
                if (operator == node.operator) each else each.retyped(node, operator)
            }
            if (next === current) return current
            current = next
        }
        return current
    }

    /** The block of [family] that [node] is to be by what its value is linked to. */
    private fun settledOperator(graph: StoryGraph, boxes: StoryGraphBoxes, node: StoryNode.Pure, family: String): String {
        val linked = graph.links.filter { it.from == node.id || it.to == node.id }
        val types = if (family == TypeOperators.UNPACK) {
            linked.filter { it.to == node.id && it.toPin == TypeOperators.SUBJECT }.map { boxes.outputKotlin(it.from, it.fromPin) }
        } else {
            linked.filter { it.from == node.id && it.fromPin == TypeOperators.SUBJECT }.map { boxes.inputKotlin(it.to, it.toPin) }
        }
        types.filterNotNull().firstNotNullOfOrNull { TypeOperators.typed(family, it) }?.let { return it.id }
        return if (linked.isEmpty()) family else node.operator
    }

    /** The graph with [node] made the block [operator], without the links and fields of pins it no longer has. */
    private fun StoryGraph.retyped(node: StoryNode.Pure, operator: String): StoryGraph {
        val found = StoryOperators.find(operator) ?: return this
        val inputs = found.inputs.mapTo(HashSet()) { it.name }
        val outputs = found.outputs.mapTo(HashSet()) { it.name }
        return copy(
            nodes = nodes.map {
                if (it.id == node.id) node.copy(operator = operator, arguments = node.arguments.filterKeys(inputs::contains)) else it
            },
            links = links.filter { link ->
                (link.to != node.id || link.toPin in inputs) && (link.from != node.id || link.fromPin in outputs)
            },
        )
    }
}
