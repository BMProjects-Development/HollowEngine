package ru.hollowhorizon.hollowengine.client.ui.panels

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.HitResult
import org.joml.Matrix4f
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

    /** Points the crosshair at the nearest panel it meets within that panel's reach, in front of any block. */
    private fun point() {
        val minecraft = Minecraft.getInstance()
        val playing = minecraft.screen == null && minecraft.player != null
        val camera = minecraft.gameRenderer.mainCamera
        val eye = camera.position
        val look = Vector3f(camera.lookVector)
        val block = minecraft.hitResult?.takeIf { it.type == HitResult.Type.BLOCK }?.location?.distanceTo(eye)
        val frame = TickHandler.renderFrame

        var nearest: UiPanelInstance? = null
        var nearestDistance = Double.MAX_VALUE
        var nearestPoint: Pair<Float, Float>? = null
        if (playing) live.forEach { panel ->
            if (!panel.interactive || frame - panel.seenFrame > SEEN_FRAMES) return@forEach
            val placement = panel.worldPlacement ?: return@forEach
            val hit = hitOf(placement, eye.x, eye.y, eye.z, look) ?: return@forEach
            val (distance, at) = hit
            if (distance > panel.reach || distance >= nearestDistance) return@forEach
            if (block != null && block < distance - BLOCK_MARGIN) return@forEach
            nearest = panel
            nearestDistance = distance
            nearestPoint = at.first * panel.definition.width to at.second * panel.definition.height
        }

        val previous = hovered
        if (previous != null && previous !== nearest) previous.point(null)
        hovered = nearest
        nearest?.let { panel -> panel.point(nearestPoint) }
    }

    /**
     * Where the ray from the eye meets the panel [placement] takes the unit square to: how far, and where across and
     * down the panel, from 0 to 1; null when it misses, meets the panel from behind or points away.
     */
    private fun hitOf(placement: Matrix4f, x: Double, y: Double, z: Double, look: Vector3f): Pair<Double, Pair<Float, Float>>? {
        val inverse = Matrix4f(placement).invert()
        val origin = inverse.transformPosition(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()))
        val direction = inverse.transformDirection(Vector3f(look))
        if (origin.z <= 0f || direction.z >= 0f) return null
        val t = -origin.z / direction.z
        val u = origin.x + direction.x * t
        val v = origin.y + direction.y * t
        if (u < -0.5f || u > 0.5f || v < -0.5f || v > 0.5f) return null
        val world = placement.transformPosition(Vector3f(u, v, 0f))
        val distance = Math.sqrt(
            (world.x - x) * (world.x - x) + (world.y - y) * (world.y - y) + (world.z - z) * (world.z - z)
        )
        return distance to ((u + 0.5f) to (0.5f - v))
    }

    /** A mouse button while playing: the hovered panel takes it instead of the world. */
    fun handleMouseButton(button: Int, action: Int): Boolean {
        val panel = hovered ?: return false
        return when (action) {
            GLFW.GLFW_PRESS -> panel.press(button)
            GLFW.GLFW_RELEASE -> panel.release(button)
            else -> false
        }
    }

    /** The wheel while playing: the hovered panel scrolls instead of the hotbar. */
    fun handleMouseScroll(scrollX: Double, scrollY: Double): Boolean = hovered?.scroll(scrollX, scrollY) == true

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean =
        hovered?.key(key, scanCode, action, modifiers) == true

    fun handleChar(codePoint: Int, modifiers: Int): Boolean = hovered?.char(codePoint, modifiers) == true

    /** How many frames a panel stays drawn and usable after the world last drew it. */
    private const val SEEN_FRAMES = 2L

    /** How far behind a block a panel may be and still be pointed at: the panel usually lies on one. */
    private const val BLOCK_MARGIN = 0.05
}
