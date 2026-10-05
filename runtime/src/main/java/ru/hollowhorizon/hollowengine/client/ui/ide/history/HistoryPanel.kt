package ru.hollowhorizon.hollowengine.client.ui.ide.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiBoxMode
import ru.hollowhorizon.hollowengine.client.ui.UiCursorShape
import ru.hollowhorizon.hollowengine.client.ui.align
import ru.hollowhorizon.hollowengine.client.ui.cursor
import ru.hollowhorizon.hollowengine.client.ui.docking.DockTags
import ru.hollowhorizon.hollowengine.client.ui.docking.LocalDockPanelTitle
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.scrollable
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.textOverflow
import ru.hollowhorizon.hollowengine.client.ui.textWrap
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val LANG = UndoLabel.LANG
private const val UNDO_ICON = "hollowengine:textures/gui/icons/undo.svg"
private const val REDO_ICON = "hollowengine:textures/gui/icons/redo.svg"

/**
 * The steps of one history, oldest at the top: those done, the current one marked, and those taken back
 * greyed out below it. A click on a row undoes or redoes up to it.
 */
@Composable
internal fun HistoryDock(histories: IdeHistories, focused: String?) {
    val contexts = histories.all()
    val context = histories.resolve(focused, contexts)
    Column(tags = listOf("ide-panel", "history-panel"), modifier = Modifier.size(100.percent, 100.percent)) {
        HistoryHeader(histories, contexts, context)
        if (context == null) {
            Box(tags = listOf("history-empty"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
                Text("$LANG.empty".lang)
            }
            return@Column
        }
        HistorySteps(context)
    }
}

@Composable
private fun HistoryHeader(histories: IdeHistories, contexts: List<HistoryContext>, context: HistoryContext?) {
    val parked = LocalDockPanelTitle.current
    var expanded by remember { mutableStateOf(false) }
    Row(id = parked?.headerId, tags = listOf("tool-window-header"), modifier = parked?.dragHandle ?: Modifier) {
        parked?.let { panel ->
            panel.icon?.let { icon -> Image(icon, tags = listOf(DockTags.PinnedHeaderIcon)) }
            Text(panel.title, tags = listOf(DockTags.PinnedHeaderLabel, "tool-window-title"))
        }
        UiDropdown(
            id = "history-context",
            label = context?.title ?: "$LANG.none".lang,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            items = contexts.map { candidate ->
                UiDropdownItem(candidate.title, candidate.icon, checked = candidate === context, mark = UiDropdownMark.RADIO) {
                    histories.choose(candidate)
                }
            },
            tags = listOf("history-context-dropdown"),
        )
        Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
        val history = context?.history
        HistoryButton("history-undo", UNDO_ICON, "$LANG.undo".lang, history?.canUndo == true) { history?.undo() }
        HistoryButton("history-redo", REDO_ICON, "$LANG.redo".lang, history?.canRedo == true) { history?.redo() }
    }
}

@Composable
private fun HistorySteps(context: HistoryContext) {
    val history = context.history
    val steps = history.steps
    val position = history.position
    Column(
        tags = listOf("history-list"),
        modifier = Modifier.size(100.percent, 0.px).grow(1f).scrollable(),
    ) {
        StepRow(context, history, 0, "$LANG.initial".lang, position)
        steps.forEachIndexed { index, step ->
            key(index) { StepRow(context, history, index + 1, step.label.text, position) }
        }
    }
}

/** The row that leaves [target] steps done when clicked. */
@Composable
private fun StepRow(context: HistoryContext, history: UndoHistory, target: Int, label: String, position: Int) {
    val state = when {
        target == position -> "current"
        target > position -> "undone"
        else -> "done"
    }
    Row(
        id = "history-${context.id}-$target",
        tags = listOf("history-row", state),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .onClick { event ->
                history.moveTo(target)
                event.consume()
            },
    ) {
        Text(
            label,
            tags = listOf("history-label"),
            modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS),
        )
    }
}

@Composable
private fun HistoryButton(id: String, icon: String, tooltip: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOfNotNull("console-button", "disabled".takeUnless { enabled }),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(if (enabled) UiCursorShape.HAND else UiCursorShape.DEFAULT)
            .tooltipOnHover(tooltip)
            .onClick { event ->
                if (enabled && event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    ) {
        Image(icon, tags = listOf("console-button-icon"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
    }
}
