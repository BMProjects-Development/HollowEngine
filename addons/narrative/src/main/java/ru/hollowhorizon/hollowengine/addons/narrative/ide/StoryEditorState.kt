package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.edit
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryRowField
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeTextModel
import ru.hollowhorizon.hollowengine.client.utils.lang

internal const val StoryEditorStylesheet = "hollowengine-narrative:ui/styles/story-editor.hss"

internal fun storyLang(name: String) = "hollowengine.gui.ide.story.$name".lang

internal fun storyLang(name: String, vararg arguments: Any) = "hollowengine.gui.ide.story.$name".lang(*arguments)

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
internal open class StoryEditorState : AutoCloseable {
    /** The file drawn now; the inspector, drawn elsewhere, reads and writes through it. */
    var context: HollowIdePreviewContext? = null

    var insight: StoryInsight.Session? = null

    val model = HollowIdeTextModel(StoryGraph::decode)
    var selected by mutableStateOf<StorySelection?>(null)

    /** The field open in the full editor of Kotlin. */
    var codeField by mutableStateOf<StoryCodeField?>(null)

    /** The field whose color is being picked. */
    var colorField by mutableStateOf<StoryRowField?>(null)

    /** The names of the variables open in the inspector. */
    var openVariables by mutableStateOf(emptySet<String>())

    /** Whether the inspector shows the imports of the story. */
    var showsImports by mutableStateOf(false)

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

    override fun close() {
        insight?.close()
    }
}
