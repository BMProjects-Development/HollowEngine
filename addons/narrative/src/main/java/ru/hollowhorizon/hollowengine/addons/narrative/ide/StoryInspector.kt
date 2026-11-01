package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameVariable
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryNodeLayout
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryNodeRow
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryRowField
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
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
        id = "story-node-$selection",
        title = node?.let { StoryNodeKind.of(it).title } ?: storyLang("story"),
        styles = listOf(StoryEditorStylesheet),
        history = state.context?.history,
        content = { if (node == null) StoryProperties(state) else NodeInspector(state, selection) },
    )
}

@Composable
private fun NodeInspector(state: StoryEditorState, selection: StorySelection) {
    val graph = state.read() ?: return
    val node = graph.node(selection.node) ?: return
    if (node is StoryNode.Reroute) return Text(storyLang("reroute.help"), tags = listOf("story-muted"))
    if (node is StoryNode.Say) FormatHint()
    val only = selection.pin?.let(StoryPins::optionIndex)
    StoryNodeLayout(graph, state).of(node, StoryPoint(0f, 0f), detailed = true).rows.forEach { row ->
        val field = row.field
        if (field != null && (only == null || field.key.substringAfterLast('-') == "$only")) {
            key(field.key) { FieldRow(state, "st-${node.id}-${field.key}", row, field) }
        }
    }
}

/** What a row of a node edits, the way the inspector shows it. */
@Composable
private fun FieldRow(state: StoryEditorState, id: String, row: StoryNodeRow, field: StoryRowField) {
    val title = row.label ?: field.placeholder
    when {
        field.vector != null -> VectorRow(state, id, title, row.hint, field.value, field.vector, field.write)
        field.assets.isNotEmpty() -> Column(tags = listOf("insp-field")) {
            FieldLabel(title, row.hint)
            AssetPathField(field.value, title, id, { it.assets(field.assets) }) { field.write(it) }
        }

        else -> TextRow(
            title,
            field.value,
            help = row.hint,
            completions = StoryInsight.field(state).completions.takeIf { field.code },
        ) { field.write(it) }
    }
    Problem(field.problem)
}

/** A vector with its label, the way a row of the inspector shows it. */
@Composable
private fun VectorRow(
    state: StoryEditorState,
    id: String,
    title: String,
    help: String?,
    written: String,
    helper: String,
    write: (String) -> Unit,
) {
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

/** The variables of the story, and the Kotlin it was generated into for those who want to see it. */
@Composable
private fun StoryProperties(state: StoryEditorState) {
    val graph = state.read() ?: return
    Section(storyLang("variables")) {
        graph.variables.forEachIndexed { index, variable ->
            key(index) {
                VariableFields(
                    state,
                    graph,
                    index,
                    variable
                )
            }
        }
        InspectorButton(storyLang("variable.add"), AddIcon) {
            state.change(StoryUndo.ADD) { copy(variables = variables + newVariable(graph)) }
        }
    }
    val kotlin = StoryGraphCompiler.compile(graph, StoryActionFunctions)
    kotlin.problems.forEach { Text(it.message, tags = listOf("story-inspector-problem")) }
    CollapsibleSection(
        storyLang("kotlin"),
        state.showsKotlin,
        id = "story-kotlin-section",
        onToggle = { state.showsKotlin = !state.showsKotlin },
    ) {
        val code = kotlin.code.trim()
        TextField(
            value = code,
            mode = UiTextFieldMode.MULTI_LINE,
            readOnly = true,
            insight = StoryInsight.script(),
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
        if (StoryChecks.variableName(typed, others) == null && StoryKotlin.isAvailable) {
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
    fun Types(values: List<String>, current: String, pick: (String) -> Unit) =
        Pills(values, current, { storyLang("variable.type.$it") }, pick)

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
    if (StoryVariableTypes.startsEmpty(type)) return Text(
        storyLang("variable.empty"),
        tags = listOf("story-muted", "wrapped")
    )
    val pin = StoryPinType.ofName(type)
    val helper = StoryLiterals.vectorHelper(pin.type)
    when {
        helper != null -> VectorRow(state, id, title, help, written, helper, ::write)
        type != null && (pin == StoryPinType.LIST || pin == StoryPinType.MAP) -> {
            var draft by remember(variable.type) { mutableStateOf(StoryVariableTypes.text(written, type)) }
            val shown = draft.takeIf { StoryVariableTypes.expression(it, type) == written } ?: StoryVariableTypes.text(
                written,
                type
            )
            TextRow(
                title,
                shown,
                id = id,
                help = storyLang(if (pin == StoryPinType.LIST) "variable.help.list" else "variable.help.map")
            ) {
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
