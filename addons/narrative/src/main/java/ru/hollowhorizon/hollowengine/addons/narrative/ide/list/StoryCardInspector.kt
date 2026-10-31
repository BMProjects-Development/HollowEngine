package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameVariable
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.TextField
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeInsight
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextFieldMode

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val KotlinLine = 12
private const val KotlinInset = 12

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
            FormatHint()
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

        is StoryNode.Loop -> Row(state, node, "items", storyLang("loop.items"), node.items) { copy(items = it) }
        is StoryNode.Pure -> PureInspector(state, node)
        is StoryNode.Reroute -> Text(storyLang("reroute.help"), tags = listOf("story-muted"))
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
private fun PureInspector(state: StoryEditorState, node: StoryNode.Pure) {
    StoryOperators.find(node.operator)?.inputs?.forEach { input ->
        TextRow(StoryCatalog.operatorPinTitle(input.name), input.type.text(node.arguments[input.name].orEmpty())) { typed ->
            val written = input.type.expression(typed)
            state.editNode(node.id, input.name) { each ->
                each as StoryNode.Pure
                each.copy(arguments = if (written.isBlank()) each.arguments - input.name else each.arguments + (input.name to written))
            }
        }
    }
}

@Composable
private fun CallInspector(state: StoryEditorState, call: StoryNode.Call) {
    val signature = StoryCatalog.signatureOf(call)
    if (signature == null) {
        Text(call.function, tags = listOf("story-muted"))
        return
    }
    signature.params.filter { it.implicit == null }.forEach { param ->
        val written = call.argument(param).orEmpty()
        val title = StoryCatalog.paramTitle(signature, param)
        val help = StoryCatalog.paramHint(signature, param)
        fun write(next: String) = state.editNode(call.id, param.name) { (it as StoryNode.Call).with(param, next) }
        val helper = StoryLiterals.vectorHelper(param.type)
        if (helper != null) VectorRow(state, "st-${call.id}-${param.name}", title, help, written, helper, ::write)
        else if (param.assets.isNotEmpty()) Column(tags = listOf("insp-field")) {
            FieldLabel(title, help)
            AssetPathField(StoryLiterals.text(written, param.type), title, "st-${call.id}-${param.name}", { it.assets(param.assets) }) {
                write(StoryLiterals.expression(it, param.type))
            }
        }
        else TextRow(title, StoryLiterals.text(written, param.type), help = help) { write(StoryLiterals.expression(it, param.type)) }
        Problem(StoryChecks.param(param, written))
    }
}

/** A vector with its label, the way a row of the inspector shows it. */
@Composable
private fun VectorRow(state: StoryEditorState, id: String, title: String, help: String?, written: String, helper: String, write: (String) -> Unit) {
    Column(tags = listOf("insp-field")) {
        FieldLabel(title, help)
        StoryVectorField(
            id, written, helper, state.context?.readOnly == true, null, listOf("insp-input"),
            Modifier.size(100.percent, 22.px), fontSize = 9f, write = write,
        )
    }
}

@Composable
private fun Problem(problem: String?) {
    problem?.let { Text(it, tags = listOf("story-inspector-problem")) }
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
        graph.variables.forEachIndexed { index, variable -> key(index) { VariableFields(state, graph, index, variable) } }
        InspectorButton(storyLang("variable.add"), AddIcon) {
            state.change(StoryUndo.ADD) { copy(variables = variables + newVariable(graph)) }
        }
    }
    val kotlin = StoryGraphCompiler.compile(graph)
    kotlin.problems.forEach { Text(it.message, tags = listOf("story-inspector-problem")) }
    CollapsibleSection(
        storyLang("kotlin"), state.showsKotlin, id = "story-kotlin-section", onToggle = { state.showsKotlin = !state.showsKotlin },
    ) {
        val code = kotlin.code.trim()
        TextField(
            value = code,
            mode = UiTextFieldMode.MULTI_LINE,
            readOnly = true,
            insight = UiCodeInsight(highlighter = StoryKotlinHighlighter),
            fontSize = 9f,
            id = "story-kotlin",
            tags = listOf("story-kotlin"),
            modifier = Modifier.size(100.percent, (code.lines().size * KotlinLine + KotlinInset).px),
        )
    }
}

