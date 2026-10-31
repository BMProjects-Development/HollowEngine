package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.canMove
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.duplicate
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.edit
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.move
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.remove
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeOption
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.unlink
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorState
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySelection
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryUndo
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.client.ui.LazyListState

/** The context menu of a row, open at the pointer. */
internal class StoryRowMenu(val row: StoryRow, val x: Float, val y: Float)

/** What the list keeps while its tab is switched away. */
internal class StoryListState : StoryEditorState() {
    val list = LazyListState()

    /** What to scroll into view, and which field to focus, once they are composed. */
    var reveal by mutableStateOf<StorySelection?>(null)
    var focus by mutableStateOf<String?>(null)

    var listWidth by mutableStateOf(0f)
    var search by mutableStateOf<String?>(null)
    var match by mutableStateOf(0)

    var drag by mutableStateOf<StoryRowDrag?>(null)

    /** For the frames after a drop, when rows take their new places at once instead of sliding there. */
    var settling by mutableStateOf(false)

    var menu by mutableStateOf<StoryRowMenu?>(null)

    private val folded = mutableStateMapOf<String, Boolean>()

    /** Whether what is under [key] is hidden: as the writer left it, or else open, the parameters of a card aside. */
    fun isFolded(key: String) = folded[key] ?: (key == paramsKey(key.substringAfter('/')))

    fun toggleFold(key: String) {
        folded[key] = !isFolded(key)
    }

    fun select(selection: StorySelection?, scroll: Boolean = false) {
        selected = selection
        if (scroll) reveal = selection
    }

    /** Adds a line said by whoever said the selected one, ready to be typed into. */
    fun addPhrase(graph: StoryGraph) {
        val speaker = (selected?.let { graph.node(it.node) } as? StoryNode.Say)?.speaker.orEmpty()
        val node = StoryNode.Say(StoryGraph.newId(), speaker)
        add(node)
        focus = fieldId(node.id, "text")
    }

    fun addOption(menu: StoryNode.Choose) = change(StoryUndo.ADD) {
        edit(menu.id) { menu.copy(options = menu.options + StoryOption(storyLang("new.option"))) }
    }

    fun remove(row: StoryRow) {
        change(StoryUndo.REMOVE) {
            when {
                row.role == StoryRowRole.JUMP -> unlink(row.selection.node, row.pin.orEmpty())
                row.role == StoryRowRole.PIN -> StoryPins.optionIndex(row.pin.orEmpty())
                    ?.let { removeOption(row.node.id, it) } ?: this

                else -> remove(row.node.id)
            }
        }
        selected = null
    }

    fun duplicate(row: StoryRow) = change(StoryUndo.DUPLICATE) { duplicate(row.node.id) }

    /** Moves the card of [row] past its neighbor in the list. */
    fun move(rows: List<StoryRow>, row: StoryRow, up: Boolean) {
        if (!row.moves) return
        val cards = rows.filter { it.moves }
        val at = cards.indexOfFirst { it.key == row.key }
        val neighbor = cards.getOrNull(at + if (up) -1 else 1) ?: return
        val place = if (up) StoryPlace.Before(neighbor.node.id) else StoryPlace.After(neighbor.node.id)
        change(StoryUndo.MOVE) { move(row.node.id, place) }
        select(row.selection, scroll = true)
    }

    /** Ends the drag, moving its card where it landed. */
    fun drop() {
        val drag = drag ?: return
        this.drag = null
        settling = true
        val target = drag.target ?: return
        change(StoryUndo.MOVE) { move(drag.row.node.id, target) }
    }

    /** Opens what hides [selection] and selects it in view. */
    fun reveal(flow: StoryFlow, selection: StorySelection) {
        var owner = flow.ownerOf(selection.node)
        while (owner != null) {
            folded[owner.first] = false
            folded["${owner.first}/${owner.second}"] = false
            owner = flow.ownerOf(owner.first)
        }
        select(selection, scroll = true)
    }

    /** Selects the next of what the search finds, or the previous with a negative [step]. */
    fun findNext(flow: StoryFlow, step: Int) {
        val matches = storyMatches(flow, search.orEmpty())
        if (matches.isEmpty()) return
        match = (match + step).mod(matches.size)
        reveal(flow, matches[match])
    }

    fun canDrop(graph: StoryGraph, row: StoryRow, place: StoryPlace) = graph.canMove(row.node.id, place)

    companion object {
        fun fieldId(node: String, field: String) = "story-$node-$field"
    }
}
