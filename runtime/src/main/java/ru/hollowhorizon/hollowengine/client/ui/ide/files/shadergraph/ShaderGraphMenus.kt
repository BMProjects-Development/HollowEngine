package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * The add menu: a submenu per category, in the order the kinds were registered, with the kinds of one
 * group together and a line between groups. Kinds that read what [target] does not offer are left out.
 */
internal fun addNodeItems(
    target: ShaderTarget,
    accepts: (ShaderNodeType) -> Boolean = { true },
    onPick: (ShaderNodeType) -> Unit,
): List<UiDropdownItem> = ShaderNodeCategory.entries.filter { it != ShaderNodeCategory.OUTPUT }.mapNotNull { category ->
    val kinds = ShaderNodeTypes.all.filter {
        it.category == category && it.master == null && it.id != ShaderNodeLibrary.REROUTE &&
                target.inputs.containsAll(it.reads) && accepts(it)
    }
    if (kinds.isEmpty()) return@mapNotNull null
    UiDropdownItem(
        graphText("category.${category.name.lowercase()}"),
        mark = UiMenuParts.icon(category.icon()),
        children = kinds.mapIndexed { index, kind ->
            UiDropdownItem(
                kind.title(),
                mark = UiMenuParts.icon(kind.displayIcon()),
                separatorBefore = index > 0 && kind.group != kinds[index - 1].group,
            ) { onPick(kind) }
        },
    )
}

/** The values of a choice, each marked when it is the current one. */
internal fun choiceItems(option: ShaderOptionSpec, current: String, onPick: (String) -> Unit): List<UiDropdownItem> =
    option.values.map { value ->
        UiDropdownItem(
            optionValueTitle(option, value),
            mark = UiMenuParts.radio(value == current)
        ) { onPick(value) }
    }

/** The first input of a node of this kind a value of [type] can be linked into, one of that very type first. */
internal fun ShaderNodeType.inputFor(type: ShaderType, node: ShaderGraphNode = prototype): ShaderPinSpec? {
    val pins = inputs(node)
    return pins.firstOrNull { it.type.fixed == type } ?: pins.firstOrNull { it.type.accepts(type) }
}

/** The first output of this kind that can be linked into a pin of [pin]. */
internal fun ShaderNodeType.outputFor(pin: ShaderPinType): ShaderOutputSpec? = outputs.firstOrNull { output ->
    output.type.fixed?.let(pin::accepts) ?: (output.typeOf != null || pin != ShaderPinType.TEXTURE)
}

/** The translated name of an option, or the option made readable. */
internal fun optionTitle(option: ShaderOptionSpec): String =
    option.titleKey.lang.takeIf { it != option.titleKey } ?: readable(option.name)

internal fun optionValueTitle(option: ShaderOptionSpec, value: String): String {
    val key = option.valueKey(value)
    return key.lang.takeIf { it != key } ?: readable(value)
}

private fun readable(name: String): String = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** The translated title of a kind, or its id made readable when nothing translates it. */
internal fun ShaderNodeType.title(): String {
    val translated = titleKey.lang
    if (translated != titleKey) return translated
    return readable(id.substringAfterLast('/'))
}
