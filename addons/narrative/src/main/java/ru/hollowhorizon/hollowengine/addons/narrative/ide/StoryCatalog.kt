package ru.hollowhorizon.hollowengine.addons.narrative.ide

import net.minecraft.locale.Language
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryBuiltins
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperator
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperators
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCommand
import ru.hollowhorizon.hollowengine.client.HighlightTheme
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang

/** What can be added to a story: the steps of its own and the commands stories know, under shared headings. */
internal object StoryCatalog {
    private const val NAMESPACE = "hollowengine-narrative"
    private const val PARAM = "hollowengine.story.param"
    private const val OPERATOR = "hollowengine.story.operator"
    private const val HEX = 16
    private const val OPAQUE = 0xFF shl 24
    private const val DIMMED = 0.6f

    private var palette: Pair<List<StoryCommand>, Map<String, UiColor>>? = null

    fun signatureOf(call: StoryNode.Call): StoryFunctionSignature? = StoryActionFunctions.signature(call)

    fun title(signature: StoryFunctionSignature): String =
        signature.title?.lang ?: known(key(signature.name)) ?: "@${signature.name.substringAfter(':')}"

    fun description(signature: StoryFunctionSignature): String? =
        signature.description?.lang ?: known(key(signature.name) + ".description")

    /** What the editor calls [param]: its own title, the key under the command, the shared key, its name. */
    fun paramTitle(signature: StoryFunctionSignature, param: StoryParam): String = param.title?.lang
        ?: known("${key(signature.name)}.${param.name}") ?: known("$PARAM.${param.name}") ?: param.name

    fun paramHelp(signature: StoryFunctionSignature, param: StoryParam): String? = param.description?.lang
        ?: known("${key(signature.name)}.${param.name}.description") ?: known("$PARAM.${param.name}.description")

    fun operatorTitle(id: String): String = known("$OPERATOR.$id") ?: readable(id)

    /** What a pin of an operator is called: pins of the same meaning share one name. */
    fun operatorPinTitle(name: String): String = known("$OPERATOR.pin.$name") ?: readable(name)

    /** [paramHelp] of [param], and that it can be left out. */
    fun paramHint(signature: StoryFunctionSignature, param: StoryParam): String? {
        val note = storyLang("param.optional").takeIf { param.optional }
        return listOfNotNull(paramHelp(signature, param), note).joinToString("\n").ifEmpty { null }
    }

    /** The color of the category of [signature]. */
    fun color(signature: StoryFunctionSignature): UiColor? = signature.category?.let { colors()[it] }

    fun operatorColor(category: String): UiColor? = colors()["$OPERATOR.$category"]

    /**
     * The color of every category: the one a command gives it, or else a slot of the palette in the order of the names,
     * dimmed to carry white text.
     */
    private fun colors(): Map<String, UiColor> {
        val commands = ActionCommands.commands
        palette?.takeIf { it.first === commands }?.let { return it.second }
        val named = commands.mapNotNull { command ->
            val signature = command.signature
            val color = signature.categoryColor?.removePrefix("#")?.toIntOrNull(HEX) ?: return@mapNotNull null
            signature.category?.let { it to UiColor.fromArgb(OPAQUE or color) }
        }.toMap()
        val free = (commands.mapNotNull { it.signature.category } + StoryOperators.all.map { "$OPERATOR.${it.category}" })
            .filter { it !in named }.distinct().sorted()
        val given = free.withIndex().associate { (slot, category) -> category to dimmed(HighlightTheme.paletteArgb(slot)) }
        return (named + given).also { palette = commands to it }
    }

    private fun dimmed(argb: Int) = UiColor(channel(argb shr 16), channel(argb shr 8), channel(argb))

    private fun channel(value: Int) = (value and 0xFF) / 255f * DIMMED

    /** What the input [name] of the operator [id] takes, when that needs saying. */
    fun operatorPinHint(id: String, name: String): String? = known("$OPERATOR.$id.$name.description")

    private fun readable(id: String) = id.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** A heading per category of operators with those [accepts] lets through, for adding a value. */
    fun operatorItems(accepts: (StoryOperator) -> Boolean = { true }, add: (StoryNode) -> Unit): List<UiDropdownItem> =
        StoryOperators.all.filter(accepts).groupBy { it.category }.map { (category, operators) ->
            UiDropdownItem(storyLang("category.$category"), children = operators.map { operator ->
                UiDropdownItem(operatorTitle(operator.id)) { add(StoryNode.Pure(StoryGraph.newId(), operator.id)) }
            })
        }

    /** A menu item per command and step of the flow, for adding a node of it. */
    fun items(add: (StoryNode) -> Unit): List<UiDropdownItem> {
        val own = StoryBuiltins.all.map { storyLang("category.flow") to item(it, add) } + listOf(
            storyLang("category.variables") to UiDropdownItem(storyLang("kind.set"), icon = storyIcon("command")) {
                add(StoryNode.Assign(StoryGraph.newId(), ""))
            },
            storyLang("category.flow") to UiDropdownItem(storyLang("kind.code"), icon = storyIcon("block")) {
                add(StoryNode.Code(StoryGraph.newId(), ""))
            },
        )
        val commands = ActionCommands.commands.map { it.signature }.filter { !it.hidden }.sortedBy(::title)
            .map { it.category?.lang to item(it, add) }
        val byCategory = (own + commands).groupBy({ it.first }, { it.second })
        val headings = byCategory.mapNotNull { (heading, children) -> heading?.let { UiDropdownItem(it, children = children) } }
        return headings.sortedBy { it.label } + byCategory[null].orEmpty()
    }

    private fun item(signature: StoryFunctionSignature, add: (StoryNode) -> Unit) =
        UiDropdownItem(title(signature), icon = signature.icon) {
            add(signature.newCall(StoryGraph.newId()))
        }

    /** The key of [id]: `ns:path` is named by `ns.story.command.path`. */
    private fun key(id: String) = "${id.substringBefore(':', NAMESPACE)}.story.command.${id.substringAfter(':')}"

    private fun known(key: String): String? = key.takeIf(Language.getInstance()::has)?.lang
}
