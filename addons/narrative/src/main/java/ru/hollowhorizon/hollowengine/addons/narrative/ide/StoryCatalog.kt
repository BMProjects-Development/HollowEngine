package ru.hollowhorizon.hollowengine.addons.narrative.ide

import net.minecraft.locale.Language
import ru.hollowhorizon.hollowengine.addons.narrative.components.ComponentCommands
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryCatalog.paramHelp
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang

/** What can be added to a story: the steps of its own and the commands stories know, under shared headings. */
internal object StoryCatalog {
    private const val NAMESPACE = "hollowengine-narrative"
    private const val PARAM = "hollowengine.story.param"
    private const val OPERATOR = "hollowengine.story.operator"
    private const val TYPE = "hollowengine.story.type"
    private const val CASE = "hollowengine.story.outcome"
    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")
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

    /** What the editor calls the type [name], qualified or simple. */
    fun typeTitle(name: String): String {
        val simple = StoryPinType.shortName(name).removeSuffix("?")
        return known("$TYPE.$simple") ?: readable(simple)
    }

    /**
     * What the editor calls the operator [id]: its own title, a value by the key of a command, the block of a type by
     * the name of the type.
     */
    fun operatorTitle(id: String): String {
        StoryOperators.find(id)?.title?.let { return it.lang }
        return when {
            id.startsWith(TypeOperators.ENUM) -> typeTitle(id.removePrefix(TypeOperators.ENUM))
            id.startsWith(TypeOperators.UNPACK) -> storyLang("operator.unpack", classTitle(id.removePrefix(TypeOperators.UNPACK)))
            id.startsWith(TypeOperators.PACK) -> storyLang("operator.pack", classTitle(id.removePrefix(TypeOperators.PACK)))
            ':' in id -> known(key(id)) ?: readable(id.substringAfter(':'))
            else -> known("$OPERATOR.$id") ?: readable(id)
        }
    }

    /** What the operator [id] reads, when that needs saying. */
    fun operatorDescription(id: String): String? {
        StoryOperators.find(id)?.description?.let { return it.lang }
        return if (':' in id && !isTypeOperator(id)) known(key(id) + ".description") else known("$OPERATOR.$id.description")
    }

    /** What a pin of an operator is called: pins of the same meaning share one name. */
    fun operatorPinTitle(name: String): String = known("$OPERATOR.pin.$name") ?: readable(name)

    /** What the editor calls [input] of [operator]: a parameter of a value as a parameter of a command is called. */
    fun operatorInputTitle(operator: StoryOperator, input: StoryOperatorInput): String {
        val param = input.param ?: return operatorPinTitle(input.name)
        if (operator.id.startsWith(TypeOperators.PACK)) {
            return fieldTitle(operator.id.removePrefix(TypeOperators.PACK), input.name)
        }
        if (isTypeOperator(operator.id)) return operatorPinTitle(input.name)
        return param.title?.lang ?: known("${key(operator.id)}.${input.name}") ?: known("$PARAM.${input.name}")
        ?: readable(input.name)
    }

    /** What [input] of [operator] takes, when that needs saying. */
    fun operatorInputHint(operator: StoryOperator, input: StoryOperatorInput): String? {
        val param = input.param
        if (param == null || isTypeOperator(operator.id)) return operatorPinHint(operator.id, input.name)
        val help = param.description?.lang ?: known("${key(operator.id)}.${input.name}.description")
        ?: known("$PARAM.${input.name}.description")
        val note = storyLang("param.optional").takeIf { param.optional }
        return listOfNotNull(help, note).joinToString("\n").ifEmpty { null }
    }

    /** What the editor calls [output] of [operator]; the parts of a type it takes apart by the names of its classes. */
    fun operatorOutputTitle(operator: StoryOperator, output: StoryOperatorOutput): String {
        if (!operator.id.startsWith(TypeOperators.UNPACK)) return operatorPinTitle(output.name)
        val type = operator.id.removePrefix(TypeOperators.UNPACK)
        val name = output.name
        if (name.startsWith("is__")) return storyLang("operator.unpack.is", classTitle("$type.${name.removePrefix("is__")}"))
        val case = name.substringBefore("__", "")
        if (case.isEmpty()) return fieldTitle(type, name)
        return "${classTitle("$type.$case")}: ${fieldTitle("$type.$case", name.substringAfter("__"))}"
    }

    private fun isTypeOperator(id: String) =
        id.startsWith(TypeOperators.ENUM) || id.startsWith(TypeOperators.UNPACK) || id.startsWith(TypeOperators.PACK)

