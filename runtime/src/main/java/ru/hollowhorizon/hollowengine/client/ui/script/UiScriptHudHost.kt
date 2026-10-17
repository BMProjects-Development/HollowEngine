package ru.hollowhorizon.hollowengine.client.ui.script

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.script.UiScriptHudHost.dropFinishedExits
import ru.hollowhorizon.hollowengine.common.ui.HudPlacement
import ru.hollowhorizon.hollowengine.common.ui.UiData
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry
import ru.hollowhorizon.hollowengine.common.ui.UiOverlayDefinition
import ru.hollowhorizon.hollowengine.common.ui.hud.HudLayerRegistry

/**
 * Draws the scripted HUD layers that are currently shown, and routes input to the ones that opted
 * in. Each layer is anchored to a vanilla layer and drawn from that layer's Pre/Post hook, which is
 * what lets a scripted overlay sit *between* two vanilla ones without the engine owning a fixed list
 * of slots. Overlays flagged `aboveScreens` also draw over an open screen.
 */
object UiScriptHudHost {
    private val shown = LinkedHashMap<ResourceLocation, ShownOverlay>()

    private class ShownOverlay(
        val definition: UiOverlayDefinition,
        val surface: UiScriptSurface,
        val sessionId: Int?,
    )

    /**
     * Shows an overlay. [sessionId] is set when a server session owns it, in which case its data is
     * fed by server patches and its events are routed back to that session.
     */
    fun show(
        definition: UiOverlayDefinition,
        sessionId: Int? = null,
        initialState: CompoundTag = CompoundTag(),
    ) = show(definition, sessionId, UiData(initialState))

    /**
     * Shows an overlay around a document that already exists. An adaptive surface switching hosts
     * hands its own [UiData] over this way, so nothing it had is lost on the way across.
     */
    fun show(
        definition: UiOverlayDefinition,
        sessionId: Int?,
        data: UiData,
    ) {
        onRenderThread {
            hideNow(definition.id)
            lateinit var surface: UiScriptSurface
            surface = UiScriptSurface(
                content = definition.content,
                data = data,
                sessionId = sessionId,
                interactive = definition.isInteractive,
                rebuildEveryFrame = definition.rebuildEveryFrame,
                exitDuration = definition.exitDuration,
                onSend = sessionId?.let { id -> { payload -> UiScriptClient.send(id, payload) } },
                onClose = { hide(definition.id, surface) },
            )
            shown[definition.id] = ShownOverlay(definition, surface, sessionId)
        }
    }

    fun show(id: ResourceLocation, sessionId: Int? = null, initialState: CompoundTag = CompoundTag()) {
        val definition = UiDefinitionRegistry.overlay(id) ?: run {
            HollowEngine.LOGGER.warn("No UI overlay declared with id {}", id)
            return
        }
        show(definition, sessionId, initialState)
    }

    fun hide(id: ResourceLocation) {
        hide(id, null)
    }

    fun isShown(id: ResourceLocation): Boolean = id in shown

    fun applyPatch(sessionId: Int, patch: CompoundTag, removed: Collection<String>) {
        onRenderThread {
            shown.values.firstOrNull { it.sessionId == sessionId }?.surface?.data?.applyPatch(patch, removed)
        }
    }

    fun closeSession(sessionId: Int) {
        onRenderThread {
            shown.entries
                .filter { it.value.sessionId == sessionId }
                .map { it.key }
                .forEach { id -> beginExit(id) }
        }
    }

    /**
     * Drops the overlays whose exit animation has played out.
     */
    fun tick() = dropFinishedExits()

    /**
     * Drops every overlay whose exit window has run out, before anything draws it.
     */
    private fun dropFinishedExits() {
        if (shown.isEmpty()) return
        onRenderThread {
            val overlays = shown.values.iterator()
            while (overlays.hasNext()) {
                val overlay = overlays.next()
                if (!overlay.surface.isExitFinished) continue
                overlays.remove()
                overlay.surface.dispose()
            }
        }
    }

