package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryArg
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParam
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val CallIcon = "hollowengine:textures/gui/icons/link.svg"
private const val RemoveIcon = "hollowengine:textures/gui/icons/cross.svg"

/**
 * The fields of [call], one slot per parameter of [params], bound the way the compiler binds them: by name, or by
 * position among the unnamed arguments.
 *
 * @param name the prefix of the slots' field names, unique within the row.
 * @param write turns the new value of one argument into the new text of the file, or null to leave it.
 */
@Composable
internal fun CallSlots(
    scope: StoryRowScope,
    row: StoryRow,
    name: String,
    call: StoryLineKind.FuncCall,
    params: List<StoryParam>,
    write: (StoryParam, StoryArg?, Int, String) -> String?,
) {
    val positional = call.args.filter { it.name == null }
    var position = 0
    params.forEachIndexed { index, param ->
        val named = call.args.firstOrNull { it.name == param.name }
        val at = position
        if (named == null) position++
        val arg = named ?: positional.getOrNull(at)
        ParamSlot(scope, row, "$name-$index", param, arg) { write(param, named, at, it) }
    }
}

/**
 * One parameter of a command: a field, or the block of the call written in it, such as `npc([0, 64, 0])`. A field
 * offers the calls whose value fits the parameter.
 */
@Composable
private fun ParamSlot(
    scope: StoryRowScope,
    row: StoryRow,
    name: String,
    param: StoryParam,
    arg: StoryArg?,
    write: (String) -> String?,
) {
    val nested = arg?.expr?.wholeCall()
    if (nested != null) return CallBlock(scope, row, name, nested.call, write)

    Field(
        scope,
        row,
        name,
        arg?.let { scope.model.source(it.expr.span) }.orEmpty(),
        param.title?.lang ?: param.display(),
        width = 96f,
        help = param.description?.lang,
        write = write,
    )
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
        ContextMenu(
            id = "story-slot-calls",
            anchorBounds = bounds,
            items = commandItems({ it?.returns?.let(param.type::takes) == true }) { function ->
                write("${function.replace('-', '_')}()")?.let(scope.state::write)
            },
            onExpandedChange = { if (!it) menu = null },
        )
    }
}

/** A call inside a parameter, as a block with its own slots; [write] puts the rewritten call into the parameter. */
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
        Text(signature?.title?.lang ?: call.function, tags = listOf("story-call-title"))
        val params = signature?.params.orEmpty()
        CallSlots(scope, row, name, call, params) { param, _, _, value ->
            write(StoryEdits.setCallArgument(scope.model.text, call, params.map { it.name }, param.name, value))
        }
        if (scope.state.context?.readOnly != true) {
            IconButton(
                RemoveIcon,
                storyLang("slot.remove"),
                id = "story-body-${row.key}-$name-remove",
                tags = listOf("inline", "story-action"),
            ) { write("")?.let(scope.state::write) }
        }
    }
}
