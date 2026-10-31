package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySelection
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyIcon
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.DisclosureArrow
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import kotlin.math.hypot

private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"

/** How far the pointer goes before a press on a row becomes a drag. */
private const val DragThreshold = 4f

/** The width of the rail line. */
private const val LineWidth = 2f

/** How many required parameters a card shows; the rest are in the inspector. */
private const val RowParams = 3

/** What every row of one composition needs. */
internal class StoryRowScope(
    val state: StoryListState,
    val graph: StoryGraph,
    val rows: List<StoryRow>,
    val matches: Set<String>,
    val current: StorySelection?,
)

/** One row of the list: the rail with the dot of a top-level card, a guide for each card it is nested in, and the row. */
@Composable
internal fun StoryRowView(scope: StoryRowScope, row: StoryRow, index: Int) {
    val state = scope.state
    val drag = state.drag
    val held = drag?.holds(index) == true
    val offset = drag?.offsetOf(index)
    val motion = Modifier.let { if (offset != null) it.translate(y = offset) else it }
        .let { if (held || state.settling) it.transition() else it }
    Row(
        id = "story-row-${row.key}",
        tags = listOfNotNull("story-row", "dragging".takeIf { held }, "rejected".takeIf { held && drag.rejected }),
        modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.STRETCH).layer(if (held) 10 else 0),
    ) {
        Box(
            mode = UiBoxMode.STACK,
            tags = listOf("story-rail"),
            modifier = Modifier.alignItems(horizontal = UiAlign.CENTER)
        ) {
            Box(tags = listOf("story-rail-line"), modifier = Modifier.size(LineWidth.px, 100.percent))
            if (row.guides.isEmpty()) Box(
                id = "story-dot-${row.key}",
                tags = listOfNotNull("story-rail-dot", row.tag, "selected".takeIf { row.selection == state.selected }),
                modifier = motion,
            )
        }
        row.guides.forEachIndexed { level, kind ->
            Box(tags = listOf("story-guide", kind.tag, if (level == row.guides.lastIndex) "branch" else "line"))
        }
        RowBody(scope, row, motion)
    }
}

@Composable
private fun RowBody(scope: StoryRowScope, row: StoryRow, motion: Modifier) {
    val state = scope.state
    Row(
        id = "story-body-${row.key}",
        tags = listOfNotNull(
            "story-body",
            row.tag,
            row.role.name.lowercase(),
            "selected".takeIf { row.selection == state.selected },
            "match".takeIf { row.node.id in scope.matches },
            "current".takeIf { row.selection == scope.current },
        ),
        modifier = motion.size(0.px).grow(1f).alignItems(vertical = UiAlign.STRETCH)
            .input(hoverable = true, clickable = true, draggable = true).onPress { event ->
                state.drag = null
                state.select(row.selection)
                if (event.isRightClick()) state.menu = StoryRowMenu(row, event.x, event.y)
            }.onDrag { event ->
                if (event.isRightClick()) return@onDrag
                val drag = state.drag ?: run {
                    if (hypot(event.dragTotalX, event.dragTotalY) < DragThreshold) return@onDrag
                    StoryRowDrag.of(scope.rows, row, state.list.averageItemSize)?.also { state.drag = it }
                        ?: return@onDrag
                }
                event.consume()
                drag.moveTo(scope.rows, event.dragTotalY) { state.canDrop(scope.graph, row, it) }
            }.onRelease { event ->
                if (state.drag == null) return@onRelease
                event.consume()
                state.drop()
            },
    ) {
        Head(row)
        Row(
            tags = listOf("story-body-content"),
            modifier = Modifier.size(0.px).grow(1f).alignItems(vertical = UiAlign.CENTER)
        ) {
            RowContent(scope, row)
            Fold(scope, row)
            More(state, row)
            IconButton(
                RemoveIcon,
                storyLang("remove"),
                id = "story-body-${row.key}-remove",
                tags = listOf("inline", "danger", "story-action"),
            ) { state.remove(row) }
        }
    }
}

