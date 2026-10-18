package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.launch
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.ui.panels.UiPanelEvent

/**
 * Fires when a player uses [panel] hung on the bound entity, or any of its panels when none is named: the content of
 * the panel sent a payload with `send { }`. On the server, as `onInteract` is:
 *
 * ```kotlin
 * onPanelEvent("mypack:terminal") { event ->
 *     if (event.payload.getString("action") == "click") data.update(Clicks) { it + 1 }
 * }
 * ```
 */
context(entity: Entity, script: NodeScript)
fun onPanelEvent(panel: String? = null, block: suspend (UiPanelEvent) -> Unit) {
    UiPanelEvent.subscribe(script) { event ->
        if (event.entity === entity && (panel == null || event.panel == panel)) script.launch { block(event) }
    }
}
