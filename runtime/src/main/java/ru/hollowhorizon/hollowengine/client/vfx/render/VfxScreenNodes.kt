package ru.hollowhorizon.hollowengine.client.vfx.render

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.vfx.*
import ru.hollowhorizon.hollowengine.common.vfx.VfxCameraShakeSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPostEffectSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxSkySpec

/** Hands the frame its full-screen pass while the node is on. */
class VfxPostEffectNode(private val spec: VfxPostEffectSpec, private val node: VfxNodeRuntime) : VfxNodeBehavior {
    private val uniforms = VfxUniformBinding(spec.shader, spec.uniforms, spec.samplers, node)

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        if (!node.isActive || spec.shader.isBlank()) return
        val origin = node.frame.position
        into.posts += VfxPostDraw(
            shader = spec.shader,
            uniforms = uniforms.evaluate(node.context),
            position = placement.transformPosition(Vector3f(origin.x, origin.y, origin.z)),
        )
    }
}

/** Hands the frame its sky pass while the node is on. */
class VfxSkyNode(private val spec: VfxSkySpec, private val node: VfxNodeRuntime) : VfxNodeBehavior {
    private val uniforms = VfxUniformBinding(spec.shader, spec.uniforms, spec.samplers, node)

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        if (!node.isActive || spec.shader.isBlank()) return
        val origin = node.frame.position
        into.skies += VfxSkyDraw(
            shader = spec.shader,
            uniforms = uniforms.evaluate(node.context),
            position = placement.transformPosition(Vector3f(origin.x, origin.y, origin.z)),
            time = node.context.effectTime,
        )
    }
}

/**
 * Adds its swing to the camera shake of the frame. The swing follows smooth noise, one channel per
 * axis, so it never repeats and never jumps.
 */
class VfxCameraShake(private val spec: VfxCameraShakeSpec, private val node: VfxNodeRuntime) : VfxNodeBehavior {
    private val strength = VfxSamplers.driven(
        VfxSamplers.scalar(spec.strength, node.expressions, 1f, VfxRangeMode.PER_PARTICLE),
        node.drive(VfxProperty.SHAKE_STRENGTH),
    )
    private val frequency = VfxSamplers.driven(
        VfxSamplers.scalar(spec.frequency, node.expressions, 14f, VfxRangeMode.PER_PARTICLE),
        node.drive(VfxProperty.SHAKE_FREQUENCY),
    )

    /** How far through the noise the shake has run; frequency changes speed it up, never jump it. */
    private var phase = 0f

    override fun update(dt: Float) {
        if (dt > 0f) phase += frequency.eval(node.context).coerceAtLeast(0f) * dt
    }

    override fun restart() {
        phase = 0f
    }

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        if (!node.isActive) return
        val scale = strength.eval(node.context).coerceAtLeast(0f)
        if (scale == 0f) return
        val amplitude = spec.amplitude
        into.shake[0] += VfxNoise.sample(phase, 0f, 0f, node.seed) * amplitude.x * scale
        into.shake[1] += VfxNoise.sample(phase, AXIS_OFFSET, 0f, node.seed) * amplitude.y * scale
        into.shake[2] += VfxNoise.sample(phase, AXIS_OFFSET * 2f, 0f, node.seed) * amplitude.z * scale
    }

    private companion object {
        /** Far enough apart in the noise that the axes do not move together. */
        const val AXIS_OFFSET = 37.3f
    }
}

/**
 * Draws the sky nodes of the frame, each a quad over the whole screen on the far plane.
 */
object VfxSkyRenderer {
    fun render(skies: List<VfxSkyDraw>, view: VfxView) {
        if (skies.isEmpty()) return
        val toView = Matrix4f(view.projection).mul(view.modelView).invert()

        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL33.GL_LEQUAL)
        RenderSystem.depthMask(false)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()
        try {
            skies.forEach { sky ->
                val shader = VfxShaders.get(sky.shader, DefaultVertexFormat.POSITION) ?: return@forEach
                val builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
                builder.addVertex(-1f, -1f, 1f)
                builder.addVertex(1f, -1f, 1f)
                builder.addVertex(1f, 1f, 1f)
                builder.addVertex(-1f, 1f, 1f)
                val mesh = builder.build() ?: return@forEach

                val offset = Vector3f(sky.position).sub(view.eye)
                val center = if (offset.lengthSquared() > 1e-6f) Vector3f(offset).normalize() else Vector3f(0f, 1f, 0f)
                view.drawImmediate(mesh, shader) { bound ->
                    bound.safeGetUniform("InvViewProjMat").set(toView)
                    bound.safeGetUniform("SkyCenter").set(center.x, center.y, center.z)
                    bound.safeGetUniform("NodeOffset").set(offset.x, offset.y, offset.z)
                    bound.safeGetUniform("EffectTime").set(sky.time)
                    sky.uniforms.apply(bound)
                }
            }
        } finally {
            VfxMaterialStates.restore()
        }
    }
}

/**
 * Draws the full-screen passes of the frame, each over the result of the one before it. [view] is the
 * one the frame was drawn with, which a post effect graph needs to read positions back from the depth.
 */
object VfxPostProcessor {
    fun apply(posts: List<VfxPostDraw>, target: RenderTarget, view: VfxView) {
        if (posts.isEmpty()) return
        val toScreen = Matrix4f(view.projection).mul(view.modelView)
        val toView = Matrix4f(toScreen).invert()

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableBlend()
        RenderSystem.disableCull()
        try {
            posts.forEach { post ->
                val shader = VfxShaders.post(post.shader) ?: return@forEach
                VfxSceneTextures.capture(target)

                VfxScreenQuad.draw(shader) { bound ->
                    VfxSceneTextures.bind(bound)
                    bound.safeGetUniform("ScreenSize").set(target.width.toFloat(), target.height.toFloat())
                    bound.safeGetUniform("SceneProjMat").set(view.projection)
                    bound.safeGetUniform("InvViewProjMat").set(toView)
                    bound.safeGetUniform("ViewProjMat").set(toScreen)
                    bound.safeGetUniform("ViewEye").set(view.eye.x, view.eye.y, view.eye.z)
                    val offset = Vector3f(post.position).sub(view.eye)
                    bound.safeGetUniform("NodeOffset").set(offset.x, offset.y, offset.z)
                    bound.safeGetUniform("ShaderTime").set(view.time)
                    post.uniforms.apply(bound)
                }
            }
        } finally {
            RenderSystem.enableDepthTest()
            RenderSystem.depthMask(true)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.enableCull()
        }
    }
}
