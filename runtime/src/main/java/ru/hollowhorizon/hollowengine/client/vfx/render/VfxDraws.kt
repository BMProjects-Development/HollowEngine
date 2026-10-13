package ru.hollowhorizon.hollowengine.client.vfx.render

import ru.hollowhorizon.hollowengine.client.decals.DecalGround
import ru.hollowhorizon.hollowengine.client.decals.DecalRenderer
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.InstanceBatchManager
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderContext
import ru.hollowhorizon.hollowengine.client.vfx.VfxParticleLook

/**
 * Draws one frame worth of effects, everything but the full-screen passes, which come once the
 * level is done.
 */
object VfxFrameRenderer {
    /**
     * Draws [list] as seen from [view] into [target], which is bound. Models go through the model
     * renderer and the buffers of the game, so they take their matrices from the render system,
     * which has to hold the same view.
     */
    fun render(list: VfxDrawList, view: VfxView, target: RenderTarget) {
        renderModels(list.models)
        renderSurfaces(list, view, target)
    }

    /**
     * Everything drawn with the effect shaders: the sky, then decals, meshes, ribbons and planes, then the glow
     * of whatever glows, over all of them.
     */
    fun renderSurfaces(list: VfxDrawList, view: VfxView, target: RenderTarget) {
        if (list.readsScene) VfxSceneTextures.capture(target)

        VfxSkyRenderer.render(list.skies, view)
        DecalRenderer.render(list.decals, view)
        VfxMeshRenderer.render(list.meshes, view)
        VfxRibbonRenderer.render(list.ribbons, view)
        VfxQuadRenderer.render(list.quads, view)

        if (list.glows && VfxGlow.begin(target)) {
            VfxMeshRenderer.renderGlow(view)
            VfxRibbonRenderer.render(list.ribbons, view, glow = true)
            VfxQuadRenderer.renderGlow(view)
            VfxGlow.finish(target)
        }
    }

    /**
     * Draws [list] into a panel of the editor, over what is there: a model preview. The sky and what reads the
     * scene or glows are left out, as the panel has no scene of its own to read or bloom.
     */
    fun renderInPanel(list: VfxDrawList, view: VfxView) {
        renderModels(list.models)
        DecalRenderer.render(list.decals, view)
        VfxMeshRenderer.render(list.meshes, view)
        VfxRibbonRenderer.render(list.ribbons, view)
        VfxQuadRenderer.render(list.quads, view)
    }

    fun renderModels(draws: List<VfxModelDraw>) {
        if (draws.isEmpty()) return
        val source = Minecraft.getInstance().renderBuffers().bufferSource()
        val stack = PoseStack()

        draws.forEach { draw ->
            val attachment = draw.model
            attachment.ensureReady()
            attachment.beginPose()
            attachment.endPose()

            val batch = draw.batch
            val particles = batch.particles
            for (slot in 0 until particles.count) {
                stack.pushPose()
                stack.mulPose(batch.matrix)
                stack.translate(
                    particles.positionX[slot] + batch.offset.x,
                    particles.positionY[slot] + batch.offset.y,
                    particles.positionZ[slot] + batch.offset.z,
                )
                if (draw.spec.alignToVelocity) {
                    stack.mulPose(
                        VfxBillboards.facing(particles.velocityX[slot], particles.velocityY[slot], particles.velocityZ[slot])
                    )
                }
                val look = batch.look
                val spin = batch.lookOf(slot) + VfxParticleLook.ROTATION
                val size = batch.lookOf(slot) + VfxParticleLook.SIZE
                stack.mulPose(
                    Quaternionf().rotateZYX(
                        (look[spin + 2] + batch.spin.z) * Mth.DEG_TO_RAD,
                        (look[spin + 1] + batch.spin.y) * Mth.DEG_TO_RAD,
                        (look[spin] + batch.spin.x) * Mth.DEG_TO_RAD,
                    )
                )
                stack.scale(
                    look[size] * batch.sizeScale.x,
                    look[size + 1] * batch.sizeScale.y,
                    look[size + 2] * batch.sizeScale.z,
                )

                attachment.pipeline.render(
                    RenderContext(
                        stack = stack,
                        source = source,
                        light = if (draw.spec.emissive) LightTexture.FULL_BRIGHT else particles.light[slot],
                        overlay = OverlayTexture.NO_OVERLAY,
                        allowInstancing = true,
                    )
                )
                stack.popPose()
            }
        }

        source.endBatch()
        InstanceBatchManager.flush()
        InstanceBatchManager.clear()
    }
}

/**
 * The view of the game camera, whose space is camera-relative world space.
 */
fun VfxView.Companion.ofCamera(
    modelView: Matrix4f,
    projection: Matrix4f,
    time: Float,
    decalGround: DecalGround? = null,
): VfxView {
    val screenToWorld = Matrix4f(projection).mul(modelView).invert()
    val center = screenToWorld.transformProject(Vector3f(0f, 0f, 0.5f))
    return VfxView(
        modelView = Matrix4f(modelView),
        projection = Matrix4f(projection),
        right = screenToWorld.transformProject(Vector3f(1f, 0f, 0.5f)).sub(center).normalize(),
        up = screenToWorld.transformProject(Vector3f(0f, 1f, 0.5f)).sub(center).normalize(),
        eye = Vector3f(),
        time = time,
        decalGround = decalGround,
    )
}
