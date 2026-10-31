package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.inspector.*

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"

/**
 * The inspector of what is selected, or of the story itself when nothing is. Its fields read the file each time they
 * are drawn and write every change back.
 */
internal fun storyInspector(state: StoryEditorState, selection: StorySelection?): InspectorTarget {
    val node = selection?.let { state.read()?.node(it.node) }
    return InspectorTarget(
        id = "story-card-$selection",
        title = node?.let { StoryCardKind.of(it).title } ?: storyLang("story"),
        styles = listOf(StoryEditorStylesheet),
        history = state.context?.history,
        content = { if (node == null) StoryProperties(state) else NodeInspector(state, selection) },
    )
}

@Composable
private fun NodeInspector(state: StoryEditorState, selection: StorySelection) {
    val graph = state.read() ?: return
    when (val node = graph.node(selection.node)) {
        null -> Unit
        is StoryNode.Start -> Row(state, node, "name", storyLang("start.name"), node.name) { copy(name = it.trim()) }
        is StoryNode.Say -> {
            TextRow(storyLang("phrase.speaker"), node.speaker, help = storyLang("phrase.speaker.help")) { value ->
                state.editNode(node.id, "speaker") { (it as StoryNode.Say).copy(speaker = value) }
            }
            TextRow(storyLang("phrase.text"), node.text, help = storyLang("phrase.text.help")) { value ->
                state.editNode(node.id, "text") { (it as StoryNode.Say).copy(text = value) }
            }
        }

        is StoryNode.Call -> CallInspector(state, node)
        is StoryNode.Choose -> ChooseInspector(state, node, selection.pin)
        is StoryNode.Branch -> Row(state, node, "condition", storyLang("branch.placeholder"), node.condition) {
            copy(
                condition = it
            )
        }

        is StoryNode.Assign -> {
            Row(state, node, "variable", storyLang("set.variable"), node.variable) { copy(variable = it.trim()) }
            Row(state, node, "value", storyLang("set.value"), node.value) { copy(value = it) }
        }

        is StoryNode.Code -> TextRow(storyLang("code.text"), node.code, help = storyLang("code.help")) { value ->
            state.editNode(node.id, "code") { (it as StoryNode.Code).copy(code = value) }
        }

        is StoryNode.Expression -> TextRow(storyLang("code.text"), node.code) { value ->
            state.editNode(node.id, "code") { (it as StoryNode.Expression).copy(code = value) }
        }
    }
}

@Composable
private inline fun <reified T : StoryNode> Row(
    state: StoryEditorState,
    node: T,
    field: String,
    label: String,
    value: String,
    crossinline change: T.(String) -> T,
) = TextRow(label, value) { typed -> state.editNode(node.id, field) { (it as T).change(typed) } }

@Composable
private fun CallInspector(state: StoryEditorState, call: StoryNode.Call) {
    val signature = StoryCatalog.signatureOf(call)
    if (signature == null) {
        Text(call.function, tags = listOf("story-muted"))
        return
    }
    signature.params.forEach { param ->
        val shown = StoryLiterals.text(call.argument(param).orEmpty(), param.type)
        TextRow(
            StoryCatalog.paramTitle(signature, param),
            shown,
            help = StoryCatalog.paramHelp(signature, param)
        ) { value ->
            val written = StoryLiterals.expression(value, param.type)
            state.editNode(call.id, param.name) { (it as StoryNode.Call).with(param, written) }
        }
    }
}

@Composable
private fun ChooseInspector(state: StoryEditorState, menu: StoryNode.Choose, pin: String?) {
    val only = pin?.let(StoryPins::optionIndex)
    menu.options.forEachIndexed { index, option ->
        if (only != null && only != index) return@forEachIndexed
        TextRow("${index + 1}", option.text) { typed ->
            state.editNode(menu.id, "option-$index") { node ->
                node as StoryNode.Choose
                node.copy(options = node.options.mapIndexed { at, it -> if (at == index) it.copy(text = typed) else it })
            }
        }
        TextRow(
            storyLang("option.condition"),
            option.condition.orEmpty(),
            help = storyLang("option.condition.help")
        ) { typed ->
            state.editNode(menu.id, "condition-$index") { node ->
                node as StoryNode.Choose
                val condition = typed.ifBlank { null }
                node.copy(options = node.options.mapIndexed { at, it -> if (at == index) it.copy(condition = condition) else it })
            }
        }
    }
}

/** The variables of the story, and the Kotlin it was generated into for those who want to see it. */
@Composable
private fun StoryProperties(state: StoryEditorState) {
    val graph = state.read() ?: return
    Section(storyLang("variables")) {
        graph.variables.forEach { variable ->
            TextRow(
                variable.name,
                variable.initial,
                help = storyLang("variable.help"),
                trailing = {
                    InspectorIconButton(RemoveIcon, storyLang("remove")) {
                        state.change(StoryUndo.REMOVE) { copy(variables = variables - variable) }
                    }
                },
            ) { value ->
                state.change(mergeKey = "variable/${variable.name}") {
                    copy(variables = variables.map { if (it.name == variable.name) it.copy(initial = value) else it })
                }
            }
        }
        InspectorButton(storyLang("variable.add"), AddIcon) {
            state.change(StoryUndo.ADD) { copy(variables = variables + newVariable(graph)) }
        }
    }
    Section(storyLang("kotlin"), help = storyLang("kotlin.help")) {
        val kotlin = StoryGraphCompiler.compile(graph)
        kotlin.problems.forEach { Text(it.message, tags = listOf("story-inspector-problem")) }
        Text(kotlin.code, tags = listOf("story-muted"))
    }
}

private fun newVariable(graph: StoryGraph): StoryVariable {
    val taken = graph.variables.mapTo(HashSet()) { it.name }
    val name = generateSequence(1) { it + 1 }.map { "value$it" }.first { it !in taken }
    return StoryVariable(name, "0")
}
