package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryScriptingAnalyzer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"

/** The fields of a row: what a writer changes most is typed right here, the rest is in the inspector. */
@Composable
internal fun StoryRowContent(scope: StoryRowScope, row: StoryRow) {
    val block = row.block
    when (row.role) {
        StoryRowRole.MENU -> MenuHead(scope, row)
        StoryRowRole.OPTION -> Option(scope, row, block.line.kind as StoryLineKind.Choice)
        StoryRowRole.BRANCH -> Branch(scope, row)
        StoryRowRole.CARD -> when (val kind = block.line.kind) {
            is StoryLineKind.Dialogue -> Phrase(scope, row, kind)
            is StoryLineKind.Label -> Label(scope, row, kind)
            is StoryLineKind.FuncCall -> if (row.kind == StoryCardKind.WAIT) Wait(scope, row, kind) else Command(
                scope,
                row,
                kind
            )

            else -> Statement(scope, row)
        }
    }
}

@Composable
private fun Phrase(scope: StoryRowScope, row: StoryRow, phrase: StoryLineKind.Dialogue) {
    val model = scope.model
    val speaker = model.speaker(phrase).orEmpty()
    val text = model.source(phrase.text.span)
    Field(scope, row, "speaker", speaker, storyLang("narrator"), width = 84f) {
        StoryEdits.setStatement(model.text, row.line, StoryListState.phrase(it, text))
    }
    Field(scope, row, "text", text, storyLang("phrase.placeholder"), onEnter = {
        val next = if (speaker.isBlank()) scope.state.newPhrase(model) else StoryListState.phrase(speaker, "")
        scope.state.insertAfter(model, row.selection, next)
    }) {
        StoryEdits.setStatement(model.text, row.line, StoryListState.phrase(speaker, it))
    }
}

@Composable
private fun Wait(scope: StoryRowScope, row: StoryRow, wait: StoryLineKind.FuncCall) {
    val model = scope.model
    val written = wait.args.firstOrNull()?.let { model.source(it.expr.span) }.orEmpty()
    val seconds = WaitDuration.seconds(written)
    Text(storyLang("kind.wait"), tags = listOf("story-title"))
    if (seconds == null) {
        Field(scope, row, "arg-0", written, "2s", width = 80f) { StoryEdits.setPositional(model.text, row.line, 0, it) }
        Box(modifier = Modifier.grow(1f))
        return
    }
    Field(
        scope,
        row,
        "arg-0",
        WaitDuration.write(seconds).removeSuffix("s"),
        "1",
        width = 54f,
        filter = UiTextInputFilter.DECIMAL
    ) {
        val value = it.toFloatOrNull() ?: return@Field null
        StoryEdits.setPositional(model.text, row.line, 0, WaitDuration.write(value))
    }
    Text(storyLang("wait.unit"), tags = listOf("story-muted"), modifier = Modifier.grow(1f))
}

/** A command with a field per parameter of its signature, or one for everything after its name. */
@Composable
private fun Command(scope: StoryRowScope, row: StoryRow, call: StoryLineKind.FuncCall) {
    val model = scope.model
    Text("@${call.function}", tags = listOf("story-chip"))
    val params = signature(call)
    if (params == null) {
        val rest = StoryListModel.statement(row.block.line).removePrefix("@${call.function}").trim()
        Field(scope, row, "arg-0", rest, "", grow = true) {
            StoryEdits.setStatement(model.text, row.line, StoryStatements.command(call.function, it))
        }
        return
    }
    val positional = call.args.filter { it.name == null }
    var position = 0
    params.forEachIndexed { index, param ->
        val named = call.args.firstOrNull { it.name == param.name }
        val at = position
        if (named == null) position++
        val arg: StoryArg? = named ?: positional.getOrNull(at)
        Field(
            scope,
            row,
            "arg-$index",
            arg?.let { model.source(it.expr.span) }.orEmpty(),
            param.display(),
            width = 96f
        ) {
            if (named != null) StoryEdits.setArgument(model.text, row.line, param.name, it)
            else StoryEdits.setPositional(model.text, row.line, at, it)
        }
    }
    Box(modifier = Modifier.grow(1f))
}

@Composable
private fun Label(scope: StoryRowScope, row: StoryRow, label: StoryLineKind.Label) {
    Text("#", tags = listOf("story-chip"))
    Field(scope, row, "text", label.name, storyLang("label.name"), grow = true) {
        if (it.isBlank()) null else StoryEdits.renameLabel(scope.model.text, label.name, it.trim())
    }
}

@Composable
private fun MenuHead(scope: StoryRowScope, row: StoryRow) {
    val options = scope.model.outline.groupOf(row.block)
    Text(storyLang("kind.choice"), tags = listOf("story-title"))
    Text(storyLang("choice.count").format(options.size), tags = listOf("story-muted"), modifier = Modifier.grow(1f))
    IconButton(
        AddIcon,
        storyLang("choice.add"),
        id = "story-body-${row.key}-add",
        tags = listOf("inline", "story-action")
    ) {
        scope.state.insertAfter(scope.model, StorySelection(options.last().line.index), StoryNewStatements.choice())
    }
}

