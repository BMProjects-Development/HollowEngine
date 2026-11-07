package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameVariable
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeEditor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnosticSeverity

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val CodeIcon = "hollowengine:textures/gui/icons/code_editor.svg"
private const val KotlinLine = 13
private const val KotlinInset = 28
private const val INSPECTOR_HEIGHT = 22f

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
    if (node is StoryNode.Function) return FunctionInspector(state, node)
    if (node is StoryNode.Parameters) {
        state.root()?.owners()?.get(node.id)?.let { FunctionInspector(state, it) }
        return
    }
    if (node is StoryNode.Say) FormatHint()
    val only = selection.pin?.let(StoryPins::optionIndex)
    StoryGraphBoxes(graph, state).detailed(node).rows.forEach { row ->
        val field = row.field
        if (field != null && (only == null || field.key.substringAfterLast('-') == "$only")) {
            key(field.key) { FieldRow(state, node.id, "st-${node.id}-${field.key}", row, field) }
        }
    }
}

/** What a row of the node [owner] edits, the way the inspector shows it. */
@Composable
private fun FieldRow(state: StoryEditorState, owner: String, id: String, row: StoryNodeRow, field: StoryRowField) {
    val title = row.label ?: field.placeholder
    val insight = fieldInsight(state, owner, field)
    when {
        field.assets.isNotEmpty() -> Column(tags = listOf("insp-field")) {
            FieldLabel(title, row.hint)
            AssetPathField(field.value, title, id, { it.assets(field.assets) }) { field.write(it) }
        }

        else -> Column(tags = listOf("insp-field")) {
            FieldLabel(title, row.hint)
            Row(tags = listOf("insp-input-row")) {
                StoryInputField(
                    id, field, insight, state.context?.readOnly == true, null, false, StoryFieldLook.INSPECTOR,
                    height = INSPECTOR_HEIGHT,
                )
                if (field.color) ColorButton(state, id, field)
                if (field.code) OpenCode(state, StoryFieldKey(owner, field.key), title, field.value, field.write)
            }
        }
    }
    Problems(field.problem, insight.diagnostics)
}

/** Opens the field [key] in the full editor. */
@Composable
private fun OpenCode(
    state: StoryEditorState,
    key: StoryFieldKey,
    title: String,
    value: String,
    write: (String) -> Unit,
) {
    InspectorIconButton(CodeIcon, storyLang("code.open")) { state.codeField = StoryCodeField(key, title, value, write) }
}

@Composable
private fun Problem(problem: String?, warning: Boolean = false) {
    problem?.let { Text(it, tags = listOfNotNull("story-inspector-problem", "warning".takeIf { warning })) }
}

/** Everything wrong with a field: [own], what the editor sees, and [found], what the compiler says of it. */
@Composable
private fun Problems(own: String?, found: List<UiTextDiagnostic>) {
    Problem(own)
    found.distinctBy { it.message }.filter { it.message != own }.forEach {
        Problem(it.message, warning = it.severity != UiTextDiagnosticSeverity.ERROR)
    }
}

/** The variables and imports of the story, and the Kotlin it was generated into for those who want to see it. */
@Composable
private fun StoryProperties(state: StoryEditorState) {
    val graph = state.read() ?: return
    Section(storyLang("variables")) {
        graph.variables.forEachIndexed { index, variable -> key(index) { VariableCard(state, graph, index, variable) } }
        InspectorButton(storyLang("variable.add"), AddIcon) {
            val variable = newVariable(graph)
            state.openVariables += variable.name
            state.change(StoryUndo.ADD) { copy(variables = variables + variable) }
        }
    }
    StoryImports(state, graph)
    val kotlin = StoryGraphCompiler.compile(state.root() ?: graph, StoryActionFunctions)
    kotlin.problems.forEach { Text(StoryChecks.text(it), tags = listOf("story-inspector-problem")) }
    CollapsibleSection(
        storyLang("kotlin"),
        state.showsKotlin,
        id = "story-kotlin-section",
        onToggle = { state.showsKotlin = !state.showsKotlin },
    ) {
        val code = kotlin.code.trimEnd()
        UiCodeEditor(
            value = code,
            onChange = {},
            insight = StoryInsight.script(state, code),
            readOnly = true,
            fontSize = 9f,
            id = "story-kotlin",
            tags = listOf("story-kotlin"),
            modifier = Modifier.size(100.percent, (code.lines().size * KotlinLine + KotlinInset).px),
        )
    }
}

