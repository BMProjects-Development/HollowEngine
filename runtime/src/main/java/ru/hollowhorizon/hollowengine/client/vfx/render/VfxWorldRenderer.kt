package ru.hollowhorizon.hollowengine.client.vfx.render

import ru.hollowhorizon.hollowengine.client.decals.DecalGround
import ru.hollowhorizon.hollowengine.client.decals.Decals
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.render.CameraSetupEvent
import ru.hollowhorizon.hollowengine.client.utils.shouldOverrideShaders
import ru.hollowhorizon.hollowengine.client.vfx.VfxBoneBindings
import ru.hollowhorizon.hollowengine.client.vfx.VfxInstance
import ru.hollowhorizon.hollowengine.client.vfx.VfxScenes
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage

/**
 * Ticks and draws the effects of the world.
 */
@ClientOnly
object VfxWorldRenderer {
    /** The shader game time of the render system is the share of a day gone, and a day is this many seconds. */
    private const val SECONDS_PER_DAY = 1200f

    private val frame = VfxDrawList()
    private val shake = FloatArray(3)

    /** The view of the frame, while its surfaces wait for the shader pack to finish. */
    private var deferred: VfxView? = null

    /** The view the effects of the frame were drawn with, which its post effects read the depth back with. */
    private var frameView: VfxView? = null

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage == RenderStage.AFTER_WEATHER) drawEffects(event)
    }

    /** Before the overlays of the level, so that a gizmo or a selection outline stays readable. */
    @SubscribeEvent(10)
    fun onLevelDone(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_LEVEL || deferred != null || frame.posts.isEmpty()) return
        val view = frameView ?: return
        VfxPostProcessor.apply(frame.posts, Minecraft.getInstance().mainRenderTarget, view)
        frame.posts.clear()
    }

    /** The shader pack has written its final image; the surfaces that waited for it go on top. */
    fun onShaderPackFrameFinished() {
        val view = deferred ?: return
        deferred = null

        val main = Minecraft.getInstance().mainRenderTarget
        main.bindWrite(true)
        VfxFrameRenderer.renderSurfaces(frame, view, main)
        VfxPostProcessor.apply(frame.posts, main, view)
        frame.posts.clear()
    }

    @SubscribeEvent
    fun onCameraSetup(event: CameraSetupEvent) {
        event.pitch += shake[0]
        event.yaw += shake[1]
        event.roll += shake[2]
    }

    private fun drawEffects(event: RenderLevelStageEvent) {
        val camera = event.camera.position
        val scene = VfxScenes.current()
        val bones = VfxBoneBindings.drain(camera)
        val decals = Decals.drain()
        frame.clear()
        deferred = null
        frameView = null
        if (scene == null && bones.isEmpty() && decals.isEmpty()) {
            shake.fill(0f)
            return
        }

        scene?.update(camera)

        val levelPose = event.poseStack.last().pose()
        scene?.forEachInstance { instance, _ ->
            instance.collect(frame, placeInWorld(instance, levelPose, camera.x, camera.y, camera.z))
        }
        bones.forEach { binding -> binding.instance.collect(frame, binding.placement) }
        frame.decals += decals
        frame.shake.copyInto(shake)
        if (frame.isEmpty) return

        val time = RenderSystem.getShaderGameTime() * SECONDS_PER_DAY
        val view = VfxView.ofCamera(
            RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), time, DecalGround.world(camera),
        )
        frameView = view
        val main = Minecraft.getInstance().mainRenderTarget
        val depthWrite = GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK)
        try {
            if (shouldOverrideShaders()) {
                VfxFrameRenderer.renderModels(frame.models)
                deferred = view
            } else {
                VfxFrameRenderer.render(frame, view, main)
            }
        } finally {
            RenderSystem.depthMask(depthWrite)
        }
    }

    private fun placeInWorld(
        instance: VfxInstance,
        levelPose: Matrix4f,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
    ): Matrix4f = Matrix4f(levelPose).translate(
        (instance.origin.x - cameraX).toFloat(),
        (instance.origin.y - cameraY).toFloat(),
        (instance.origin.z - cameraZ).toFloat(),
    )
}
