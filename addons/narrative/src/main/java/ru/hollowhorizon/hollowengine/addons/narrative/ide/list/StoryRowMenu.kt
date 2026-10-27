package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem

/**
 * The menu of a row: adding after it or into it, which is how a statement gets after a menu or inside an option,
 * and the same moves the keys make.
 */
@Composable
internal fun StoryRowContextMenu(state: StoryListState, model: StoryListModel, rows: List<StoryRow>, menu: StoryRowMenu) {
    val selection = menu.selection
    val block = model.outline.blockAt(selection.line) ?: return run { state.menu = null }
    val option = !selection.menu && block.line.kind is StoryLineKind.Choice
    val after: (String) -> Unit = { state.insertAfter(model, selection, it) }
    val into: (String) -> Unit = { state.insertInto(model, selection.line, it) }
    val items = buildList {
        add(UiDropdownItem(storyLang("menu.add_after"), children = statementItems(state, model, after)))
        if (block.line.opensBlock && !selection.menu) {
            add(UiDropdownItem(storyLang("menu.add_into"), children = statementItems(state, model, into)))
        }
        if (option || selection.menu) {
            val last = model.outline.groupOf(block).last()
            add(UiDropdownItem(storyLang("choice.add")) {
                state.insertAfter(model, StorySelection(last.line.index), StoryNewStatements.choice())
            })
        }
        add(UiDropdownItem(storyLang("duplicate"), shortcut = "Ctrl+D", separatorBefore = true) { state.duplicate(model, selection) })
        add(UiDropdownItem(storyLang("menu.up"), shortcut = "Alt+↑") { state.move(model, up = true) })
        add(UiDropdownItem(storyLang("menu.down"), shortcut = "Alt+↓") { state.move(model, up = false) })
        add(UiDropdownItem(storyLang("menu.indent"), shortcut = "Tab") { state.indent(model, out = false) })
        add(UiDropdownItem(storyLang("menu.outdent"), shortcut = "Shift+Tab", enabled = block.parent != null) {
            state.indent(model, out = true)
        })
        add(UiDropdownItem(storyLang("remove"), shortcut = "Delete", separatorBefore = true) { state.remove(model, rows, selection) })
    }
    ContextMenu(
        id = "story-row-menu",
        anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
        items = items,
        onExpandedChange = { if (!it) state.menu = null },
    )
}

/** What can be added: a phrase, a pause, a menu, and any command the stories know. */
private fun statementItems(state: StoryListState, model: StoryListModel, add: (String) -> Unit): List<UiDropdownItem> = listOf(
    UiDropdownItem(storyLang("add.phrase")) { add(state.newPhrase(model)) },
    UiDropdownItem(storyLang("add.wait")) { add(StoryNewStatements.wait()) },
    UiDropdownItem(storyLang("add.choice")) { add(StoryNewStatements.choice()) },
    UiDropdownItem(storyLang("add.command"), children = commandItems { add(StoryNewStatements.command(it)) }),
)