/** What the Kotlin of the story imports on top of what every story does; completion adds to it. */
@Composable
private fun StoryImports(state: StoryEditorState, graph: StoryGraph) {
    CollapsibleSection(
        "${storyLang("imports")} (${graph.imports.size})",
        state.showsImports,
        id = "story-imports-section",
        onToggle = { state.showsImports = !state.showsImports },
    ) {
        graph.imports.forEachIndexed { index, name ->
            key(index) {
                TextRow(
                    "", name, id = "st-import-$index",
                    trailing = {
                        InspectorIconButton(RemoveIcon, storyLang("remove")) {
                            state.change(StoryUndo.REMOVE) { copy(imports = imports.filterIndexed { at, _ -> at != index }) }
                        }
                    },
                ) { typed ->
                    state.change(mergeKey = "import/$index") {
                        copy(imports = imports.mapIndexed { at, it -> if (at == index) typed.trim() else it })
                    }
                }
            }
        }
        InspectorButton(
            storyLang("import.add"),
            AddIcon
        ) { state.change(StoryUndo.ADD) { copy(imports = imports + "") } }
    }
}

/** A variable as a card of its own, which folds to its name and type. */
@Composable
private fun VariableCard(state: StoryEditorState, graph: StoryGraph, index: Int, variable: StoryVariable) {
    val open = variable.name in state.openVariables
    Column(id = "st-variable-card-$index", tags = listOf("story-card")) {
        Row(
            id = "st-variable-head-$index",
            tags = listOf("story-card-head"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick {
                state.openVariables =
                    if (open) state.openVariables - variable.name else state.openVariables + variable.name
            },
        ) {
            DisclosureArrow(open)
            Text(variable.name, tags = listOf("story-card-title"))
            Text(variable.type.orEmpty(), tags = listOf("story-card-type"), modifier = Modifier.grow(1f))
            InspectorIconButton(RemoveIcon, storyLang("remove"), id = "st-variable-remove-$index") {
                state.change(StoryUndo.REMOVE) { copy(variables = variables - variable) }
            }
        }
        if (open) Column(tags = listOf("story-card-body")) { VariableFields(state, graph, index, variable) }
    }
}

/** A variable: its name, what it starts as, and its type. A name is kept only once it can be one. */
@Composable
private fun VariableFields(state: StoryEditorState, graph: StoryGraph, index: Int, variable: StoryVariable) {
    var draft by remember(variable.name) { mutableStateOf(variable.name) }
    val others = graph.variables.filter { it !== variable }.map { it.name }
    val wrong = StoryChecks.variableName(draft, others)
    TextRow(storyLang("variable.name"), draft, id = "st-variable-name-$index") { typed ->
        draft = typed
        if (StoryChecks.variableName(typed, others) == null && StoryKotlin.isAvailable) {
            state.openVariables = state.openVariables - variable.name + typed
            state.change(mergeKey = "variable-name/$index") { renameVariable(variable.name, typed) }
        }
    }
    Problem(wrong)
    VariableType(state, index, variable)
    VariableInitial(state, index, variable)
}

/** The Kotlin type of a variable, which `?` lets stay empty. */
@Composable
private fun VariableType(state: StoryEditorState, index: Int, variable: StoryVariable) {
    TextRow(
        storyLang("variable.type"), variable.type.orEmpty(), id = "st-variable-type-$index",
        completions = StoryInsight.type(state).completions, help = storyLang("variable.type.help"),
    ) { typed ->
        val type = typed.trim().ifEmpty { null }
        state.change(mergeKey = "variable-type/$index") {
            copy(variables = variables.map { if (it.name == variable.name) it.copy(type = type) else it })
        }
    }
}

/** What a variable starts as, in Kotlin. */
@Composable
private fun VariableInitial(state: StoryEditorState, index: Int, variable: StoryVariable) {
    val key = StoryFieldKey(StoryFieldChecks.variableOwner(variable.name), "initial")
    val insight = StoryInsight.field(state, key, variable.initial)
    val write = { typed: String ->
        state.change(mergeKey = "variable/$index") {
            copy(variables = variables.map { if (it.name == variable.name) it.copy(initial = typed) else it })
        }
    }
    TextRow(
        storyLang("variable.initial"), variable.initial, id = "st-variable-$index",
        completions = insight.completions, highlighter = insight.highlighter, diagnostics = insight.diagnostics,
        highlightRevision = insight.highlightRevision, help = storyLang("variable.help"),
        trailing = { OpenCode(state, key, storyLang("variable.initial"), variable.initial, write) },
    ) { write(it) }
    Problems(StoryChecks.variable(variable), insight.diagnostics)
}

/** What can be written in a line: the markup of text, shown in place of a toolbar. */
@Composable
private fun FormatHint() = Text(storyLang("phrase.format"), tags = listOf("story-muted", "wrapped"))

private fun newVariable(graph: StoryGraph): StoryVariable {
    val taken = graph.variables.mapTo(HashSet()) { it.name }
    val name = generateSequence(1) { it + 1 }.map { "value$it" }.first { it !in taken }
    return StoryVariable(name, "0.0", "Double")
}
