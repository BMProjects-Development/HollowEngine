package ru.hollowhorizon.hollowengine.addons.narrative.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CameraPose
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CameraPoseSource
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderHudEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * The black (or any color) veil a story fades in and out.
 */
@ClientOnly
object StoryFadeOverlay {
    private var from = 0f
    private var to = 0f
    private var color = 0x000000
    private var startedAt = 0L
    private var durationMillis = 0L

    val isActive: Boolean get() = currentAlpha() > 0f

    /** Fades to [target] opacity over [duration], from wherever the fade currently stands. */
    fun fadeTo(target: Float, duration: Long, rgb: Int) {
        from = currentAlpha()
        to = target.coerceIn(0f, 1f)
        color = rgb
        durationMillis = duration.coerceAtLeast(0L)
        startedAt = System.currentTimeMillis()
    }

    /** Drops the veil at once, what a dialogue does when it stops for any reason. */
    fun clear() {
        from = 0f
        to = 0f
        durationMillis = 0L
        startedAt = 0L
    }

    private fun currentAlpha(): Float {
        if (durationMillis <= 0L) return to
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed >= durationMillis) return to
        return from + (to - from) * (elapsed.toFloat() / durationMillis)
    }

    @SubscribeEvent
    fun onRenderHud(event: RenderHudEvent) {
        val alpha = currentAlpha()
        if (alpha <= 0f) return

        val argb = ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
        val graphics = event.guiGraphics
        graphics.flush()
        graphics.fill(RenderType.guiOverlay(), 0, 0, event.window.guiScaledWidth, event.window.guiScaledHeight, argb)
        graphics.flush()
    }
}

/**
 * A camera pose a story sets directly, interpolated from wherever the camera is now.
 */
@ClientOnly
object StoryCameraSystem : CameraPoseSource {
    private var from: CameraPose? = null
    private var target: CameraPose? = null
    private var startedAt = 0L
    private var durationMillis = 0L

    /** Where the story wants the camera, or null when it is leaving the camera alone. */
    override val currentPose: CameraPose?
        get() {
            val destination = target ?: return null
            val origin = from ?: return destination
            if (durationMillis <= 0L) return destination
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= durationMillis) return destination
            return origin.lerp(destination, elapsed.toFloat() / durationMillis)
        }

    fun moveTo(pose: CameraPose, duration: Long) {
        from = currentPose ?: capturePlayerPose()
        target = pose
        durationMillis = duration.coerceAtLeast(0L)
        startedAt = System.currentTimeMillis()
    }

    /** Hands the camera back to the player, sliding back over [duration] first. */
    fun release(duration: Long) {
        val player = capturePlayerPose()
        if (player == null || duration <= 0L) {
            clear()
            return
        }
        moveTo(player, duration)
        releaseAfter = System.currentTimeMillis() + duration
    }

    fun clear() {
        from = null
        target = null
        durationMillis = 0L
        releaseAfter = 0L
    }

    private var releaseAfter = 0L

    /**
     * Finishes a release once its slide back is over, and hands the camera back the moment the player
     * is no longer alive to look through it.
     */
    @SubscribeEvent
    fun onClientTick(event: TickEvent.Client) {
        if (releaseAfter != 0L && System.currentTimeMillis() >= releaseAfter) clear()
        if (target == null) return
        val player = Minecraft.getInstance().player
        if (player == null || !player.isAlive) {
            clear()
            StoryFadeOverlay.clear()
        }
    }

    private fun capturePlayerPose(): CameraPose? {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return null
        val eye = player.getEyePosition(1f)
        return CameraPose(
            position = Vec3f(eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat()),
            rotation = Vec3f(player.xRot, player.yRot, 0f),
            fov = minecraft.options.fov().get().toFloat(),
        )
    }

    private fun CameraPose.lerp(other: CameraPose, progress: Float): CameraPose {
        val t = progress.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return CameraPose(
            position = Vec3f(
                mix(position.x, other.position.x),
                mix(position.y, other.position.y),
                mix(position.z, other.position.z),
            ),
            rotation = Vec3f(
                mix(rotation.x, other.rotation.x),
                rotation.y + shortestAngle(rotation.y, other.rotation.y) * t,
                mix(rotation.z, other.rotation.z),
            ),
            fov = mix(fov, other.fov),
        )
    }

    private fun shortestAngle(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}
