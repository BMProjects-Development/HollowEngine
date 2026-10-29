package ru.hollowhorizon.hollowengine.client.ui.script

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.script.UiScriptHudHost.dropFinishedExits
import ru.hollowhorizon.hollowengine.common.ui.HudPlacement
import ru.hollowhorizon.hollowengine.common.ui.OverlayAnchor
import ru.hollowhorizon.hollowengine.common.ui.ScreenLevel
import ru.hollowhorizon.hollowengine.common.ui.UiData
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry
import ru.hollowhorizon.hollowengine.common.ui.UiOverlayDefinition
import ru.hollowhorizon.hollowengine.common.ui.hud.HudLayerRegistry
import java.util.EnumSet

/**
 * Draws the scripted HUD layers that are currently shown, and routes input to the ones that opted
 * in. A layer in the HUD is drawn from the Pre/Post hook of the vanilla layer it is anchored to, which
 * is what lets a scripted overlay sit *between* two vanilla ones without the engine owning a fixed list
 * of slots; one on a screen is drawn from the screen's hooks at its [ScreenLevel].
 */
object UiScriptHudHost {
    private val shown = LinkedHashMap<ResourceLocation, ShownOverlay>()
    private val drawnLevels = EnumSet.noneOf(ScreenLevel::class.java)

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

    /** Draws every overlay that sits in the HUD at [layer]/[placement] right now and is not suppressed. */
    fun render(layer: ResourceLocation, placement: HudPlacement, nowNanos: Long) {
        dropFinishedExits()
        if (shown.isEmpty()) return
        val anchor = OverlayAnchor.HudLayer(layer, placement)
        val screen = Minecraft.getInstance().screen?.javaClass
        shown.values.forEach { overlay ->
            if (overlay.definition.anchorFor(screen) == anchor) renderOne(overlay, nowNanos)
        }
    }

    /** Starts a frame of the open screen; each [ScreenLevel] is drawn once in it. */
    fun beginScreenFrame() {
        drawnLevels.clear()
    }

    /**
     * Draws the overlays on top of [screen] up to [level], the levels below it included if the screen skipped
     * their hook: a screen without a background still gets its [ScreenLevel.BACKGROUND] overlays.
     */
    fun renderOnScreen(screen: Screen, level: ScreenLevel, graphics: GuiGraphics, nowNanos: Long) {
        dropFinishedExits()
        for (current in ScreenLevel.entries) {
            if (current > level) break
            if (!drawnLevels.add(current) || shown.isEmpty()) continue
            // What the screen batched so far lies under the overlay, so it goes out first.
            graphics.flush()
            shown.values.forEach { overlay ->
                val anchor = overlay.definition.anchorFor(screen.javaClass)
                if (anchor is OverlayAnchor.OnScreen && anchor.level == current) renderOne(overlay, nowNanos)
            }
        }
    }

    fun handleMouseMove(x: Float, y: Float): Boolean = dispatch(x, y) { it.surface.mouseMoved(x, y) }

    fun handleMouseButton(x: Float, y: Float, button: Int, action: Int): Boolean =
        dispatch(x, y) { it.surface.mousePressed(x, y, button, action) }

    fun handleMouseScroll(x: Float, y: Float, scrollX: Double, scrollY: Double): Boolean =
        dispatch(x, y) { it.surface.mouseScrolled(x, y, scrollX, scrollY) }

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean =
        dispatch(null, null) { it.surface.keyPressed(key, scanCode, action, modifiers) }

    fun handleChar(codePoint: Int, modifiers: Int): Boolean =
        dispatch(null, null) { it.surface.charTyped(codePoint, modifiers) }

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
     * Dispatches an input action to the most recently shown interactive overlay that consumes it. [x] and [y] are
     * the pointer, null for keys.
     */
    private inline fun dispatch(x: Float?, y: Float?, action: (ShownOverlay) -> Boolean): Boolean {
        if (shown.isEmpty()) return false
        val screen = Minecraft.getInstance().screen
        for (overlay in shown.values.reversed()) {
            if (!overlay.definition.isInteractive || !takesInput(overlay.definition, screen, x, y)) continue
            if (action(overlay)) return true
        }
        return false
    }

    private fun takesInput(definition: UiOverlayDefinition, screen: Screen?, x: Float?, y: Float?): Boolean {
        val anchor = definition.anchorFor(screen?.javaClass)
        if (screen == null) return anchor != null
        if (anchor !is OverlayAnchor.OnScreen) return false
        if (anchor.level != ScreenLevel.BACKGROUND) return true
        return x != null && y != null && !covers(screen, x, y)
    }

    /** Whether [screen] has something of its own to click at [x], [y]. */
    private fun covers(screen: Screen, x: Float, y: Float): Boolean {
        if (screen.getChildAt(x.toDouble(), y.toDouble()).isPresent) return true
        if (screen !is AbstractContainerScreen<*>) return false
        return x >= screen.leftPos && x < screen.leftPos + screen.imageWidth &&
                y >= screen.topPos && y < screen.topPos + screen.imageHeight
    }
}
