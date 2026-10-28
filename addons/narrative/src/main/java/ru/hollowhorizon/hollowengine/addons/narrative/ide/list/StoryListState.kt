package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdit
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryStatements
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.LazyListState
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeTextModel

/** What the list's edits are called in the history window. */
internal object StoryUndo {
    private const val LANG = "hollowengine.gui.history.story"

    val EDIT = UndoLabel("$LANG.edit")
    val ADD = UndoLabel("$LANG.add")
    val REMOVE = UndoLabel("$LANG.remove")
    val MOVE = UndoLabel("$LANG.move")
    val INDENT = UndoLabel("$LANG.indent")
    val OUTDENT = UndoLabel("$LANG.outdent")
    val DUPLICATE = UndoLabel("$LANG.duplicate")
    val CALL = UndoLabel("$LANG.call")
}

/** The context menu of a row, open at the pointer. */
internal class StoryRowMenu(val selection: StorySelection, val x: Float, val y: Float)

/**
 * What the list keeps while its tab is switched away, and the edits it makes. Every edit goes into the text, which
 * stays the only thing saved; the selection is a line and follows the statement an edit moved.
 */
internal class StoryListState {
    /** The file the list is drawn for now; the inspector, drawn elsewhere, reads and writes through it. */
    var context: HollowIdePreviewContext? = null

    val model = HollowIdeTextModel { text -> StoryListModel(context?.file?.path.orEmpty(), text) }
    val list = LazyListState()
    var selected by mutableStateOf<StorySelection?>(null)

    /** A row to select by its place once the list is rebuilt, for after a removal. */
    var selectIndex by mutableStateOf<Int?>(null)

    /** What to scroll into view, and which field to focus, once they are composed. */
    var reveal by mutableStateOf<StorySelection?>(null)
    var focus by mutableStateOf<String?>(null)

    var search by mutableStateOf<String?>(null)
    var match by mutableStateOf(0)

    var drag by mutableStateOf<StoryRowDrag?>(null)

    /** For the frames after a drop, when rows take their new places at once instead of sliding there. */
    var settling by mutableStateOf(false)

    var menu by mutableStateOf<StoryRowMenu?>(null)

    private val folded = mutableStateMapOf<String, Boolean>()

    /** The text this list wrote last, and the one it was drawn for last: a change that is neither came from elsewhere. */
    private var written: String? = null
    private var seen: String? = null

    fun read(): StoryListModel? = context?.let { model.read(it.text).getOrNull() }

    /** Whether what is under [key] is hidden: as the writer left it, or else hidden for an option and open for the rest. */
    fun isFolded(key: String) = folded[key] ?: key.startsWith(StoryListModel.OptionFold)

    fun toggleFold(key: String) {
        folded[key] = !isFolded(key)
    }

    fun select(selection: StorySelection?, scroll: Boolean = false) {
        selected = selection
        if (scroll) reveal = selection
    }

    /**
     * Writes [text], keeping the selection where it is.
     *
     * @param label what the step is called in the history window.
     * @param mergeKey folds the step into the one just before with the same key, as for the letters typed into a field.
     */
    fun write(text: String, label: UndoLabel = StoryUndo.EDIT, mergeKey: String? = null) {
        val context = context?.takeUnless { it.readOnly } ?: return
        written = text
        context.edit(text, label, mergeKey)
    }

    /** Writes [edit], selects the statement it ended on and, with [field], puts the caret into that field of it. */
    fun write(edit: StoryEdit?, label: UndoLabel, menu: Boolean = false, field: String? = null) {
        val context = context?.takeUnless { it.readOnly } ?: return
        edit ?: return
        written = edit.text
        context.edit(edit.text, label)
        val selection = StorySelection(edit.line, menu)
        read()?.foldsOver(selection)?.forEach { folded[it] = false }
        select(selection, scroll = true)
        focus = field?.let { fieldId(edit.line, it) }
    }

    fun selectNext(rows: List<StoryRow>, step: Int) {
        if (rows.isEmpty()) return
        val at = rows.indexOfFirst { it.selection == selected }
        select(
            if (at < 0) rows.first().selection else rows[(at + step).coerceIn(0, rows.lastIndex)].selection,
            scroll = true
        )
    }

    /** Moves the selected statement: an option inside its menu, anything else as the whole card. */
    fun move(model: StoryListModel, up: Boolean) {
        val selection = selected ?: return
        write(StoryEdits.move(model.text, selection.line, up, whole(model, selection)), StoryUndo.MOVE, selection.menu)
    }

    fun indent(model: StoryListModel, out: Boolean) {
        val selection = selected ?: return
        val line = selection.line
        write(
            if (out) StoryEdits.outdent(model.text, line) else StoryEdits.indent(model.text, line),
            if (out) StoryUndo.OUTDENT else StoryUndo.INDENT,
            selection.menu,
        )
    }

    /** Adds [statement] after what is selected, or at the end of the story when nothing is. */
    fun add(model: StoryListModel, statement: String) {
        selected?.let { insertAfter(model, it, statement) } ?: append(model, statement)
    }

