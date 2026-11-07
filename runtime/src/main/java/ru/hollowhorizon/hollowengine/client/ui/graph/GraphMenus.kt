package ru.hollowhorizon.hollowengine.client.ui.graph

import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.utils.lang

/**
 * How graphs behave, for the menu of an empty spot of any graph: snapping to the grid. It is one of
 * the [GraphPreferences], so it changes every graph at once.
 */
fun graphViewItems(): List<UiDropdownItem> = listOf(
    UiDropdownItem(
        text("snap_to_grid"),
        mark = UiMenuParts.check(GraphPreferences.snapToGrid),
        separatorBefore = true,
    ) { GraphPreferences.snapToGrid = !GraphPreferences.snapToGrid },
)

/**
 * Lining up and spacing out the [count] selected nodes; left out below two, since one node has nothing
 * to line up with.
 */
fun graphArrangeItem(
    count: Int,
    onAlign: (GraphAlignment) -> Unit,
    onDistribute: (horizontal: Boolean) -> Unit,
): UiDropdownItem? {
    if (count < 2) return null
    return UiDropdownItem(
        text("arrange"),
        mark = UiMenuParts.icon(ArrangeIcon),
        children = GraphAlignment.entries.map { alignment ->
            UiDropdownItem(
                text("align.${alignment.name.lowercase()}"),
                separatorBefore = alignment == GraphAlignment.TOP,
            ) { onAlign(alignment) }
        } + listOf(
            UiDropdownItem(text("distribute.horizontal"), enabled = count > 2, separatorBefore = true) { onDistribute(true) },
            UiDropdownItem(text("distribute.vertical"), enabled = count > 2) { onDistribute(false) },
        ),
    )
}

private fun text(name: String): String = "hollowengine.gui.graph.$name".lang

private const val ArrangeIcon = "hollowengine:textures/gui/icons/actions/align.svg"
