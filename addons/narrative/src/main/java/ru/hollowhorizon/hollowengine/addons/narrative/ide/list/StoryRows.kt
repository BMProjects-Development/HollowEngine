package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryStatements
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.DisclosureArrow
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import kotlin.math.hypot

private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val ProblemIcon = "hollowengine:textures/gui/icons/failure.svg"

/** How far the pointer goes before a press on a row becomes a drag. */
private const val DragThreshold = 4f

/** The width of the rail line. */
private const val LineWidth = 2f

/** What every row of one composition needs. */
internal class StoryRowScope(
    val state: StoryListState,
    val model: StoryListModel,
    val rows: List<StoryRow>,
    val matches: Set<Int>,
    val current: StorySelection?,
)

/**
 * One row of the list: the rail with the dot of a top-level card, a guide for each card it is nested in, and the
 * row itself. While a row is dragged, it follows the pointer and the others slide out of its way.
 */
@Composable
internal fun StoryRowView(scope: StoryRowScope, row: StoryRow, index: Int) {
    val state = scope.state
    val drag = state.drag
    val held = drag?.holds(index) == true
    val offset = drag?.offsetOf(index)
    Row(
        id = "story-row-${row.key}",
        tags = listOfNotNull("story-row", "dragging".takeIf { held }, "rejected".takeIf { held && drag.rejected }),
        modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.STRETCH)
            .let { if (offset != null) it.translate(y = offset) else it }
            .let { if (held || state.settling) it.transition() else it }.layer(if (held) 10 else 0),
    ) {
        Box(
            mode = UiBoxMode.STACK,
            tags = listOf("story-rail"),
            modifier = Modifier.alignItems(horizontal = UiAlign.CENTER)
        ) {
            Box(tags = listOf("story-rail-line"), modifier = Modifier.size(LineWidth.px, 100.percent))
            if (row.dot) Box(
                tags = listOfNotNull(
                    "story-rail-dot",
                    row.kind.tag,
                    "selected".takeIf { isSelected(scope, row) })
            )
        }
        row.guides.forEachIndexed { level, kind ->
            Box(tags = listOfNotNull("story-guide", kind.tag, row.joints.getOrNull(level)?.tag))
        }
        RowBody(scope, row)
    }
}

@Composable
private fun RowBody(scope: StoryRowScope, row: StoryRow) {
    val state = scope.state
    val problems = if (row.role == StoryRowRole.MENU) emptyList() else scope.model.problems[row.line].orEmpty()
    Row(
        id = "story-body-${row.key}",
        tags = listOfNotNull(
            "story-body",
            row.kind.tag,
            row.role.name.lowercase(),
            "selected".takeIf { row.selection == state.selected },
            "match".takeIf { row.role != StoryRowRole.MENU && row.line in scope.matches },
            "current".takeIf { row.selection == scope.current },
            "problem".takeIf { problems.isNotEmpty() },
        ),
        modifier = Modifier.size(0.px).grow(1f).alignItems(vertical = UiAlign.CENTER)
            .input(hoverable = true, clickable = true, draggable = true).onPress { event ->
                // A drag whose release never arrived (the window lost focus mid-gesture) ends here.
                state.drag = null
                state.select(row.selection)
                if (event.isRightClick()) state.menu = StoryRowMenu(row.selection, event.x, event.y)
            }.onDrag { event ->
                if (event.isRightClick()) return@onDrag
                val drag = state.drag ?: run {
                    if (hypot(event.dragTotalX, event.dragTotalY) < DragThreshold) return@onDrag
                    StoryRowDrag.of(state, scope.model, scope.rows, row)?.also { state.drag = it } ?: return@onDrag
                }
                event.consume()
                drag.moveTo(scope.model, scope.rows, event.dragTotalX, event.dragTotalY)
            }.onRelease { event ->
                if (state.drag == null) return@onRelease
                event.consume()
                state.drop(scope.model)
            },
    ) {
        StoryRowContent(scope, row)
        problems.firstOrNull()?.let {
            Image(
                ProblemIcon,
                tags = listOf("story-problem"),
                modifier = Modifier.tooltipOnHover(problems.joinToString("\n") { it.message })
            )
        }
        if (row.role != StoryRowRole.MENU) {
            IconButton(
                RemoveIcon,
                storyLang("remove"),
                id = "story-body-${row.key}-remove",
                tags = listOf("inline", "danger", "story-action")
            ) {
                state.remove(scope.model, scope.rows, row.selection)
            }
        }
        Fold(scope, row)
    }
}

/** The arrow that folds what is under a row, at its right end so the rows keep their starts in line. */
@Composable
private fun Fold(scope: StoryRowScope, row: StoryRow) {
    val block = row.block
    val key = when {
        row.role == StoryRowRole.MENU -> scope.model.cardKey(block)
        block.children.isNotEmpty() -> scope.model.rowKey(block)
        else -> return Box(tags = listOf("story-fold", "empty"))
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

private fun isSelected(scope: StoryRowScope, row: StoryRow): Boolean {
    val line = scope.state.selected?.line ?: return false
    val card = scope.model.outline.groupOf(row.block)
    return line in card.first().line.index..card.last().last
}

/** A phrase, a pause, an option and a menu as statements, for the menus and buttons that add them. */
internal object StoryNewStatements {
    fun wait() = StoryStatements.command(StoryCardKind.WAIT_FUNCTION, WaitDuration.write(1f))

    fun choice() = StoryStatements.choice(storyLang("new.option"))

    fun command(name: String) = StoryStatements.command(name)
}