    /** Adds [statement] at the end of the story. */
    fun append(model: StoryListModel, statement: String) {
        write(StoryEdits.insertAfter(model.text, null, listOf(statement)), StoryUndo.ADD, field = fieldOf(statement))
    }

    /**
     * Adds [statement] after the card of [selection]: another option after a selected option, anything else after
     * the whole card, so a phrase added at an option goes after its menu.
     */
    fun insertAfter(model: StoryListModel, selection: StorySelection, statement: String) {
        val block = model.outline.blockAt(selection.line) ?: return append(model, statement)
        val option = !selection.menu && block.line.kind is StoryLineKind.Choice
        val anchor = if (option && isChoice(statement)) block else model.outline.groupOf(block).last()
        write(StoryEdits.insertAfter(model.text, anchor.line.index, listOf(statement)), StoryUndo.ADD, field = fieldOf(statement))
    }

    /** Adds [statement] as the last one under [line]. */
    fun insertInto(model: StoryListModel, line: Int, statement: String) {
        write(StoryEdits.insertInto(model.text, line, listOf(statement)), StoryUndo.ADD, field = fieldOf(statement))
    }

    /** A new phrase, said by whoever the selected one or the last one before it is said by. */
    fun newPhrase(model: StoryListModel): String {
        val lines = model.outline.cst.lines
        val before = lines.take((selected?.line ?: lines.lastIndex) + 1)
        val speaker =
            before.asReversed().firstNotNullOfOrNull { (it.kind as? StoryLineKind.Dialogue)?.let(model::speaker) }
        return phrase(speaker.orEmpty(), storyLang("new.phrase"))
    }

    /** Removes the statement of [selection], or its whole card where one part cannot stay alone. */
    fun remove(model: StoryListModel, rows: List<StoryRow>, selection: StorySelection) {
        val whole = selection.menu || !isRow(model, selection.line)
        val index = rows.indexOfFirst { it.selection == selection }
        write(StoryEdits.remove(model.text, selection.line, whole), StoryUndo.REMOVE)
        selected = null
        selectIndex = index.takeIf { it >= 0 }
    }

    fun duplicate(model: StoryListModel, selection: StorySelection) {
        write(StoryEdits.duplicate(model.text, selection.line, whole(model, selection)), StoryUndo.DUPLICATE, selection.menu)
    }

    /** Ends the drag, moving its rows where it landed. */
    fun drop(model: StoryListModel) {
        val drag = drag ?: return
        this.drag = null
        settling = true
        val target = drag.target ?: return
        val selection = drag.selection
        write(
            StoryEdits.relocate(model.text, selection.line, drag.whole, target.line, target.placement),
            StoryUndo.MOVE,
            selection.menu,
        )
    }

    /**
     * Selects in view the card that a change made elsewhere touched first, as an undo or redo does, so the writer sees
     * what was taken back. The list's own edits select what they ended on themselves.
     */
    fun follow(model: StoryListModel) {
        val before = seen
        seen = model.text
        if (before == null || before == model.text || model.text == written) return
        val prefix = before.commonPrefixWith(model.text)
        val line = prefix.count { it == '\n' }
        val block = (line downTo 0).firstNotNullOfOrNull(model.outline::blockAt) ?: return
        reveal(model, StorySelection(block.line.index))
    }

    /** Opens what hides [selection] and selects it in view. */
    fun reveal(model: StoryListModel, selection: StorySelection) {
        model.foldsOver(selection).forEach { folded[it] = false }
        select(selection, scroll = true)
    }

    /** Selects the next of what the search finds, or the previous with a negative [step]. */
    fun findNext(model: StoryListModel, step: Int) {
        val matches = model.matches(search.orEmpty())
        if (matches.isEmpty()) return
        match = (match + step).mod(matches.size)
        reveal(model, matches[match])
    }

    /** Whether the statement of [selection] goes with its whole card: all but an option, which goes alone. */
    fun whole(model: StoryListModel, selection: StorySelection): Boolean =
        selection.menu || model.outline.blockAt(selection.line)?.line?.kind !is StoryLineKind.Choice

    /** Whether [line] is a part of a card that goes alone: an option, an `@elseif` or `@else`. */
    private fun isRow(model: StoryListModel, line: Int): Boolean {
        val kind = model.outline.blockAt(line)?.line?.kind ?: return false
        return kind is StoryLineKind.Choice || kind is StoryLineKind.ElseIf || kind == StoryLineKind.Else
    }

    private fun isChoice(statement: String) = statement.startsWith("@choice")

    /** The field a new statement is typed into first. */
    private fun fieldOf(statement: String): String? = when {
        isChoice(statement) -> "option"
        statement.startsWith("@") -> "arg-0"
        else -> "text"
    }

    companion object {
        fun fieldId(line: Int, field: String) = "story-$line-$field"

        /** A phrase of [speaker], who is a name, a quoted name or an `{expression}` as the list shows it. */
        fun phrase(speaker: String, text: String): String {
            val name = speaker.trim()
            return when {
                name.isEmpty() -> StoryStatements.dialogue(null, text)
                name.startsWith("{") && name.endsWith("}") -> "$name: ${StoryStatements.lineText(text)}"
                else -> StoryStatements.dialogue(name, text)
            }
        }
    }
}
