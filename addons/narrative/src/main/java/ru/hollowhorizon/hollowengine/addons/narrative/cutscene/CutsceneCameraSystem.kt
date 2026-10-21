package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.client.camera.CameraPoseSource
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * The cutscene being played or previewed. It holds the camera above a dialogue's own camera, because it is
 * the more specific instruction, and hides the hand and the screen overlays while it does.
 */
@ClientOnly
object CutsceneCameraSystem : CameraPoseSource {
    private var controller: CutscenePlaybackController? = null
    private var isPreview = false
    private val environmentOverride = CutsceneEnvironmentOverride()

    val activeController: CutscenePlaybackController?
        get() = controller

    override val currentPose: CameraPose?
        get() = controller?.currentPose

    override val hidesHand: Boolean get() = true

    override val hidesOverlays: Boolean get() = true

    fun play(data: CutsceneData, loop: Boolean = false, anchor: CutsceneAnchor = CutsceneAnchor.WHERE_RECORDED) {
        val playback = CutscenePlaybackController().also {
            it.setupTracks(data, loop, anchor)
        }
        activate(playback, preview = false)
    }

    fun play(controller: CutscenePlaybackController) {
        activate(controller, preview = false)
    }

    fun preview(controller: CutscenePlaybackController) {
        activate(controller, preview = true)
    }

    fun stop() {
        controller?.stop()
        releaseController()
    }

    private fun activate(controller: CutscenePlaybackController, preview: Boolean) {
        if (this.controller !== controller) environmentOverride.restore()
        this.controller = controller
        isPreview = preview
        if (preview) controller.pause() else controller.play()
        Minecraft.getInstance().level?.let { level ->
            environmentOverride.apply(level, controller.currentEnvironment)
        }
    }

    private fun releaseController() {
        environmentOverride.restore()
        controller = null
        isPreview = false
    }

    @SubscribeEvent
    fun onRenderTick(event: RenderTickEvent.Pre) {
        val active = controller ?: return
        val minecraft = event.minecraft
        val level = minecraft.level
        if (level == null || minecraft.player == null) {
            stop()
            return
        }

        active.update(minecraft.timer.realtimeDeltaTicks / 20f)
        environmentOverride.apply(level, active.currentEnvironment)
        if (!isPreview && !active.isPlaying && active.currentTime >= active.duration) {
            releaseController()
        }
    }

    fun capturePlayerPose(minecraft: Minecraft = Minecraft.getInstance()): CameraPose? {
        val player = minecraft.player ?: return null
        val eye = player.getEyePosition(1f)
        return CameraPose(
            position = eye.toVec3f(),
            rotation = Vec3f(player.xRot, player.yRot, 0f),
            fov = minecraft.options.fov().get().toFloat(),
        )
    }

    private fun Vec3.toVec3f(): Vec3f = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())
}