@Composable
private fun Option(scope: StoryRowScope, row: StoryRow, choice: StoryLineKind.Choice) {
    val model = scope.model
    val number = model.outline.groupOf(row.block).indexOf(row.block) + 1
    Box(tags = listOf("story-badge"), modifier = Modifier.alignItems(UiAlign.CENTER, UiAlign.CENTER)) {
        Text("$number", tags = listOf("story-badge-text"))
    }
    val next = { scope.state.insertAfter(model, row.selection, StoryNewStatements.choice()) }
    Field(
        scope,
        row,
        "option",
        model.source(choice.text.span),
        storyLang("option.placeholder"),
        grow = true,
        onEnter = next
    ) {
        StoryEdits.replace(model.text, choice.text.span, quoted(it))
    }
    val id = choice.args.firstOrNull { it.name == "id" }?.let { model.source(it.expr.span) }.orEmpty()
    Field(scope, row, "id", id, storyLang("option.id"), width = 90f, help = storyLang("option.id.help")) {
        StoryEdits.setArgument(model.text, row.line, "id", it.trim())
    }
}

@Composable
private fun Branch(scope: StoryRowScope, row: StoryRow) {
    val model = scope.model
    val (keyword, condition) = when (val kind = row.block.line.kind) {
        is StoryLineKind.If -> "if" to kind.condition
        is StoryLineKind.ElseIf -> "elseif" to kind.condition
        else -> "else" to null
    }
    Text(storyLang("branch.$keyword"), tags = listOf("story-keyword"))
    if (condition == null) return Box(modifier = Modifier.grow(1f))
    Field(scope, row, "condition", model.source(condition.span), storyLang("branch.placeholder"), grow = true) {
        StoryEdits.replace(model.text, condition.span, it)
    }
}

/** Anything else, as its keyword and the rest of the statement. */
@Composable
private fun Statement(scope: StoryRowScope, row: StoryRow) {
    val statement = StoryListModel.statement(row.block.line)
    val keyword = statement.substringBefore(' ').takeIf { it.startsWith("@") && row.kind != StoryCardKind.BROKEN }
    if (keyword != null) Text(keyword, tags = listOf("story-chip"))
    val rest = if (keyword != null) statement.removePrefix(keyword).trim() else statement
    Field(scope, row, "arg-0", rest, "", grow = true) {
        StoryEdits.setStatement(scope.model.text, row.line, if (keyword != null) "$keyword $it".trimEnd() else it)
    }
}

/**
 * A field of a row that writes what is typed into the file. [write] turns the typed text into the whole new text,
 * or null to leave the file as it is.
 */
@Composable
private fun Field(
    scope: StoryRowScope,
    row: StoryRow,
    name: String,
    value: String,
    placeholder: String,
    width: Float? = null,
    grow: Boolean = width == null,
    filter: UiTextInputFilter = UiTextInputFilter.ANY,
    help: String? = null,
    onEnter: (() -> Unit)? = null,
    write: (String) -> String?,
) {
    val size = if (width != null) Modifier.size(width.px, 20.px) else Modifier.size(0.px, 20.px)
    TextField(
        value = value,
        onChange = { typed -> write(typed)?.let(scope.state::write) },
        filter = filter,
        readOnly = scope.state.context?.readOnly == true,
        placeholder = placeholder,
        id = StoryListState.fieldId(row.line, name),
        tags = listOf("story-field", name),
        modifier = (if (grow) size.grow(1f) else size).onKeyInput { input ->
            if (fieldKey(
                    scope,
                    row,
                    input,
                    onEnter
                )
            ) input.consume()
        }.let { field -> help?.let { field.tooltipOnHover(it) } ?: field },
    )
}

/** What a field does with the list's keys: Enter adds the next statement, Escape gives the keys back to the list. */
private fun fieldKey(scope: StoryRowScope, row: StoryRow, input: UiKeyInput, onEnter: (() -> Unit)?): Boolean {
    val state = scope.state
    return when {
        StoryListKeys.isFind(input) -> {
            state.search = state.search.orEmpty()
            state.focus = StoryListKeys.SearchField
            true
        }

        input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER -> {
            onEnter?.invoke() ?: state.insertAfter(scope.model, row.selection, state.newPhrase(scope.model))
            true
        }

        input.key == GLFW.GLFW_KEY_ESCAPE -> {
            state.select(row.selection)
            state.focus = StoryListKeys.ListField
            true
        }

        else -> false
    }
}

/** The signature of [call] that fits what is written, or null when the function is not known. */
private fun signature(call: StoryLineKind.FuncCall): List<StoryParam>? {
    val overloads = StoryScriptingAnalyzer.catalog().overloads(call.function)?.takeIf { it.isNotEmpty() } ?: return null
    val written = call.args.size
    return (overloads.firstOrNull { it.params.size >= written } ?: overloads.maxBy { it.params.size }).params
}

/** Text inside the quotes of an option: a quote typed into it is escaped so the quotes still close where they did. */
internal fun quoted(text: String): String = buildString {
    var escaped = false
    for (char in StoryStatements.lineText(text)) {
        if (char == '"' && !escaped) append('\\')
        append(char)
        escaped = char == '\\' && !escaped
    }
}