    /** Draws every overlay anchored at [anchor]/[placement] that is not currently suppressed. */
    fun render(anchor: ResourceLocation, placement: HudPlacement, nowNanos: Long) {
        dropFinishedExits()
        if (shown.isEmpty()) return
        val screenOpen = Minecraft.getInstance().screen != null
        shown.values.forEach { overlay ->
            if (overlay.definition.anchor != anchor || overlay.definition.placement != placement) return@forEach
            if (screenOpen && overlay.definition.aboveScreens) return@forEach
            renderOne(overlay, nowNanos)
        }
    }

    /** Draws the overlays that opted to sit on top of an open screen. */
    fun renderAboveScreens(nowNanos: Long) {
        dropFinishedExits()
        if (shown.isEmpty()) return
        shown.values.forEach { overlay ->
            if (overlay.definition.aboveScreens) renderOne(overlay, nowNanos)
        }
    }

    fun handleMouseMove(x: Float, y: Float): Boolean = dispatch { it.surface.mouseMoved(x, y) }

    fun handleMouseButton(x: Float, y: Float, button: Int, action: Int): Boolean =
        dispatch { it.surface.mousePressed(x, y, button, action) }

    fun handleMouseScroll(x: Float, y: Float, scrollX: Double, scrollY: Double): Boolean =
        dispatch { it.surface.mouseScrolled(x, y, scrollX, scrollY) }

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean =
        dispatch { it.surface.keyPressed(key, scanCode, action, modifiers) }

    fun handleChar(codePoint: Int, modifiers: Int): Boolean =
        dispatch { it.surface.charTyped(codePoint, modifiers) }

    /**
     * Drops every shown overlay and brings back the ones scripts declare as auto-showing. Called
     * after scripts recompile, when the old composables no longer correspond to any live definition.
     */
    fun reload() {
        onRenderThread {
            hideAllNow()
            UiDefinitionRegistry.allOverlays.filter { it.autoShow }.forEach { definition ->
                show(definition)
            }
        }
    }

    fun hideAll() {
        onRenderThread(::hideAllNow)
    }

    /** Drops an overlay at once, without its exit animation: a switch between hosts has none to play. */
    fun drop(id: ResourceLocation) {
        onRenderThread { hideNow(id) }
    }

    private fun hide(id: ResourceLocation, expectedSurface: UiScriptSurface?) {
        onRenderThread { beginExit(id, expectedSurface) }
    }

    /**
     * Starts the overlay's exit animation. [dropFinishedExits] takes it away once that has played
     * out or drops it right away when it declares no exit duration.
     */
    private fun beginExit(id: ResourceLocation, expectedSurface: UiScriptSurface? = null) {
        val overlay = shown[id] ?: return
        if (expectedSurface != null && overlay.surface !== expectedSurface) return
        if (!overlay.surface.dismiss()) hideNow(id, expectedSurface)
    }

    private fun hideNow(id: ResourceLocation, expectedSurface: UiScriptSurface? = null) {
        val overlay = shown[id] ?: return
        if (expectedSurface != null && overlay.surface !== expectedSurface) return
        shown.remove(id)
        overlay.surface.dispose()
    }

    private fun hideAllNow() {
        val surfaces = shown.values.map { it.surface }
        shown.clear()
        surfaces.forEach(UiScriptSurface::dispose)
    }

    private inline fun onRenderThread(crossinline action: () -> Unit) {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            action()
        } else {
            RenderSystem.recordRenderCall { action() }
        }
    }

    private fun renderOne(overlay: ShownOverlay, nowNanos: Long) {
        if (HudLayerRegistry.isHidden(overlay.definition.id)) return
        runCatching { overlay.surface.render(nowNanos) }.onFailure { error ->
            HollowEngine.LOGGER.error("Failed to render UI overlay {}", overlay.definition.id, error)
            drop(overlay.definition.id)
        }
    }

    /**
     * Dispatches an input action to the most recently shown interactive overlay that consumes it.
     */
    private inline fun dispatch(action: (ShownOverlay) -> Boolean): Boolean {
        if (shown.isEmpty()) return false
        val screenOpen = Minecraft.getInstance().screen != null
        for (overlay in shown.values.reversed()) {
            if (!overlay.definition.isInteractive) continue
            if (screenOpen && !overlay.definition.aboveScreens) continue
            if (action(overlay)) return true
        }
        return false
    }
}