/** The band at the start of a row that says what it is. An option starts with its number instead. */
@Composable
private fun Head(row: StoryRow) {
    val call = (row.node as? StoryNode.Call)?.takeIf { row.role == StoryRowRole.CARD }
    val signature = call?.let(StoryCatalog::signatureOf)
    val title = when {
        row.role == StoryRowRole.PIN && row.node is StoryNode.Choose -> return
        row.role == StoryRowRole.PIN -> branchPinTitle(row.pin.orEmpty())
        call != null -> signature?.let(StoryCatalog::title) ?: call.function.substringAfterLast('.')
        else -> row.kind.title
    }
    Row(
        tags = listOf("story-head", row.tag),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER).let { modifier ->
            signature?.let(StoryCatalog::description)?.let { modifier.tooltipOnHover(it) } ?: modifier
        },
    ) {
        Image(signature?.icon ?: row.kind.icon, tags = listOf("story-head-icon"))
        Text(title, tags = listOf("story-head-title"))
    }
}

/** The fields of a row: what a writer changes most is typed right here, the rest is in the inspector. */
@Composable
private fun RowContent(scope: StoryRowScope, row: StoryRow) {
    val state = scope.state
    when (row.role) {
        StoryRowRole.PIN -> PinRow(scope, row)
        StoryRowRole.JUMP -> Text(
            storyLang("jump.to").format(StoryTargets.title(row.node)),
            tags = listOf("story-muted"),
            modifier = Modifier.grow(1f)
        )

        StoryRowRole.START, StoryRowRole.CARD -> when (val node = row.node) {
            is StoryNode.Start -> Field(scope, node.id, "name", node.name, storyLang("start.name")) { value ->
                state.editNode(node.id, "name") { node.copy(name = value.trim()) }
            }

            is StoryNode.Say -> {
                Field(scope, node.id, "speaker", node.speaker, storyLang("narrator"), width = 84f) { value ->
                    state.editNode(node.id, "speaker") { (it as StoryNode.Say).copy(speaker = value) }
                }
                Field(scope, node.id, "text", node.text, storyLang("phrase.placeholder")) { value ->
                    state.editNode(node.id, "text") { (it as StoryNode.Say).copy(text = value) }
                }
            }

            is StoryNode.Call -> Command(scope, node)
            is StoryNode.Choose -> {
                Text(
                    storyLang("choice.count").format(node.options.size),
                    tags = listOf("story-muted"),
                    modifier = Modifier.grow(1f)
                )
                IconButton(
                    AddIcon,
                    storyLang("choice.add"),
                    id = "story-body-${row.key}-add",
                    tags = listOf("inline", "story-action")
                ) {
                    state.addOption(node)
                }
            }

            is StoryNode.Branch -> Field(
                scope, node.id, "condition", node.condition, storyLang("branch.placeholder")
            ) { value ->
                state.editNode(node.id, "condition") { (it as StoryNode.Branch).copy(condition = value) }
            }

            is StoryNode.Assign -> {
                Field(scope, node.id, "variable", node.variable, storyLang("set.variable"), width = 84f) { value ->
                    state.editNode(node.id, "variable") { (it as StoryNode.Assign).copy(variable = value.trim()) }
                }
                Field(scope, node.id, "value", node.value, storyLang("set.value")) { value ->
                    state.editNode(node.id, "value") { (it as StoryNode.Assign).copy(value = value) }
                }
            }

            is StoryNode.Code -> Text(
                node.code.lineSequence().first(), tags = listOf("story-muted"), modifier = Modifier.grow(1f)
            )

            is StoryNode.Expression -> Text(node.code, tags = listOf("story-muted"), modifier = Modifier.grow(1f))
        }
    }
}

/** The required parameters of a call, or its first ones when it needs none. */
@Composable
private fun Command(scope: StoryRowScope, call: StoryNode.Call) {
    val signature = StoryCatalog.signatureOf(call)
    val params = signature?.params.orEmpty().filter { !it.optional }.take(RowParams)
    params.forEach { param ->
        val shown = StoryLiterals.text(call.argument(param).orEmpty(), param.type)
        Field(scope, call.id, param.name, shown, StoryCatalog.paramTitle(signature!!, param), width = 120f) { value ->
            val written = StoryLiterals.expression(value, param.type)
            scope.state.editNode(call.id, param.name) { (it as StoryNode.Call).with(param, written) }
        }
    }
    Box(modifier = Modifier.grow(1f))
}