    /** What the class [name] is called: a class of a sealed type under it, `MoveResult.Closest`, any other as a type. */
    private fun classTitle(name: String): String {
        val short = StoryPinType.shortName(name)
        return known("$CASE.$short") ?: if ('.' in short) readable(short.substringAfterLast('.')) else typeTitle(short)
    }

    /** What the editor calls the field [field] of the class [owner]. */
    private fun fieldTitle(owner: String, field: String): String =
        known("$CASE.${StoryPinType.shortName(owner)}.$field") ?: known("$PARAM.$field") ?: readable(field)

    /** What the input [name] of the operator [id] takes, when that needs saying. */
    fun operatorPinHint(id: String, name: String): String? = known("$OPERATOR.$id.$name.description")

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

    /** [id] in words: `target-unavailable`, `TARGET_UNAVAILABLE` and `TargetUnavailable` all read "Target unavailable". */
    private fun readable(id: String): String {
        val words = if (id.any(Char::isLowerCase)) id.replace(CAMEL, " ") else id.lowercase()
        return words.replace('-', ' ').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The operators [accepts] lets through, under the headings of their categories, for adding a value. */
    fun operatorItems(accepts: (StoryOperator) -> Boolean = { true }, add: (StoryNode) -> Unit): List<UiDropdownItem> =
        tree(operatorEntries(accepts, add))

    /** The commands [commands] and the operators [operators] let through, in one tree of their categories. */
    fun linkItems(
        commands: (StoryFunctionSignature) -> Boolean,
        operators: (StoryOperator) -> Boolean,
        add: (StoryNode) -> Unit,
    ): List<UiDropdownItem> = tree(commandEntries(commands, add) + operatorEntries(operators, add))

    private fun commandEntries(accepts: (StoryFunctionSignature) -> Boolean, add: (StoryNode) -> Unit) =
        (ActionCommands.commands.map { it.signature } + ComponentCommands.all).filter { !it.hidden && accepts(it) }
            .map { it.category to item(it, add) }

    private fun operatorEntries(accepts: (StoryOperator) -> Boolean, add: (StoryNode) -> Unit) =
        StoryOperators.all.filter(accepts).map { operator ->
            operatorCategory(operator.category) to UiDropdownItem(
                operatorTitle(operator.id),
                icon = operatorIcon(operator) ?: StoryNodeKind.VALUE.icon,
                iconColor = StoryCategories.tint(operatorColor(operator)),
            ) { add(StoryNode.Pure(StoryGraph.newId(), operator.id)) }
        }

    /**
     * The menu of [entries] keyed by category: a heading for each level of a category key, every level sorted by
     * name with its headings before its items. Entries with no category follow the headings.
     */
    fun tree(entries: List<Pair<String?, UiDropdownItem>>): List<UiDropdownItem> {
        val headings = LinkedHashMap<String, MutableList<UiDropdownItem>>()
        val children = LinkedHashMap<String?, MutableSet<String>>()
        for ((category, item) in entries) {
            if (category == null) continue
            headings.getOrPut(category) { mutableListOf() } += item
            var at: String = category
            while (true) {
                val parent = StoryCategories.parent(at)
                children.getOrPut(parent) { linkedSetOf() } += at
                at = parent ?: break
            }
        }

        fun level(parent: String?): List<UiDropdownItem> = children[parent].orEmpty().map { category ->
            StoryCategories.rank(category) to heading(category, level(category) + headings[category].orEmpty().sortedBy { it.label })
        }.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }

        return level(null) + entries.filter { it.first == null }.map { it.second }
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

    /**
     * A menu item per command and step of the flow, for adding a node of it; with [values], the operators too, each
     * under the heading of its category, so that reading a property sits next to the commands that change it.
     */
    fun items(add: (StoryNode) -> Unit, values: Boolean = false): List<UiDropdownItem> {
        val own = StoryBuiltins.all.map { it.category to item(it, add) } + listOf(
            StoryCategory.VARIABLES to kindItem(storyLang("kind.set"), StoryNodeKind.SET) {
                add(StoryNode.Assign(StoryGraph.newId(), ""))
            },
            StoryCategory.FLOW to kindItem(storyLang("kind.code"), StoryNodeKind.CODE) {
                add(StoryNode.Code(StoryGraph.newId(), ""))
            },
        )
        val commands = commandEntries({ true }, add)
        val operators = if (values) operatorEntries({ true }, add) else emptyList()
        return tree(own + commands + operators)
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
