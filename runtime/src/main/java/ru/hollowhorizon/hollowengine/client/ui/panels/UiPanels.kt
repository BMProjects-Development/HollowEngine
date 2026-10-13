package ru.hollowhorizon.hollowengine.client.ui.panels

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent

/**
 * The panels shown in the world: drawn into their textures at the start of a frame, outside of drawing the world,
 * and given the pointer of the crosshair, the mouse buttons and, while the player types into one, the keyboard.
 */
@ClientOnly
internal object UiPanels {
    private val live = LinkedHashSet<UiPanelInstance>()

    /** The panel the crosshair points at, if any. */
    private var hovered: UiPanelInstance? = null

    fun add(panel: UiPanelInstance) {
        live += panel
    }

    fun remove(panel: UiPanelInstance) {
        live -= panel
        if (hovered === panel) hovered = null
    }

    @SubscribeEvent
    fun onRenderTick(event: RenderTickEvent.Pre) {
        if (live.isEmpty()) return
        val frame = TickHandler.renderFrame
        val now = System.nanoTime()
        point()
        live.forEach { panel -> if (frame - panel.seenFrame <= SEEN_FRAMES) panel.render(now) }
    }

    /** Points the crosshair at the nearest reachable panel, unless a block or entity hides it. */
    private fun point() {
        val minecraft = Minecraft.getInstance()
        val playing = minecraft.screen == null && minecraft.player != null
        val camera = minecraft.gameRenderer.mainCamera
        val eye = camera.position
        val look = Vector3f(camera.lookVector)
        val source = minecraft.cameraEntity
        val ray = source?.let { UiPanelRaycast(it, eye, Vec3(look.x.toDouble(), look.y.toDouble(), look.z.toDouble())) }
        val frame = TickHandler.renderFrame

        var nearest: UiPanelInstance? = null
        var nearestDistance = Double.MAX_VALUE
        var nearestPoint: Pair<Float, Float>? = null
        if (playing) live.forEach { panel ->
            if (!panel.interactive || frame - panel.seenFrame > SEEN_FRAMES) return@forEach
            val placement = panel.worldPlacement ?: return@forEach
            val hit = UiPanelPicking.hitOf(placement, eye.x, eye.y, eye.z, look) ?: return@forEach
            val (distance, at) = hit
            if (distance > panel.reach || distance >= nearestDistance) return@forEach
            if (ray == null || ray.isOccluded(distance)) return@forEach
            nearest = panel
            nearestDistance = distance
            nearestPoint = at.first * panel.definition.width to at.second * panel.definition.height
        }

        val previous = hovered
        if (previous != null && previous !== nearest) previous.point(null)
        hovered = nearest
        nearest?.let { panel -> panel.point(nearestPoint) }
    }

    /** A mouse button while playing: the hovered panel takes it instead of the world. */
    fun handleMouseButton(button: Int, action: Int): Boolean {
        if (action == GLFW.GLFW_PRESS) point()
        val panel = hovered ?: return false
        return when (action) {
            GLFW.GLFW_PRESS -> panel.press(button)
            GLFW.GLFW_RELEASE -> panel.release(button)
            else -> false
        }
    }

    /** The wheel while playing: the hovered panel scrolls instead of the hotbar. */
    fun handleMouseScroll(scrollX: Double, scrollY: Double): Boolean {
        point()
        return hovered?.scroll(scrollX, scrollY) == true
    }

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean =
        hovered?.key(key, scanCode, action, modifiers) == true

    fun handleChar(codePoint: Int, modifiers: Int): Boolean = hovered?.char(codePoint, modifiers) == true

    /** How many frames a panel stays drawn and usable after the world last drew it. */
    private const val SEEN_FRAMES = 2L
}
