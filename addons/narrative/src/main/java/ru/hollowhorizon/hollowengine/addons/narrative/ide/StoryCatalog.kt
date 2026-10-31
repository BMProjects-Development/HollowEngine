package ru.hollowhorizon.hollowengine.addons.narrative.ide

import net.minecraft.locale.Language
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.reflect.typeOf

/** What can be added to a story: the steps of its own and the commands stories know, under shared headings. */
internal object StoryCatalog {
    private const val STORY_PACKAGE = "ru.hollowhorizon.hollowengine.addons.narrative.story"
    private const val NAMESPACE = "hollowengine-narrative"
    private const val PARAM = "hollowengine.story.param"

    /** A function of a story that takes a block of steps. */
    private val BLOCKS = setOf("temporary", "async")

    private val FLOW = listOf(
        builtin("checkpoint", param("name")),
        builtin("rewind", param("checkpoint", optional = true)),
        builtin("temporary", param("until", optional = true)),
        builtin("async", param("name")),
        builtin("scene", param("address")),
    )

    fun signatureOf(call: StoryNode.Call): StoryFunctionSignature? {
        val action = call.action ?: return null
        return FLOW.firstOrNull { it.name == action }
            ?: ActionCommands.find(action).singleOrNull()?.signature
    }

    fun title(signature: StoryFunctionSignature): String =
        signature.title?.lang ?: known(key(signature.name)) ?: "@${signature.name.substringAfter(':')}"

    fun description(signature: StoryFunctionSignature): String? =
        signature.description?.lang ?: known(key(signature.name) + ".description")

    /** What the editor calls [param]: its own title, the key under the command, the shared key, its name. */
    fun paramTitle(signature: StoryFunctionSignature, param: StoryParam): String = param.title?.lang
        ?: known("${key(signature.name)}.${param.name}") ?: known("$PARAM.${param.name}") ?: param.name

    fun paramHelp(signature: StoryFunctionSignature, param: StoryParam): String? = param.description?.lang
        ?: known("${key(signature.name)}.${param.name}.description") ?: known("$PARAM.${param.name}.description")

    /** A menu item per command and step of the flow, for adding a node of it. */
    fun items(add: (StoryNode) -> Unit): List<UiDropdownItem> {
        val own = FLOW.map { storyLang("category.flow") to item(it, add) } + listOf(
            storyLang("category.variables") to UiDropdownItem(storyLang("kind.set"), icon = storyIcon("command")) {
                add(StoryNode.Assign(StoryGraph.newId(), ""))
            },
            storyLang("category.flow") to UiDropdownItem(storyLang("kind.code"), icon = storyIcon("block")) {
                add(StoryNode.Code(StoryGraph.newId(), ""))
            },
        )
        val commands = ActionCommands.commands.map { it.signature }.sortedBy(::title)
            .map { it.category?.lang to item(it, add) }
        val byCategory = (own + commands).groupBy({ it.first }, { it.second })
        val headings = byCategory.mapNotNull { (heading, children) -> heading?.let { UiDropdownItem(it, children = children) } }
        return headings.sortedBy { it.label } + byCategory[null].orEmpty()
    }

    private fun item(signature: StoryFunctionSignature, add: (StoryNode) -> Unit) =
        UiDropdownItem(title(signature), icon = signature.icon) {
            add(signature.newCall(StoryGraph.newId()).let { it.copy(block = signature.name in BLOCKS) })
        }

    private fun builtin(name: String, vararg params: StoryParam) =
        StoryFunctionSignature(name, "$STORY_PACKAGE.$name", params.toList())

    private fun param(name: String, optional: Boolean = false) = StoryParam(name, typeOf<String>(), optional)

    /** The key of [id]: `ns:path` is named by `ns.story.command.path`. */
    private fun key(id: String) = "${id.substringBefore(':', NAMESPACE)}.story.command.${id.substringAfter(':')}"

    private fun known(key: String): String? = key.takeIf(Language.getInstance()::has)?.lang
}
