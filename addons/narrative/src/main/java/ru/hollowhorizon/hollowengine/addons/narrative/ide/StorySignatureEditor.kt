package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.addFlow
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.addValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.edit
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameFlow
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameFunction
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryTypedName

/** A part of the signature of a function: its parameters, its results or its flows, which have no type. */
internal enum class StorySignaturePart(val key: String) {
    PARAM("param"),
    RESULT("result"),
    FLOW("flow");

    val heading: String get() = storyLang("function.$key.heading")
    val addLabel: String get() = storyLang("function.$key.add")
}

/** One entry of a part of a signature: its name, its type unless it is a flow, and how each changes. */
internal class StorySignatureEntry(
    val index: Int,
    val name: String,
    val type: String?,
    val rename: (String) -> Unit,
    val retype: (String) -> Unit,
    val remove: () -> Unit,
)

/** The signature of [function] as the editors change it: the card of the function and the inspector alike. */
internal class StorySignatureEditor(private val state: StoryEditorState, private val function: StoryNode.Function) {
    private fun sectionKey(part: StorySignaturePart) = "${function.id}/${part.key}"

    /** Whether [part] is shown unfolded; every part starts folded. */
    fun isOpen(part: StorySignaturePart): Boolean = sectionKey(part) in state.openSections

    fun toggle(part: StorySignaturePart) = state.toggleSection(sectionKey(part))

    fun rename(typed: String) = state.changeRoot(mergeKey = "${function.id}/name") { renameFunction(function.id, typed.trim()) }

    fun add(part: StorySignaturePart) = state.changeRoot {
        when (part) {
            StorySignaturePart.FLOW -> addFlow(function.id)
            else -> addValue(function.id, part == StorySignaturePart.RESULT)
        }
    }

    fun entries(part: StorySignaturePart): List<StorySignatureEntry> = when (part) {
        StorySignaturePart.PARAM -> values(part, function.params)
        StorySignaturePart.RESULT -> values(part, function.results)
        StorySignaturePart.FLOW -> function.flows.mapIndexed { index, flow ->
            StorySignatureEntry(
                index, flow, null,
                rename = { typed -> named(typed) { state.changeRoot(mergeKey = mergeKey(part, "name", index)) { renameFlow(function.id, flow, it) } } },
                retype = {},
                remove = { state.changeRoot { renameFlow(function.id, flow, null) } },
            )
        }
    }

    private fun values(part: StorySignaturePart, values: List<StoryTypedName>): List<StorySignatureEntry> {
        val result = part == StorySignaturePart.RESULT
        return values.mapIndexed { index, value ->
            StorySignatureEntry(
                index, value.name, value.type,
                rename = { typed ->
                    named(typed) { state.changeRoot(mergeKey = mergeKey(part, "name", index)) { renameValue(function.id, value.name, it, result) } }
                },
                retype = { typed -> state.changeRoot(mergeKey = mergeKey(part, "type", index)) { retyped(index, typed.trim(), result) } },
                remove = { state.changeRoot { removeValue(function.id, value.name, result) } },
            )
        }
    }

    private fun StoryGraph.retyped(index: Int, type: String, result: Boolean) =
        edit(function.id) { node ->
            node as StoryNode.Function
            fun List<StoryTypedName>.typed() = mapIndexed { at, it -> if (at == index) it.copy(type = type) else it }
            if (result) node.copy(results = node.results.typed()) else node.copy(params = node.params.typed())
        }

    private fun mergeKey(part: StorySignaturePart, what: String, index: Int) = "${function.id}/${part.key}-$what-$index"

    /** Calls [write] with [typed] trimmed, unless nothing is left of it: a value cannot go without a name. */
    private fun named(typed: String, write: (String) -> Unit) {
        typed.trim().takeIf { it.isNotEmpty() }?.let(write)
    }
}
