package ru.hollowhorizon.hollowengine.addons.narrative.ide

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog.paramHelp
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.utils.lang

/** What can be added to a story: the steps of its own and the commands stories know, under shared headings. */
internal object StoryCatalog {
    private const val NAMESPACE = "hollowengine-narrative"
    private const val PARAM = "hollowengine.story.param"
    private const val OPERATOR = "hollowengine.story.operator"
    private const val HEADING = StoryCategories.PREFIX
    private const val HEX = 16
    private const val OPAQUE = 0xFF shl 24

    fun signatureOf(call: StoryNode.Call): StoryFunctionSignature? = StoryActionFunctions.signature(call)

    fun title(signature: StoryFunctionSignature): String =
        signature.title?.lang ?: known(key(signature.name)) ?: "@${signature.name.substringAfter(':')}"

    fun description(signature: StoryFunctionSignature): String? =
        signature.description?.lang ?: known(key(signature.name) + ".description")

    /** What the editor calls [param]: its own title, the key under the command, the shared key, its name. */
    fun paramTitle(signature: StoryFunctionSignature, param: StoryParam): String =
        param.title?.lang ?: known("${key(signature.name)}.${param.name}") ?: known("$PARAM.${param.name}")
        ?: param.name

    fun paramHelp(signature: StoryFunctionSignature, param: StoryParam): String? =
        param.description?.lang ?: known("${key(signature.name)}.${param.name}.description")
        ?: known("$PARAM.${param.name}.description")

    fun operatorTitle(id: String): String = known("$OPERATOR.$id") ?: readable(id)

    /** What a pin of an operator is called: pins of the same meaning share one name. */
    fun operatorPinTitle(name: String): String = known("$OPERATOR.pin.$name") ?: readable(name)

    /** [paramHelp] of [param], and that it can be left out. */
    fun paramHint(signature: StoryFunctionSignature, param: StoryParam): String? {
        val note = storyLang("param.optional").takeIf { param.optional }
        return listOfNotNull(paramHelp(signature, param), note).joinToString("\n").ifEmpty { null }
    }

    /** The color of the nodes of [signature]: the one it gives its category, or that of the category. */
    fun color(signature: StoryFunctionSignature): UiColor? {
        signature.categoryColor?.removePrefix("#")?.toIntOrNull(HEX)?.let { return UiColor.fromArgb(OPAQUE or it) }
        return signature.category?.let(StoryCategories::color)
    }

    /** The icon of the nodes of [signature]: its own, or that of its category. */
    fun icon(signature: StoryFunctionSignature): String? =
        signature.icon ?: signature.category?.let(StoryCategories::icon)

    /** The key of the category of operators [category] is: they share the categories of commands. */
    fun operatorCategory(category: String) = HEADING + category

    fun operatorColor(operator: StoryOperator): UiColor = StoryCategories.color(operatorCategory(operator.category))

    fun operatorIcon(operator: StoryOperator): String? = StoryCategories.icon(operatorCategory(operator.category))

    /** The heading a category sits under: `effects.sound` under `effects`, and one of the top level under itself. */
    private fun rootOf(category: String) =
        if ('.' in category.substringAfter(HEADING, "")) category.substringBeforeLast('.') else category

    /** What the input [name] of the operator [id] takes, when that needs saying. */
    fun operatorPinHint(id: String, name: String): String? = known("$OPERATOR.$id.$name.description")

    private var attributesLanguage: Language? = null
    private var attributes = emptyList<UiTextCompletion>()

    private fun readable(id: String) = id.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** What the input [name] of an operator is usually given, to complete as it is typed. */
    fun inputHints(name: String): List<UiTextCompletion> = if (name == "attribute") attributeHints() else emptyList()

    /** The attributes under the language they were last named in: fields ask for them each time a node is laid out. */
    private fun attributeHints(): List<UiTextCompletion> {
        val language = Language.getInstance()
        if (language !== attributesLanguage) {
            attributesLanguage = language
            attributes = BuiltInRegistries.ATTRIBUTE.entrySet().map { (key, attribute) ->
                val id = key.location().toString()
                val title = Component.translatable(attribute.descriptionId).string
                UiTextCompletion(label = id, tail = title, filterText = "$id $title", wordChars = ":/._-")
            }.sortedBy { it.label }
        }
        return attributes
    }

    /** A heading per category of operators with those [accepts] lets through, for adding a value. */
    fun operatorItems(accepts: (StoryOperator) -> Boolean = { true }, add: (StoryNode) -> Unit): List<UiDropdownItem> =
        StoryOperators.all.filter(accepts).groupBy { it.category }.map { (category, operators) ->
            heading(operatorCategory(category), operators.map { operator ->
                UiDropdownItem(
                    operatorTitle(operator.id),
                    icon = operatorIcon(operator) ?: StoryNodeKind.VALUE.icon,
                    iconColor = StoryCategories.tint(operatorColor(operator)),
                ) { add(StoryNode.Pure(StoryGraph.newId(), operator.id)) }
            })
        }

    /** The submenu of [category], in its icon and color. */
    fun heading(category: String, children: List<UiDropdownItem>) = UiDropdownItem(
        category.lang,
        icon = StoryCategories.icon(category),
        iconColor = StoryCategories.tint(StoryCategories.color(category)),
        children = children,
    )

    /** An item that adds a node of [kind], in its icon and color. */
    fun kindItem(label: String, kind: StoryNodeKind, onClick: () -> Unit) =
        UiDropdownItem(label, icon = kind.icon, iconColor = StoryCategories.tint(kind.color), onClick = onClick)

    /** A menu item per command and step of the flow, for adding a node of it. */
    fun items(add: (StoryNode) -> Unit): List<UiDropdownItem> {
        val own = StoryBuiltins.all.map { it.category to item(it, add) } + listOf(
            "${HEADING}variables" to kindItem(storyLang("kind.set"), StoryNodeKind.SET) {
                add(StoryNode.Assign(StoryGraph.newId(), ""))
            },
            "${HEADING}flow" to kindItem(storyLang("kind.code"), StoryNodeKind.CODE) {
                add(StoryNode.Code(StoryGraph.newId(), ""))
            },
        )
        val commands = ActionCommands.commands.map { it.signature }.filter { !it.hidden }.sortedBy(::title)
            .map { it.category to item(it, add) }
        val all = own + commands
        val headings = all.mapNotNull { it.first }.groupBy(::rootOf).map { (root, categories) ->
            val inner = categories.distinct().filter { it != root }.map { category ->
                heading(category, all.filter { it.first == category }.map { it.second })
            }.sortedBy { it.label }
            heading(root, inner + all.filter { it.first == root }.map { it.second })
        }
        return headings.sortedBy { it.label } + all.filter { it.first == null }.map { it.second }
    }

    private fun item(signature: StoryFunctionSignature, add: (StoryNode) -> Unit) =
        UiDropdownItem(
            title(signature),
            icon = icon(signature) ?: StoryNodeKind.COMMAND.icon,
            iconColor = StoryCategories.tint(color(signature) ?: StoryNodeKind.COMMAND.color),
        ) {
            add(signature.newCall(StoryGraph.newId()))
        }

    /** The key of [id]: `ns:path` is named by `ns.story.command.path`. */
    private fun key(id: String) = "${id.substringBefore(':', NAMESPACE)}.story.command.${id.substringAfter(':')}"

    private fun known(key: String): String? = key.takeIf(Language.getInstance()::has)?.lang
}
