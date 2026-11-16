package ru.hollowhorizon.hollowengine.client.ui.ide

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimeBridge
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the game panel sits, and how large the game renders for it.
 */
internal object HollowIdeGameViewport {
    private class PanelReport(val owner: Any, val bounds: UiRect)

    @Volatile
    private var panel: PanelReport? = null

    @Volatile
    private var windowMetrics: RuntimeBridge.GameViewportMetrics? = null

    @Volatile
    private var windowPass = false

    @Volatile
    private var windowWidth = 0

    @Volatile
    private var windowHeight = 0

    /** Called by a docked panel with its bounds; [owner] tells an old panel instance from its replacement. */
    fun report(owner: Any, bounds: UiRect) {
        panel = PanelReport(owner, bounds)
    }

    /** Withdraws [owner]'s bounds, unless another panel instance has reported since. */
    fun release(owner: Any) {
        if (panel?.owner === owner) panel = null
    }

    /** Whether a docked panel is showing the game, which is the only case the game is cut down for. */
    fun isEmbedded(): Boolean = panel != null

    fun windowWidth(): Int = windowWidth.takeIf { it > 0 } ?: Minecraft.getInstance().window.width

    fun windowHeight(): Int = windowHeight.takeIf { it > 0 } ?: Minecraft.getInstance().window.height

    /**
     * Makes the main game target match the physical pixels the panel occupies. Runs before anything
     * else in the frame, so tasks, ticks and input see the same framebuffer size as rendering does:
     * whatever vanilla sizes from the window outside rendering (post chains rebuilt by a resource
     * reload, the transparency chain, entity effects) is sized for the panel too.
     */
    fun beginFrame(minecraft: Minecraft) {
        sampleWindowSize(minecraft)
        val requested = requestedMetrics(minecraft)
        if (requested == null) {
            restoreWindowTarget(minecraft)
            return
        }

        val target = minecraft.mainRenderTarget
        val resized = target.width != requested.framebufferWidth() || target.height != requested.framebufferHeight()
        val metricsChanged = windowMetrics != requested
        windowMetrics = requested
        if (resized) {
            target.resize(requested.framebufferWidth(), requested.framebufferHeight(), Minecraft.ON_OSX)
            minecraft.gameRenderer.resize(requested.framebufferWidth(), requested.framebufferHeight())
        }
        if (metricsChanged || resized) {
            minecraft.screen?.resize(minecraft, requested.guiScaledWidth(), requested.guiScaledHeight())
        }
    }

    /** The finished frame goes onto the real window, and the editor is drawn over it at the window's size. */
    fun beginWindowPass() {
        windowPass = true
    }

    /**
     * After game has been blitted, the screen belongs to the editor: the game left the viewport
     * sized for the panel, and everything drawn from here on covers the whole window.
     */
    fun restoreWindowViewport() {
        if (windowMetrics == null) return
        RenderSystem.viewport(0, 0, windowWidth(), windowHeight())
    }

    fun endWindowPass() {
        windowPass = false
    }

    fun metrics(): RuntimeBridge.GameViewportMetrics? = windowMetrics

    fun isWindowPass(): Boolean = windowPass

    private fun sampleWindowSize(minecraft: Minecraft) {
        val width = IntArray(1)
        val height = IntArray(1)
        GLFW.glfwGetFramebufferSize(minecraft.window.window, width, height)
        if (width[0] > 0 && height[0] > 0) {
            windowWidth = width[0]
            windowHeight = height[0]
        }
    }

    private fun requestedMetrics(minecraft: Minecraft): RuntimeBridge.GameViewportMetrics? {
        if (!HollowIdeOverlay.expanded) return null
        val bounds = panel?.bounds ?: return null
        if (bounds.width <= 0f || bounds.height <= 0f) return null

        val framebufferScale = HollowIdeScale.factor()
        val width = (bounds.width * framebufferScale).roundToInt().coerceIn(1, windowWidth().coerceAtLeast(1))
        val height = (bounds.height * framebufferScale).roundToInt().coerceIn(1, windowHeight().coerceAtLeast(1))
        val guiScale = viewportGuiScale(minecraft, width, height)
        return RuntimeBridge.GameViewportMetrics(
            width,
            height,
            ceil(width / guiScale).toInt().coerceAtLeast(1),
            ceil(height / guiScale).toInt().coerceAtLeast(1),
            guiScale,
        )
    }

    private fun viewportGuiScale(minecraft: Minecraft, width: Int, height: Int): Double {
        val requested = minecraft.options.guiScale().get()
        var scale = 1
        while (scale != requested && scale < width && scale < height && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) {
            scale++
        }
        if (minecraft.isEnforceUnicode && scale % 2 != 0) scale++
        return scale.toDouble()
    }

    /**
     * Metrics are only ever dropped here, so the target, the post chains and the screen layout
     * always return to the window together with the window getters.
     */
    private fun restoreWindowTarget(minecraft: Minecraft) {
        if (windowMetrics == null) return
        windowMetrics = null
        minecraft.resizeDisplay()
    }

    fun imageRect(bounds: UiRect): UiRect? {
        val target = Minecraft.getInstance().mainRenderTarget
        if (target == null || target.width <= 0 || target.height <= 0) return null
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        val scale = min(bounds.width / target.width, bounds.height / target.height)
        val width = target.width * scale
        val height = target.height * scale
        return UiRect(
            x = bounds.x + (bounds.width - width) * 0.5f,
            y = bounds.y + (bounds.height - height) * 0.5f,
            width = width,
            height = height,
        )
    }

    fun imageRect(): UiRect? = panel?.bounds?.let(::imageRect)
}
