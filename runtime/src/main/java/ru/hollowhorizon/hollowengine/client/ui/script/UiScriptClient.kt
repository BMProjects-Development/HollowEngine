package ru.hollowhorizon.hollowengine.client.ui.script

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.slots.ClientSlots
import ru.hollowhorizon.hollowengine.client.utils.mc
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.ui.UiData
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry
import ru.hollowhorizon.hollowengine.common.ui.hud.HudLayerRegistry
import ru.hollowhorizon.hollowengine.common.ui.net.CloseUiPacket
import ru.hollowhorizon.hollowengine.common.ui.net.UiEventPacket

/**
 * Client end of the UI protocol: applies what a server session asks for, and reports back what the
 * player did. Screens and overlays are two different hosts, so this keeps track of which one owns
 * the session in order to route patches and closes to the right place.
 */
object UiScriptClient {
    private var screenSessionId: Int? = null
    private var wasInLevel = false

    fun openScreen(sessionId: Int, screen: ResourceLocation, state: CompoundTag) {
        val definition = UiDefinitionRegistry.screen(screen) ?: run {
            HollowEngine.LOGGER.warn("Server opened unknown UI screen {}", screen)
            return
        }
        screenSessionId = sessionId
        mc.setScreen(UiScriptScreen(definition, UiData(state), sessionId))
    }

    fun showOverlay(sessionId: Int, overlay: ResourceLocation, state: CompoundTag) {
        UiScriptHudHost.show(overlay, sessionId, state)
    }

    /** Opens a surface that picks its own host; see [UiAdaptiveSurfaces]. */
    fun openSurface(sessionId: Int, surface: ResourceLocation, state: CompoundTag) {
        UiAdaptiveSurfaces.open(sessionId, surface, state)
    }

    fun applyPatch(sessionId: Int, patch: CompoundTag, removed: List<String>) {
        if (UiAdaptiveSurfaces.applyPatch(sessionId, patch, removed)) return
        activeScreen(sessionId)?.data?.applyPatch(patch, removed)
        UiScriptHudHost.applyPatch(sessionId, patch, removed)
    }

    fun close(sessionId: Int) {
        if (UiAdaptiveSurfaces.close(sessionId)) {
            ClientSlots.close(sessionId)
            return
        }
        val screen = activeScreen(sessionId)
        if (screen != null) {
            screenSessionId = null
            if (!screen.dismiss()) mc.setScreen(null)
        }
        UiScriptHudHost.closeSession(sessionId)
        ClientSlots.close(sessionId)
    }

    fun setHiddenLayers(layers: List<ResourceLocation>) {
        HudLayerRegistry.setHidden(layers, HudLayerRegistry.ServerOwner)
    }

    /** Sends a payload produced by scripted UI back to the session that opened it. */
    fun send(sessionId: Int, payload: CompoundTag) {
        UiEventPacket(sessionId, payload).send()
    }

    /** Tells the server the player dismissed the screen, so it can drop the session. */
    fun notifyClosed(sessionId: Int) {
        if (screenSessionId == sessionId) screenSessionId = null
        ClientSlots.close(sessionId)
        CloseUiPacket(sessionId).send()
    }

    /** Forgets every server-driven UI; used when leaving a world. */
    fun reset() {
        UiAdaptiveSurfaces.reset()
        screenSessionId = null
        UiScriptHudHost.reload()
        HudLayerRegistry.releaseAll(HudLayerRegistry.ServerOwner)
        ClientSlots.clear()
    }

    /**
     * Drops server-driven UI once the player has left the world. There is no disconnect hook in the
     * bridge, and adding one would mean touching both bootstraps, so the transition is read off the
     * client tick instead.
     */
    internal fun onLevelPresenceChanged(hasLevel: Boolean) {
        if (hasLevel) {
            wasInLevel = true
            return
        }
        if (!wasInLevel) return
        wasInLevel = false
        reset()
    }

    private fun activeScreen(sessionId: Int): UiScriptScreen? =
        (mc.screen as? UiScriptScreen)?.takeIf { screenSessionId == sessionId }
}

@SubscribeEvent
fun onClientTickResetUi(event: TickEvent.Client) {
    UiScriptClient.onLevelPresenceChanged(event.minecraft.level != null)
    UiScriptHudHost.tick()
}
