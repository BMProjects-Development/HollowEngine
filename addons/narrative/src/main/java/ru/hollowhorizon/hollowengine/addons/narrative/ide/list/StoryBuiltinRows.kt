package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParam
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.TextRow
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton

private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/cross.svg"

/** The fields of a built-in statement; the last one takes the room left. */
@Composable
internal fun BuiltinRow(scope: StoryRowScope, row: StoryRow, builtin: StoryBuiltin, kind: StoryLineKind) {
    val model = scope.model
    val parts = builtin.read(model, kind)
    builtin.fields.forEachIndexed { index, field ->
        val last = index == builtin.fields.lastIndex && kind !is StoryLineKind.Async
        val placeholder = StoryTexts.field(builtin.id, field.name) ?: field.name
        val help = StoryTexts.fieldDescription(builtin.id, field.name)
        val width = if (last) null else 96f
        val write = { typed: String ->
            builtin.with(model, kind, index, typed)?.let { StoryEdits.setStatement(model.text, row.line, it) }
        }
        val slot = field.slot
        if (slot == null) Field(
            scope,
            row,
            "arg-$index",
            parts[index],
            placeholder,
            width = width,
            help = help,
            write = write
        )
        else ValueSlot(
            scope,
            row,
            "arg-$index",
            StoryParam(field.name, slot),
            field.expression(kind),
            parts[index],
            placeholder,
            help,
            width,
            inExpression = true,
            write = write
        )
    }
    if (kind is StoryLineKind.Async) AsyncCommand(scope, row, builtin, kind)
}

/** The command an `@async` runs on its track, with its parameters, or a button to pick one when it opens a block. */
@Composable
private fun AsyncCommand(scope: StoryRowScope, row: StoryRow, builtin: StoryBuiltin, async: StoryLineKind.Async) {
    val model = scope.model
    val readOnly = scope.state.context?.readOnly == true
    val command = { written: String ->
        builtin.with(model, async, 1, written)?.let {
            scope.state.write(StoryEdits.setStatement(model.text, row.line, it), StoryUndo.CALL)
        }
    }
    val inline = async.inline
    if (inline == null) {
        Box(modifier = Modifier.grow(1f))
        if (!readOnly) CommandPicker(row, "async-command", storyLang("async.command")) { command("@$it") }
        return
    }
    val signature = signature(inline)
    Row(
        id = "story-body-${row.key}-async-command",
        tags = listOf("story-call-slot"),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
    ) {
        Image(signature?.icon ?: storyIcon("command"), tags = listOf("story-call-icon"))
        Text(StoryTexts.title(inline.function, signature), tags = listOf("story-call-title"))
        CallSlots(scope, row, "arg-inline", inline, signature?.params.orEmpty()) { param, named, at, value ->
            if (named != null) StoryEdits.setArgument(model.text, row.line, param.name, value)
            else StoryEdits.setPositional(model.text, row.line, at, value)
        }
        if (!readOnly) {
            IconButton(
                RemoveIcon,
                storyLang("slot.remove"),
                id = "story-body-${row.key}-async-remove",
                tags = listOf("inline", "story-action"),
            ) { command("") }
        }
    }
    Box(modifier = Modifier.grow(1f))
}

/** A button that opens the menu of commands and gives the name of the one picked to [pick]. */
@Composable
internal fun CommandPicker(row: StoryRow, name: String, tooltip: String, pick: (String) -> Unit) {
    var menu by remember { mutableStateOf<UiRect?>(null) }
    var anchor by remember { mutableStateOf(UiRect(0f, 0f, 0f, 0f)) }
    IconButton(
        AddIcon,
        tooltip,
        id = "story-body-${row.key}-$name",
        tags = listOf("inline", "story-action"),
        modifier = Modifier.onPlaced { anchor = it },
    ) { menu = anchor }
    menu?.let { bounds ->
        ContextMenu(
            id = "story-$name-menu",
            anchorBounds = bounds,
            items = commandItems(add = pick),
            onExpandedChange = { if (!it) menu = null },
        )
    }
}

/** The inspector of a built-in statement: a row per field, with what it is for. */
@Composable
internal fun BuiltinInspector(
    state: StoryListState,
    model: StoryListModel,
    builtin: StoryBuiltin,
    kind: StoryLineKind,
    line: Int,
) {
    val parts = builtin.read(model, kind)
    builtin.fields.forEachIndexed { index, field ->
        TextRow(
            StoryTexts.field(builtin.id, field.name) ?: field.name,
            parts[index],
            id = "story-inspector-${builtin.id}-$index",
            help = StoryTexts.fieldDescription(builtin.id, field.name),
        ) { typed ->
            builtin.with(model, kind, index, typed)?.let { state.write(StoryEdits.setStatement(model.text, line, it)) }
        }
    }
}
