package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark
import ru.hollowhorizon.hollowengine.client.utils.lang

/** A context menu the editor has open, where it was opened on the screen. */
internal data class GraphMenu(val x: Float, val y: Float, val items: List<UiDropdownItem>)

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
        icon = category.icon(),
        children = kinds.mapIndexed { index, kind ->
            UiDropdownItem(
                kind.title(),
                icon = kind.displayIcon(),
                separatorBefore = index > 0 && kind.group != kinds[index - 1].group,
            ) { onPick(kind) }
        },
    )
}

/** The item that puts a reroute where a link was dropped, or on the link that was clicked. */
internal fun rerouteItem(onPick: () -> Unit) = UiDropdownItem(graphText("reroute"), icon = RerouteIcon, onClick = onPick)

/**
 * What the menu of a node offers: its preview, and unless it is the output, a copy and removal; then
 * [organize], the grouping and arranging of the selection it belongs to.
 */
internal fun nodeItems(
    node: ShaderGraphNode,
    kind: ShaderNodeType,
    onPreview: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    organize: List<UiDropdownItem>,
): List<UiDropdownItem> = buildList {
    if (kind.id != ShaderNodeLibrary.REROUTE) {
        val shown = kind.showsPreview(node)
        add(UiDropdownItem(graphText("show_preview"), mark = UiDropdownMark.CHECKBOX, checked = shown) { onPreview(!shown) })
    }
    if (kind.master == null) {
        add(UiDropdownItem(graphText("duplicate"), shortcut = HollowKeys.DUPLICATE.shortcut, separatorBefore = isNotEmpty(), onClick = onDuplicate))
        add(UiDropdownItem(graphText("delete_node"), shortcut = HollowKeys.DELETE.shortcut, onClick = onDelete))
    }
    organize.forEachIndexed { index, item -> add(if (index == 0) item.copy(separatorBefore = true) else item) }
}

/**
 * Grouping the selection: putting it in a new group, and, when it is [grouped], taking it out of its
 * groups or taking them apart; then [arrange], lining it up. Empty when there is nothing to do.
 */
internal fun organizeItems(
    canGroup: Boolean,
    grouped: Boolean,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
    onLeave: () -> Unit,
    arrange: UiDropdownItem?,
): List<UiDropdownItem> = listOfNotNull(
    UiDropdownItem(graphText("group_nodes"), icon = GroupIcon, shortcut = HollowKeys.GROUP.shortcut, onClick = onGroup).takeIf { canGroup },
    UiDropdownItem(graphText("leave_group"), onClick = onLeave).takeIf { grouped },
    UiDropdownItem(graphText("ungroup"), icon = UngroupIcon, shortcut = HollowKeys.UNGROUP.shortcut, onClick = onUngroup).takeIf { grouped },
    arrange,
)

/** What the menu of a group offers: opening or collapsing it, taking it apart, lining its nodes up, removing it with its nodes. */
internal fun groupItems(
    group: ShaderGraphGroup,
    onToggle: () -> Unit,
    onUngroup: () -> Unit,
    onDelete: () -> Unit,
    arrange: UiDropdownItem?,
): List<UiDropdownItem> = listOfNotNull(
    UiDropdownItem(graphText(if (group.collapsed) "expand_group" else "collapse_group"), onClick = onToggle),
    UiDropdownItem(graphText("ungroup"), icon = UngroupIcon, shortcut = HollowKeys.UNGROUP.shortcut, onClick = onUngroup),
    arrange,
    UiDropdownItem(graphText("delete_group_nodes"), separatorBefore = true, onClick = onDelete),
)

private const val RerouteIcon = "hollowengine:textures/gui/icons/actions/reroute.svg"
private const val GroupIcon = "hollowengine:textures/gui/icons/actions/group.svg"
private const val UngroupIcon = "hollowengine:textures/gui/icons/actions/ungroup.svg"

/** The values of a choice, each marked when it is the current one. */
internal fun choiceItems(option: ShaderOptionSpec, current: String, onPick: (String) -> Unit): List<UiDropdownItem> =
    option.values.map { value ->
        UiDropdownItem(
            optionValueTitle(option, value),
            mark = UiDropdownMark.RADIO,
            checked = value == current
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
