package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.edit
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.insert
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeTextModel
import ru.hollowhorizon.hollowengine.client.utils.lang

internal const val StoryEditorStylesheet = "hollowengine-narrative:ui/styles/story-list.hss"

internal fun storyLang(name: String) = "hollowengine.gui.ide.story.$name".lang

internal fun storyIcon(name: String) = "hollowengine-narrative:textures/gui/icons/story/$name.svg"

/** What the edits of a story are called in the history window. */
internal object StoryUndo {
    private const val LANG = "hollowengine.gui.history.story"

    val EDIT = UndoLabel("$LANG.edit")
    val ADD = UndoLabel("$LANG.add")
    val REMOVE = UndoLabel("$LANG.remove")
    val MOVE = UndoLabel("$LANG.move")
    val DUPLICATE = UndoLabel("$LANG.duplicate")
    val LINK = UndoLabel("$LANG.link")
}

/** A node of a story, or the branch of it that leaves by [pin]. */
internal data class StorySelection(val node: String, val pin: String? = null)

/** What the editors of a `.story` share: the file as a graph and what is selected. Every edit is written to the file. */
internal open class StoryEditorState {
    /** The file drawn now; the inspector, drawn elsewhere, reads and writes through it. */
    var context: HollowIdePreviewContext? = null

    val model = HollowIdeTextModel(StoryGraph::decode)
    var selected by mutableStateOf<StorySelection?>(null)

    /** Whether the inspector shows the Kotlin of the story. */
    var showsKotlin by mutableStateOf(false)

    fun read(): StoryGraph? = context?.let { model.read(it.text).getOrNull() }

    /**
     * Writes what [change] makes of the story.
     *
     * @param label what the step is called in the history window.
     * @param mergeKey folds the step into the one just before with the same key, as for the letters typed into a field.
     */
    fun change(label: UndoLabel = StoryUndo.EDIT, mergeKey: String? = null, change: StoryGraph.() -> StoryGraph) {
        val context = context?.takeUnless { it.readOnly } ?: return
        val graph = read() ?: return
        context.edit(graph.change().encode(), label, mergeKey)
    }

    /** Changes the node [id]; typing into [field] of it is one step of the history. */
    fun editNode(id: String, field: String, change: (StoryNode) -> StoryNode) =
        change(mergeKey = "$id/$field") { edit(id, change) }

    /** Adds [node] after what is selected, or at the end of the story when nothing is, and selects it. */
    fun add(node: StoryNode) {
        val at = selected
        change(StoryUndo.ADD) {
            val story = if (nodes.any { it is StoryNode.Start }) this
            else copy(nodes = nodes + StoryNode.Start(StoryGraph.newId(), MAIN))
            story.insert(node, at?.let(::placeOf) ?: story.end())
        }
        selected = StorySelection(node.id)
    }

    private fun placeOf(selection: StorySelection): StoryPlace =
        selection.pin?.let { StoryPlace.Into(selection.node, it) } ?: StoryPlace.After(selection.node)

    /** The place after the last step of the first story. */
    private fun StoryGraph.end(): StoryPlace {
        val section = StoryFlow(this).sections.first()
        val last = section.steps.lastOrNull { it !is StoryStep.Jump }
        return last?.let { StoryPlace.After(it.id) } ?: StoryPlace.Into(section.start!!.id, StoryPins.NEXT)
    }

    private companion object {
        const val MAIN = "main"
    }
}