@Composable
private fun PinRow(scope: StoryRowScope, row: StoryRow) {
    val menu = row.node as? StoryNode.Choose ?: return Box(modifier = Modifier.grow(1f))
    val index = StoryPins.optionIndex(row.pin.orEmpty()) ?: return
    val option = menu.options.getOrNull(index) ?: return
    Box(tags = listOf("story-badge"), modifier = Modifier.alignItems(UiAlign.CENTER, UiAlign.CENTER)) {
        Text("${index + 1}", tags = listOf("story-badge-text"))
    }
    Field(scope, menu.id, "option-$index", option.text, storyLang("option.placeholder")) { value ->
        scope.state.editNode(menu.id, "option-$index") { node ->
            node as StoryNode.Choose
            node.copy(options = node.options.mapIndexed { at, it -> if (at == index) it.copy(text = value) else it })
        }
    }
}

/** The arrow that folds what is under a row; a row with nothing under it has none. */
@Composable
private fun Fold(scope: StoryRowScope, row: StoryRow) {
    val key = when {
        row.role == StoryRowRole.PIN -> "${row.node.id}/${row.pin}"
        row.role == StoryRowRole.CARD && row.kind in FOLDING -> row.node.id
        else -> return
    }
    val expanded = !scope.state.isFolded(key)
    Box(
        id = "story-body-${row.key}-fold",
        tags = listOf("story-fold"),
        modifier = Modifier.alignItems(UiAlign.CENTER, UiAlign.CENTER).input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND).onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) scope.state.toggleFold(key)
                event.consume()
            },
    ) { DisclosureArrow(expanded) }
}

private val FOLDING = setOf(StoryCardKind.CHOICE, StoryCardKind.CONDITION, StoryCardKind.BLOCK)

/** The button that opens the menu of a row, the same as a right click on it. */
@Composable
private fun More(state: StoryListState, row: StoryRow) {
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    IconButton(
        storyIcon("more"),
        storyLang("more"),
        id = "story-body-${row.key}-more",
        tags = listOf("inline", "story-action"),
        modifier = Modifier.onPlaced { anchor = it },
    ) {
        state.select(row.selection)
        state.menu = StoryRowMenu(row, anchor.x, anchor.y + anchor.height)
    }
}

/** A field of a row. Every letter typed goes into the file, and the file's history undoes it. */
@Composable
internal fun Field(
    scope: StoryRowScope,
    node: String,
    name: String,
    value: String,
    placeholder: String,
    width: Float? = null,
    write: (String) -> Unit,
) {
    val size = if (width != null) Modifier.size(width.px, 20.px) else Modifier.size(0.px, 20.px).grow(1f)
    TextField(
        value = value,
        onChange = write,
        readOnly = scope.state.context?.readOnly == true,
        placeholder = placeholder,
        id = StoryListState.fieldId(node, name),
        tags = listOf("story-field", name.substringBefore('-')),
        modifier = size.onKeyInput { input -> if (fieldKey(scope, node, input)) input.consume() },
    )
}

/** What a field does with the list's keys: Enter adds a line, Escape gives the keys back to the list. */
private fun fieldKey(scope: StoryRowScope, node: String, input: UiKeyInput): Boolean {
    val state = scope.state
    return when {
        StoryListKeys.isFind(input) -> {
            state.search = state.search.orEmpty()
            state.focus = StoryListKeys.SearchField
            true
        }

        input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER -> {
            state.addPhrase(scope.graph)
            true
        }

        // A field writes the file as it is typed into, so its undo is the file's, not a copy of its own.
        HollowKeys.UNDO.matches(input) -> state.context?.history?.undo().let { true }
        HollowKeys.REDO.matches(input) -> state.context?.history?.redo().let { true }

        input.key == GLFW.GLFW_KEY_ESCAPE -> {
            state.select(StorySelection(node))
            state.focus = StoryListKeys.ListField
            true
        }

        else -> false
    }
}

/** How a jump names where it leads. */
internal object StoryTargets {
    fun title(node: StoryNode): String = when (node) {
        is StoryNode.Start -> node.name
        is StoryNode.Say -> node.text.ifBlank { node.speaker }
        is StoryNode.Call -> node.function.substringAfterLast('.')
        else -> StoryCardKind.of(node).title
    }
}
