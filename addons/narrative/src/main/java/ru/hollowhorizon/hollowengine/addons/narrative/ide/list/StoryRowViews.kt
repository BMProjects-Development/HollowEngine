package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryChecks
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySelection
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryVectorField
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyIcon
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.AssetPickerButton
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

/** What every row of one composition needs. */
internal class StoryRowScope(
    val state: StoryListState,
    val graph: StoryGraph,
    val rows: List<StoryRow>,
    val matches: Set<String>,
    val current: StorySelection?,
    val rowWidth: Float,
)

/** How wide a row has to be for the row at [row] to fit what it holds, past which the list scrolls sideways. */
internal fun rowFit(row: StoryRow): Float = RailWidth + row.guides.size * GuideWidth + maxOf(BodyWidth, commandFit(row))

/** How wide the body of a card has to be for the fields of its command. */
private fun commandFit(row: StoryRow): Float {
    val signature = (row.node as? StoryNode.Call)?.takeIf { row.role == StoryRowRole.CARD }?.let(StoryCatalog::signatureOf) ?: return 0f
    return CommandChrome + rowParams(signature).sumOf {
        (if (StoryLiterals.vectorHelper(it.type) != null) VectorField else CommandField).toDouble()
    }.toFloat()
}

private const val RailWidth = 24f
private const val GuideWidth = 14f
private const val BodyWidth = 380f
private const val CommandField = 120f
private const val VectorField = 190f

/** What a card of a command spends on its title and buttons. */
private const val CommandChrome = 210f

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
        modifier = Modifier.size(scope.rowWidth.px).alignItems(vertical = UiAlign.STRETCH).layer(if (held) 10 else 0),
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
                if (event.isRightClick() && row.role != StoryRowRole.PARAM) state.menu = StoryRowMenu(row, event.x, event.y)
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
            if (row.role != StoryRowRole.PARAM) {
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
}

/** The band at the start of a row that says what it is. An option starts with its number instead. */
@Composable
private fun Head(row: StoryRow) {
    val call = (row.node as? StoryNode.Call)?.takeIf { row.role == StoryRowRole.CARD }
    val signature = call?.let(StoryCatalog::signatureOf)
    val param = signature ?: (row.node as? StoryNode.Call)?.takeIf { row.role == StoryRowRole.PARAM }?.let(StoryCatalog::signatureOf)
    val title = when {
        row.role == StoryRowRole.PARAM -> param?.params?.firstOrNull { it.name == row.pin }?.let { StoryCatalog.paramTitle(param, it) } ?: row.pin.orEmpty()
        row.role == StoryRowRole.PIN && row.node is StoryNode.Choose -> return
        row.role == StoryRowRole.PIN -> branchPinTitle(row.pin.orEmpty())
        call != null -> signature?.let(StoryCatalog::title) ?: call.function.substringAfterLast('.')
        else -> row.kind.title
    }
    Row(
        tags = listOf("story-head", row.tag),
        modifier = Modifier.alignItems(vertical = UiAlign.CENTER)
            .let { modifier -> signature?.let(StoryCatalog::color)?.let { modifier.background(it) } ?: modifier }
            .let { modifier -> signature?.let(StoryCatalog::description)?.let { modifier.tooltipOnHover(it) } ?: modifier },
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
        StoryRowRole.PARAM -> {
            val call = row.node as StoryNode.Call
            val signature = StoryCatalog.signatureOf(call)
            signature?.params?.firstOrNull { it.name == row.pin }?.let { ParamField(scope, call, signature, it, null) }
        }

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
                if (scope.graph.links.any { it.to == node.id && it.toPin == StoryPins.TEXT }) {
                    Text(storyLang("param.linked"), tags = listOf("story-muted"), modifier = Modifier.grow(1f))
                } else Field(scope, node.id, "text", node.text, storyLang("phrase.placeholder")) { value ->
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
            is StoryNode.Loop -> Field(scope, node.id, "items", node.items, storyLang("loop.items")) { value ->
                state.editNode(node.id, "items") { (it as StoryNode.Loop).copy(items = value) }
            }

            is StoryNode.Pure, is StoryNode.Reroute -> Unit
        }
    }
}

/** The first parameters of a call, those it needs ahead of the rest. */
@Composable
private fun Command(scope: StoryRowScope, call: StoryNode.Call) {
    val signature = StoryCatalog.signatureOf(call) ?: return Box(modifier = Modifier.grow(1f))
    rowParams(signature).forEach { ParamField(scope, call, signature, it, CommandField) }
    Box(modifier = Modifier.grow(1f))
}

/** The field of [param], or a note that its value comes from a link. */
@Composable
private fun ParamField(scope: StoryRowScope, call: StoryNode.Call, signature: StoryFunctionSignature, param: StoryParam, width: Float?) {
    val state = scope.state
    if (scope.graph.links.any { it.to == call.id && it.toPin == param.name }) {
        return Text(storyLang("param.linked"), tags = listOf("story-muted"))
    }
    val written = call.argument(param).orEmpty()
    val problem = StoryChecks.param(param, written)
    val title = StoryCatalog.paramTitle(signature, param)
    fun write(next: String) = state.editNode(call.id, param.name) { (it as StoryNode.Call).with(param, next) }
    val helper = StoryLiterals.vectorHelper(param.type)
    if (helper != null) return StoryVectorField(
        StoryListState.fieldId(call.id, param.name), written, helper, state.context?.readOnly == true, problem,
        listOf("story-field"), Modifier.size((width ?: VectorField).px, 20.px), write = ::write,
    )
    Field(scope, call.id, param.name, StoryLiterals.text(written, param.type), title, width, problem) { value ->
        write(StoryLiterals.expression(value, param.type))
    }
    if (param.assets.isNotEmpty()) {
        AssetPickerButton(title, StoryLiterals.text(written, param.type), { it.assets(param.assets) }) { picked ->
            write(StoryLiterals.expression(picked, param.type))
        }
    }
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
        row.role == StoryRowRole.CARD && moreParams(row.node).isNotEmpty() -> paramsKey(row.node.id)
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
    problem: String? = null,
    write: (String) -> Unit,
) {
    val size = if (width != null) Modifier.size(width.px, 20.px) else Modifier.size(0.px, 20.px).grow(1f)
    TextField(
        value = value,
        onChange = write,
        readOnly = scope.state.context?.readOnly == true,
        placeholder = placeholder,
        id = StoryListState.fieldId(node, name),
        tags = listOfNotNull("story-field", name.substringBefore('-'), "error".takeIf { problem != null }),
        modifier = size.tooltipOnHover(problem.orEmpty()).onKeyInput { input -> if (fieldKey(scope, node, input)) input.consume() },
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