/** A variable: its name, what it starts as, and its type. A name is kept only once it can be one. */
@Composable
private fun VariableFields(state: StoryEditorState, graph: StoryGraph, index: Int, variable: StoryVariable) {
    var draft by remember(variable.name) { mutableStateOf(variable.name) }
    val others = graph.variables.filter { it !== variable }.map { it.name }
    val wrong = StoryChecks.variableName(draft, others)
    TextRow(
        storyLang("variable.name"),
        draft,
        id = "st-variable-name-$index",
        trailing = {
            InspectorIconButton(RemoveIcon, storyLang("remove")) {
                state.change(StoryUndo.REMOVE) { copy(variables = variables - variable) }
            }
        },
    ) { typed ->
        draft = typed
        if (StoryChecks.variableName(typed, others) == null) {
            state.change(mergeKey = "variable-name/$index") { renameVariable(variable.name, typed) }
        }
    }
    Problem(wrong)
    VariableType(state, variable)
    VariableInitial(state, index, variable)
}

/** The type of a variable, with what a list or a map of that type holds. */
@Composable
private fun VariableType(state: StoryEditorState, variable: StoryVariable) {
    fun retype(next: StoryVariable) = state.change {
        copy(variables = variables.map { if (it.name == variable.name) next else it })
    }
    @Composable
    fun Types(values: List<String>, current: String, pick: (String) -> Unit) = Pills(values, current, { storyLang("variable.type.$it") }, pick)

    val types = StoryVariableTypes
    val kind = types.nameOf(variable.type)
    Types(types.names, kind) { retype(types.retyped(variable, it)) }
    when (kind) {
        types.LIST -> {
            Label(storyLang("variable.element"))
            Types(types.elements, types.elementOf(variable.type)) { retype(types.list(variable, it)) }
        }

        types.MAP -> {
            val key = types.keyOf(variable.type)
            val value = types.valueOf(variable.type)
            Label(storyLang("variable.key"))
            Types(types.keys, key) { retype(types.map(variable, it, value)) }
            Label(storyLang("variable.value"))
            Types(types.elements, value) { retype(types.map(variable, key, it)) }
        }
    }
}

/** What a variable starts as, in the way its type is typed; an entity starts empty and is set while the story runs. */
@Composable
private fun VariableInitial(state: StoryEditorState, index: Int, variable: StoryVariable) {
    val title = storyLang("variable.initial")
    val help = storyLang("variable.help")
    val id = "st-variable-$index"
    val written = variable.initial
    val type = variable.type
    fun write(initial: String) = state.change(mergeKey = "variable/$index") {
        copy(variables = variables.map { if (it.name == variable.name) it.copy(initial = initial) else it })
    }
    if (StoryVariableTypes.startsEmpty(type)) return Text(storyLang("variable.empty"), tags = listOf("story-muted", "wrapped"))
    val pin = StoryPinType.ofName(type)
    val helper = StoryLiterals.vectorHelper(pin.type)
    when {
        helper != null -> VectorRow(state, id, title, help, written, helper, ::write)
        type != null && (pin == StoryPinType.LIST || pin == StoryPinType.MAP) -> {
            var draft by remember(variable.type) { mutableStateOf(StoryVariableTypes.text(written, type)) }
            val shown = draft.takeIf { StoryVariableTypes.expression(it, type) == written } ?: StoryVariableTypes.text(written, type)
            TextRow(title, shown, id = id, help = storyLang(if (pin == StoryPinType.LIST) "variable.help.list" else "variable.help.map")) {
                draft = it
                write(StoryVariableTypes.expression(it, type))
            }
        }

        else -> TextRow(title, pin.text(written), id = id, help = help) { write(pin.expression(it)) }
    }
    Problem(StoryChecks.variable(variable))
}

/** What can be written in a line: the markup of text, shown in place of a toolbar. */
@Composable
private fun FormatHint() = Text(storyLang("phrase.format"), tags = listOf("story-muted", "wrapped"))

private fun newVariable(graph: StoryGraph): StoryVariable {
    val taken = graph.variables.mapTo(HashSet()) { it.name }
    val name = generateSequence(1) { it + 1 }.map { "value$it" }.first { it !in taken }
    return StoryVariable(name, "0.0", "Double")
}
