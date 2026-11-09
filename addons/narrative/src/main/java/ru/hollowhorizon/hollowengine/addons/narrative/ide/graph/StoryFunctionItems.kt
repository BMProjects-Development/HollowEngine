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
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/**
 * The menu of the functions a story can call: [create], the item declaring a new one, then its own, then those of
 * every other story, a submenu per file.
 */
internal object StoryFunctionItems {
    fun of(state: StoryEditorState, add: (StoryNode) -> Unit, create: UiDropdownItem? = null): UiDropdownItem? {
        val own = state.root()?.functions.orEmpty().sortedBy { it.name }.map { function ->
            item(function.name) { add(StoryNode.Invoke(StoryGraph.newId(), function.name)) }
        }
        val here = state.context?.file?.path?.let { ScriptRegistry.parse(it).qualified }
        val others = StoryLibrary.stories().filter { it != here }.mapNotNull { file ->
            val functions = StoryLibrary.functions(file).sortedBy { it.name }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            UiDropdownItem(
                ScriptRegistry.display(ScriptId.parse(file, ScriptRegistry.sandboxNamespace)),
                mark = UiMenuParts.icon(StoryNodeKind.FUNCTION.icon),
                children = functions.map { function ->
                    item(function.name) { add(StoryNode.Invoke(StoryGraph.newId(), function.name, file)) }
                },
            )
        }
        val sections = listOfNotNull(create?.let(::listOf), own.takeIf { it.isNotEmpty() }, others.takeIf { it.isNotEmpty() })
        if (sections.isEmpty()) return null
        return UiDropdownItem(
            storyLang("add.functions"),
            mark = UiMenuParts.icon(StoryNodeKind.FUNCTION.icon, StoryCategories.tint(StoryNodeKind.FUNCTION.color)),
            children = sections.flatMapIndexed { index, items ->
                items.mapIndexed { at, item -> item.copy(separatorBefore = index > 0 && at == 0) }
            },
        )
    }

    private fun item(name: String, onClick: () -> Unit) = StoryCatalog.kindItem(name, StoryNodeKind.INVOKE, onClick)
}
