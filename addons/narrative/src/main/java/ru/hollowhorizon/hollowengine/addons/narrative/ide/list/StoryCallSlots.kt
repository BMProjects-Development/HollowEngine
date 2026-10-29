package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryArg
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryExpression
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParam
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem

private const val CallIcon = "hollowengine:textures/gui/icons/link.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/cross.svg"

/**
 * The fields of [call], one slot per parameter of [params], bound the way the compiler binds them: by name, or by
 * position among the unnamed arguments.
 *
 * @param name the prefix of the slots' field names, unique within the row.
 * @param inExpression whether the call is inside an expression, where a name is a variable rather than a word.
 * @param write turns the new value of one argument into the new text of the file, or null to leave it.
 */
@Composable
internal fun CallSlots(
    scope: StoryRowScope,
    row: StoryRow,
    name: String,
    call: StoryLineKind.FuncCall,
    params: List<StoryParam>,
    inExpression: Boolean = false,
    write: (StoryParam, StoryArg?, Int, String) -> String?,
) {
    val command = signature(call)?.name ?: call.function
    val positional = call.args.filter { it.name == null }
    var position = 0
    params.forEachIndexed { index, param ->
        val named = call.args.firstOrNull { it.name == param.name }
        val at = position
        if (named == null) position++
        val arg = named ?: positional.getOrNull(at)
        ValueSlot(
            scope,
            row,
            "$name-$index",
            param,
            arg?.expr,
            arg?.let { scope.model.source(it.expr.span) }.orEmpty(),
            StoryTexts.paramTitle(command, param),
            StoryTexts.paramHelp(command, param),
            inExpression = inExpression,
        ) { write(param, named, at, it) }
    }
}

/**
 * A value of a card: a field, or the block of the call written in it, such as `npc([0, 64, 0])`. A field offers the
 * variables and the calls whose value fits [param].
 *
 * @param expression what is written now, read for the call it may be.
 * @param value what the field shows.
 * @param width the width of the field; it takes the room left when null.
 * @param inExpression whether the value is an expression; a variable is written in braces into a command's argument,
 * where a bare word is text.
 * @param write turns the new value into the new text of the file, or null to leave it.
 */
@Composable
internal fun ValueSlot(
    scope: StoryRowScope,
    row: StoryRow,
    name: String,
    param: StoryParam,
    expression: StoryExpression?,
    value: String,
    placeholder: String,
    help: String?,
    width: Float? = 96f,
    inExpression: Boolean = false,
    write: (String) -> String?,
) {
    val nested = expression?.wholeCall()?.takeIf { signature(it.call) != null }
    if (nested != null) return CallBlock(scope, row, name, nested.call, write)

    Field(scope, row, name, value, placeholder, width = width, help = help, write = write)
    if (scope.state.context?.readOnly == true) return
    var menu by remember { mutableStateOf<UiRect?>(null) }
    var anchor by remember { mutableStateOf(UiRect(0f, 0f, 0f, 0f)) }
    IconButton(
        CallIcon,
        storyLang("slot.call"),
        id = "story-body-${row.key}-$name-call",
        tags = listOf("inline", "story-action", "story-slot-call"),
        modifier = Modifier.onPlaced { anchor = it },
    ) { menu = anchor }
    menu?.let { bounds ->
        val fill: (String) -> Unit = { written -> write(written)?.let { scope.state.write(it, StoryUndo.CALL) } }
        ContextMenu(
            id = "story-slot-calls",
            anchorBounds = bounds,
            items = slotItems(scope.model, param, inExpression, fill),
            onExpandedChange = { if (!it) menu = null },
        )
    }
}

/** What fills a slot for [param]: a variable or character of the story, or a call of a command that gives such a value. */
private fun slotItems(model: StoryListModel, param: StoryParam, inExpression: Boolean, fill: (String) -> Unit): List<UiDropdownItem> {
    val variables = model.variables.filter { (_, known) -> known == null || param.type.takes(known) }.keys.sorted()
    val calls = commandItems({ it?.fills(param) == true }) { function -> fill("${function.replace('-', '_')}()") }
    if (variables.isEmpty() && calls.isEmpty()) return listOf(UiDropdownItem(storyLang("slot.nothing"), enabled = false))
    val names = variables.map { variable -> UiDropdownItem(variable) { fill(if (inExpression) variable else "{$variable}") } }
    return if (calls.isEmpty()) names
    else names + calls.mapIndexed { index, item -> if (index == 0 && names.isNotEmpty()) item.copy(separatorBefore = true) else item }
}

/** A call inside a value, as a block with its own slots; [write] puts the rewritten call into the value. */
@Composable
private fun CallBlock(
    scope: StoryRowScope,
    row: StoryRow,
    name: String,
    call: StoryLineKind.FuncCall,
    write: (String) -> String?,
) {
    val signature = signature(call)
    Row(
        id = "story-body-${row.key}-$name",
        tags = listOf("story-call-slot"),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER)
    ) {
        Image(signature?.icon ?: storyIcon("command"), tags = listOf("story-call-icon"))
        Text(StoryTexts.title(call.function, signature), tags = listOf("story-call-title"))
        val params = signature?.params.orEmpty()
        CallSlots(scope, row, name, call, params, inExpression = true) { param, _, _, value ->
            write(StoryEdits.setCallArgument(scope.model.text, call, params.map { it.name }, param.name, value))
        }
        if (scope.state.context?.readOnly != true) {
            IconButton(
                RemoveIcon,
                storyLang("slot.remove"),
                id = "story-body-${row.key}-$name-remove",
                tags = listOf("inline", "story-action"),
            ) { write("")?.let { scope.state.write(it, StoryUndo.CALL) } }
        }
    }
}
