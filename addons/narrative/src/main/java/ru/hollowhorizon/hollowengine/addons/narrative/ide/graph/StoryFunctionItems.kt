package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCategories
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorState
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryNodeKind
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryLibrary
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/** The menu of the functions a story can call: its own first, then those of every other story, a submenu per file. */
internal object StoryFunctionItems {
    fun of(state: StoryEditorState, add: (StoryNode) -> Unit): UiDropdownItem? {
        val own = state.root()?.functions.orEmpty().sortedBy { it.name }.map { function ->
            item(function.name) { add(StoryNode.Invoke(StoryGraph.newId(), function.name)) }
        }
        val here = state.context?.file?.path?.let { ScriptRegistry.parse(it).qualified }
        val others = StoryLibrary.stories().filter { it != here }.mapNotNull { file ->
            val functions = StoryLibrary.functions(file).sortedBy { it.name }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            UiDropdownItem(
                ScriptRegistry.display(ScriptId.parse(file, ScriptRegistry.sandboxNamespace)),
                icon = StoryNodeKind.FUNCTION.icon,
                children = functions.map { function ->
                    item(function.name) { add(StoryNode.Invoke(StoryGraph.newId(), function.name, file)) }
                },
            )
        }
        if (own.isEmpty() && others.isEmpty()) return null
        return UiDropdownItem(
            storyLang("add.functions"),
            icon = StoryNodeKind.FUNCTION.icon,
            iconColor = StoryCategories.tint(StoryNodeKind.FUNCTION.color),
            children = own + others.mapIndexed { index, item -> item.copy(separatorBefore = index == 0 && own.isNotEmpty()) },
        )
    }

    private fun item(name: String, onClick: () -> Unit) = StoryCatalog.kindItem(name, StoryNodeKind.INVOKE, onClick)
}
