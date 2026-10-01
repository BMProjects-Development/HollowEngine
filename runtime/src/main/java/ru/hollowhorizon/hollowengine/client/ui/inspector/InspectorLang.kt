package ru.hollowhorizon.hollowengine.client.ui.inspector

import ru.hollowhorizon.hollowengine.client.utils.lang

/** Wording shared by every inspector, whichever editor opened it. */
object InspectorLang {
    private const val ROOT = "hollowengine.gui.inspector."

    val nothingSelected: String get() = (ROOT + "nothing_selected").lang
    val notSet: String get() = (ROOT + "not_set").lang
    val emptyList: String get() = (ROOT + "empty_list").lang
    val add: String get() = (ROOT + "add").lang
    val remove: String get() = (ROOT + "remove").lang
    val pick: String get() = (ROOT + "pick").lang
    val search: String get() = (ROOT + "search").lang
    val nothingFound: String get() = (ROOT + "nothing_found").lang
    val close: String get() = (ROOT + "close").lang

    fun assetMissing(path: String): String = (ROOT + "asset_missing").lang.format(path)
    fun useAsset(path: String): String = (ROOT + "asset_use").lang.format(path)

    fun unsupported(type: String): String = (ROOT + "unsupported").lang.format(type)
}

internal object InspectorIcons {
    const val ADD = "hollowengine:textures/gui/icons/add.svg"
    const val REMOVE = "hollowengine:textures/gui/icons/remove.svg"
    const val FOLDER = "hollowengine:textures/gui/icons/folder.svg"
    const val HELP = "hollowengine:textures/gui/icons/info.svg"
    const val SEARCH = "hollowengine:textures/gui/icons/search.svg"
    const val CLOSE = "hollowengine:textures/gui/icons/cross.svg"
}
