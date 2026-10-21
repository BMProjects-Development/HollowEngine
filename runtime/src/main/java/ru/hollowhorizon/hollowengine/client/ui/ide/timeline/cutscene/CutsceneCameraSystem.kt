package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderItemInHandEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderOverlayEvent
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

@ClientOnly
object CutsceneCameraSystem {
    private var controller: CutscenePlaybackController? = null
    private var isPreview = false
    private val environmentOverride = CutsceneEnvironmentOverride()

    val activeController: CutscenePlaybackController?
        get() = controller

    /**
     * A pose held by a tool that needs a frame seen from a given point. It wins over cutscenes and
     * [CameraPoseSources] while it is set and hides the hand like a cutscene does.
     */
    @Volatile
    var heldPose: CameraPose? = null

    /**
     * The pose the camera should take. A held pose wins over everything; a cutscene being played wins
     * over the poses of [CameraPoseSources], because it is the more specific instruction. The render
     * bridge reads this one property, so all of them arrive through the same path.
     */
    val currentPose: CameraPose?
        get() = heldPose ?: controller?.currentPose ?: CameraPoseSources.currentPose

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

    fun update(minecraft: Minecraft) {
        val active = controller ?: return
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

    @SubscribeEvent
    fun onRenderOverlays(event: RenderOverlayEvent.Pre) {
        if (controller != null) event.isCanceled = true
    }

    @SubscribeEvent
    fun onRenderHand(event: RenderItemInHandEvent) {
        if (controller != null || heldPose != null) event.isCanceled = true
    }
}
