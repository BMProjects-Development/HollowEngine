package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFieldForm
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPinType
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPins
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryTypedName
import ru.hollowhorizon.hollowengine.addons.narrative.graph.definition
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySignatureEditor
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySignaturePart
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/** The pin of [value], typed as it is declared. */
private fun pinOf(value: StoryTypedName) =
    StoryPinSpec(value.name, StoryPinType.ofName(value.type), value.type)

/** The function [node] belongs to, which sits in the story around the body the editor shows. */
private fun StoryNodeLayout.Rows.ownerOf(node: StoryNode): StoryNode.Function? =
    layout.state.root()?.owners()?.get(node.id)

/** A function as its card shows it: its name, then its parameters, results and flows, each part folded until opened. */
internal fun StoryNodeLayout.Rows.signature(function: StoryNode.Function) {
    val signature = StorySignatureEditor(layout.state, function)
    row(
        storyLang("function.name"),
        field = StoryRowField("name", function.name, write = signature::rename),
        hint = storyLang("function.open"),
    )
    StorySignaturePart.entries.forEach { part(signature, it) }
}

/** The heading of [part] and, when it is open, a row per entry with its name and type, and the button that adds one. */
private fun StoryNodeLayout.Rows.part(signature: StorySignatureEditor, part: StorySignaturePart) {
    val entries = signature.entries(part)
    val open = signature.isOpen(part)
    row("${part.heading} (${entries.size})", section = StoryRowSection(open) { signature.toggle(part) })
    if (!open) return
    entries.forEach { entry ->
        row(
            nameField = StoryRowField("${part.key}-name-${entry.index}", entry.name, write = entry.rename),
            field = entry.type?.let { StoryRowField("${part.key}-type-${entry.index}", it, "Int", typeName = true, write = entry.retype) },
            onRemove = entry.remove,
        )
    }
    row(onAdd = { signature.add(part) }, addLabel = part.addLabel)
}

/** Runs one of the flows of the function it is in, picked by its name. */
internal fun StoryNodeLayout.Rows.signal(node: StoryNode.Signal) {
    flow()
    row(
        storyLang("signal.flow"),
        field = StoryRowField("flow", node.flow, choices = ownerOf(node)?.flows.orEmpty()) { typed ->
            layout.state.editNode(node.id, "flow") { (it as StoryNode.Signal).copy(flow = typed.trim()) }
        },
    )
}

/** Where a function starts: the flow on, and a pin per parameter. */
internal fun StoryNodeLayout.Rows.parameters(node: StoryNode.Parameters) {
    val function = ownerOf(node)
    row(storyLang("function.entry"), output = StoryPinSpec(StoryPins.NEXT, StoryPinType.FLOW))
    function?.params.orEmpty().forEach { row(it.name, output = pinOf(it)) }
}

/** Where a function ends: a value for each of its results. */
internal fun StoryNodeLayout.Rows.giveBack(node: StoryNode.Return) {
    flow(hasOutput = false)
    ownerOf(node)?.results.orEmpty().forEach { result ->
        val written = node.values[result.name].orEmpty()
        val field = typedField(result, written) { next ->
            layout.state.editNode(node.id, result.name) { each ->
                each as StoryNode.Return
                each.copy(values = if (next.isBlank()) each.values - result.name else each.values + (result.name to next))
            }
        }
        val pin = pinOf(result)
        value(node.id, result.name, result.name, pin.type, field, kotlin = pin.kotlin)
    }
}

/** The field of [value], which shows the Kotlin [written] the way its type is easiest to work with; [write] gets Kotlin. */
private fun StoryNodeLayout.Rows.typedField(value: StoryTypedName, written: String, write: (String) -> Unit): StoryRowField {
    val type = StoryFieldForm.typeNamed(value.type)
    val form = StoryFieldForm.of(type)
    return StoryRowField(
        value.name,
        form.shown(written, type),
        code = form != StoryFieldForm.Literal,
        form = form,
        choices = form.choices,
        problem = codeProblem(written),
    ) { typed -> write(form.written(typed, type)) }
}

/** A call of a function: a value for each parameter, a pin for each result, and the story it is from. */
internal fun StoryNodeLayout.Rows.invoke(node: StoryNode.Invoke) {
    val function = StoryActionFunctions.definition(layout.state.root() ?: layout.graph, node)
    flow()
    node.file?.let { file ->
        row(storyLang("invoke.from", ScriptRegistry.display(ScriptId.parse(file, ScriptRegistry.sandboxNamespace))))
    }
    function?.params.orEmpty().forEach { param ->
        val written = node.arguments[param.name].orEmpty()
        val field = typedField(param, written) { next ->
            layout.state.editNode(node.id, param.name) { each ->
                each as StoryNode.Invoke
                each.copy(arguments = if (next.isBlank()) each.arguments - param.name else each.arguments + (param.name to next))
            }
        }
        val pin = pinOf(param)
        value(node.id, param.name, param.name, pin.type, field, kotlin = pin.kotlin)
    }
    function?.results.orEmpty().forEach { row(it.name, output = pinOf(it)) }
    function?.flows.orEmpty().forEach { row(it, output = StoryPinSpec(it, StoryPinType.FLOW)) }
}
