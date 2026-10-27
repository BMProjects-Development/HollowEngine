package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryBlock
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.inspector.*

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"

/**
 * The inspector of what is selected in the list. Its fields read the file each time they are drawn, so they show
 * what the text says now, and every change is written back into the text.
 */
internal fun storyCardInspector(state: StoryListState, selection: StorySelection, kind: StoryCardKind) =
    InspectorTarget(
        id = "story-card-${selection.line}-${selection.menu}",
        title = kind.title,
        subtitle = storyLang("line").format(selection.line + 1),
        styles = listOf(StoryListStylesheet),
        content = { CardInspector(state, selection) },
    )

@Composable
private fun CardInspector(state: StoryListState, selection: StorySelection) {
    val model = state.read() ?: return
    val line = selection.line
    val block = model.outline.blockAt(line) ?: return
    when (val kind = block.line.kind) {
        is StoryLineKind.Dialogue -> PhraseInspector(state, model, kind, line)
        is StoryLineKind.Choice -> if (selection.menu) MenuInspector(
            state,
            model,
            model.outline.groupOf(block)
        ) else OptionInspector(state, model, kind, line)

        is StoryLineKind.If, is StoryLineKind.ElseIf, StoryLineKind.Else -> ConditionInspector(
            state,
            model,
            model.outline.groupOf(block)
        )

        is StoryLineKind.Label -> TextRow(storyLang("label.name"), kind.name, id = "story-inspector-label") { name ->
            if (name.isNotBlank()) state.write(StoryEdits.renameLabel(model.text, kind.name, name.trim()))
        }

        is StoryLineKind.FuncCall -> StoryCommandWidgets.of(kind)?.inspector?.invoke(state, model, kind, line)
            ?: StatementRow(state, model, line)

        else -> StatementRow(state, model, line)
    }
    model.problems[line]?.forEach { Text(it.message, tags = listOf("story-inspector-problem")) }
}

@Composable
private fun PhraseInspector(state: StoryListState, model: StoryListModel, phrase: StoryLineKind.Dialogue, line: Int) {
    val speaker = model.speaker(phrase).orEmpty()
    val text = model.source(phrase.text.span)
    TextRow(
        storyLang("phrase.speaker"),
        speaker,
        id = "story-inspector-speaker",
        help = storyLang("phrase.speaker.help")
    ) {
        state.write(StoryEdits.setStatement(model.text, line, StoryListState.phrase(it, text)))
    }
    TextRow(storyLang("phrase.text"), text, id = "story-inspector-text", help = storyLang("phrase.text.help")) {
        state.write(StoryEdits.setStatement(model.text, line, StoryListState.phrase(speaker, it)))
    }
}

@Composable
private fun MenuInspector(state: StoryListState, model: StoryListModel, options: List<StoryBlock>) {
    Section(storyLang("choice.options")) {
        options.forEachIndexed { index, option ->
            val choice = option.line.kind as StoryLineKind.Choice
            TextRow("${index + 1}", model.source(choice.text.span), id = "story-inspector-option-$index") {
                state.write(StoryEdits.replace(model.text, choice.text.span, quoted(it)))
            }
        }
        InspectorButton(storyLang("choice.add"), AddIcon) {
            state.insertAfter(model, StorySelection(options.last().line.index), StoryNewStatements.choice())
        }
    }
}

@Composable
private fun OptionInspector(state: StoryListState, model: StoryListModel, choice: StoryLineKind.Choice, line: Int) {
    fun argument(name: String) =
        choice.args.firstOrNull { it.name == name }?.let { model.source(it.expr.span) }.orEmpty()
    TextRow(storyLang("option.text"), model.source(choice.text.span), id = "story-inspector-option") {
        state.write(StoryEdits.replace(model.text, choice.text.span, quoted(it)))
    }
    TextRow(
        storyLang("option.id"),
        argument("id"),
        id = "story-inspector-option-id",
        help = storyLang("option.id.help")
    ) {
        state.write(StoryEdits.setArgument(model.text, line, "id", it.trim()))
    }
    TextRow(
        storyLang("option.condition"),
        argument("if"),
        id = "story-inspector-option-if",
        help = storyLang("option.condition.help")
    ) {
        state.write(StoryEdits.setArgument(model.text, line, "if", it))
    }
}

@Composable
private fun ConditionInspector(state: StoryListState, model: StoryListModel, branches: List<StoryBlock>) {
    branches.forEachIndexed { index, branch ->
        val condition = when (val kind = branch.line.kind) {
            is StoryLineKind.If -> kind.condition
            is StoryLineKind.ElseIf -> kind.condition
            else -> null
        } ?: return@forEachIndexed
        val keyword = if (index == 0) "if" else "elseif"
        TextRow(storyLang("branch.$keyword"), model.source(condition.span), id = "story-inspector-branch-$index") {
            state.write(StoryEdits.replace(model.text, condition.span, it))
        }
    }
}

/** The statement as written, for what has no fields of its own. */
@Composable
internal fun StatementRow(state: StoryListState, model: StoryListModel, line: Int) {
    val statement = StoryListModel.statement(model.outline.cst.lines[line])
    TextRow(storyLang("statement"), statement, id = "story-inspector-statement") {
        state.write(StoryEdits.setStatement(model.text, line, it))
    }
}
