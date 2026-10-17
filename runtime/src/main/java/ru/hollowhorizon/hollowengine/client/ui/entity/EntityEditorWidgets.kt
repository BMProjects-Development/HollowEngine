package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos

import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.rotate
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

internal object EntityEditorIcons {
    const val ADD = "hollowengine:textures/gui/icons/add.svg"
    const val REMOVE = "hollowengine:textures/gui/icons/remove.svg"
    const val SEARCH = "hollowengine:textures/gui/icons/search.svg"
    const val RELOAD = "hollowengine:textures/gui/icons/reload.svg"
    const val ZOOM_RESET = "hollowengine:textures/gui/icons/zoom_out.svg"
    const val SCRIPT = "hollowengine:textures/gui/icons/file_kts.svg"
    const val COMPONENT = "hollowengine:textures/gui/icons/box.svg"
    const val CLOSE = "hollowengine:textures/gui/icons/cross.svg"
    const val STATE = "hollowengine:textures/gui/icons/state.svg"
}

internal object EntityEditorLang {
    private const val ROOT = "hollowengine.gui.entity_editor."

    val title: String get() = (ROOT + "title").lang
    val components: String get() = (ROOT + "components").lang
    val scripts: String get() = (ROOT + "scripts").lang
    val addComponent: String get() = (ROOT + "add_component").lang
    val add: String get() = (ROOT + "add").lang
    val remove: String get() = (ROOT + "remove").lang
    val refresh: String get() = (ROOT + "refresh").lang
    val search: String get() = (ROOT + "search").lang
    val searchHint: String get() = (ROOT + "search_hint").lang
    val emptyList: String get() = (ROOT + "empty_list").lang
    val nothingFound: String get() = (ROOT + "nothing_found").lang
    val virtual: String get() = (ROOT + "virtual").lang
    val virtualHint: String get() = (ROOT + "virtual_hint").lang
    val attached: String get() = (ROOT + "attached").lang
    val available: String get() = (ROOT + "available").lang
    val noScripts: String get() = (ROOT + "no_scripts").lang
    val noSuitableScripts: String get() = (ROOT + "no_suitable_scripts").lang
    val attach: String get() = (ROOT + "attach").lang
    val detach: String get() = (ROOT + "detach").lang
    val pick: String get() = (ROOT + "pick").lang
    val noPreview: String get() = (ROOT + "no_preview").lang
    val allAdded: String get() = (ROOT + "all_added").lang
    val close: String get() = (ROOT + "close").lang
    val inventory: String get() = (ROOT + "inventory").lang
    val inventoryHint: String get() = (ROOT + "inventory_hint").lang
    val equipment: String get() = (ROOT + "equipment").lang
    val carried: String get() = (ROOT + "carried").lang
    val statComponents: String get() = (ROOT + "stat_components").lang
    val statScripts: String get() = (ROOT + "stat_scripts").lang
    val statHealth: String get() = (ROOT + "stat_health").lang
    val autoRotate: String get() = (ROOT + "auto_rotate").lang
    val resetView: String get() = (ROOT + "reset_view").lang
    val busy: String get() = (ROOT + "busy").lang
    val playerItems: String get() = (ROOT + "player_items").lang
}

/** Spins while the editor waits for the server to answer. */
@Composable
internal fun BusySpinner() {
    val frame by produceState(0) {
        while (true) {
            withFrameNanos { }
            value++
        }
    }
    Image(
        EntityEditorIcons.RELOAD,
        tags = listOf("ee-busy"),
        modifier = Modifier.rotate(0f, 0f, (frame * 4f) % 360f).tooltipOnHover(EntityEditorLang.busy),
    )
}
