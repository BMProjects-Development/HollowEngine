package ru.hollowhorizon.hollowengine.client.ui.widgets

/** An item a search of a menu found, and the submenus it sits in, joined for showing beside it. */
data class UiMenuMatch(val item: UiDropdownItem, val path: String)

/** Finds what can be picked in a menu, through every submenu, by the words typed. */
object UiMenuSearch {
    private const val LIMIT = 60
    private const val PATH_SEPARATOR = " › "

    /**
     * The items every word of [query] is found in, by label or by the submenus around them: those whose label starts
     * with the query first, then those whose label has it, then the rest.
     */
    fun find(items: List<UiDropdownItem>, query: String): List<UiMenuMatch> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = query.trim().lowercase()
        return leaves(items, emptyList())
            .filter { (item, path) ->
                val text = (path + item.label).joinToString(" ").lowercase()
                words.all { it in text }
            }
            .sortedBy { (item, _) ->
                val label = item.label.lowercase()
                when {
                    label.startsWith(whole) -> 0
                    whole in label -> 1
                    else -> 2
                }
            }
            .take(LIMIT)
            .map { (item, path) -> UiMenuMatch(item, path.joinToString(PATH_SEPARATOR)) }
    }

    /** Every item that does something when picked, with the labels of the submenus it is in. */
    private fun leaves(items: List<UiDropdownItem>, path: List<String>): List<Pair<UiDropdownItem, List<String>>> =
        items.filter { it.enabled && it.body == null }.flatMap { item ->
            val children = item.children
            if (children == null) listOf(item to path) else leaves(children, path + item.label)
        }
}
