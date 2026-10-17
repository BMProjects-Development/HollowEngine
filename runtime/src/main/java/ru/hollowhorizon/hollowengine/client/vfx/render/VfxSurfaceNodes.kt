package ru.hollowhorizon.hollowengine.client.vfx.render

import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.client.vfx.*
import ru.hollowhorizon.hollowengine.common.utils.MutableColor
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.vfx.*

/**
 * What every renderer node shares: the particles it draws, the look it gives them, and its tint.
 *
 * Under an emitter those are the emitter's particles, sized and colored by a [VfxParticleLook] of
 * this node. Anywhere else the node draws one particle of its own, a unit one at its origin, so the
 * same draw path covers both.
 */
abstract class VfxRendererNode(
    protected val node: VfxNodeRuntime,
    tint: VfxColorValue,
    appearance: VfxAppearance,
) : VfxNodeBehavior {
    /** The emitter whose particles this node draws, or null when it draws itself. */
    protected val source: VfxEmitter? = node.particleSource

    private val own: VfxParticles? = if (source == null) VfxParticles(1).also(::placeOwnParticle) else null

    private val look: FloatArray = source
        ?.let { emitter -> VfxParticleLook(appearance, node, emitter.particles).also(emitter::addLook).data }
        ?: VfxParticleLook.unit()

    private val tintSampler: VfxColorSampler = VfxSamplers.color(
        tint,
        node.expressions,
        salt = VfxProperty.TINT.hashCode(),
        drive = node.drive(VfxProperty.TINT),
    )
    private val tintScratch = MutableColor(1f, 1f, 1f, 1f)
    private val frameMatrix = MutableMat4f()

    /** What this node draws this frame, or null when it draws nothing. */
    protected fun batch(placement: Matrix4f): VfxParticleBatch? {
        if (!node.isActive) return null

        tintSampler.eval(node.context, tintScratch)
        val tint = floatArrayOf(tintScratch.r, tintScratch.g, tintScratch.b, tintScratch.a)

        val emitter = source
        if (emitter != null) {
            if (emitter.particles.count == 0) return null
            val local = node.transform
            return VfxParticleBatch(
                particles = emitter.particles,
                look = look,
                matrix = Matrix4f(placement).mul(emitter.simToRender.asMatrix4f()),
                uvColumns = emitter.uvColumns,
                uvRows = emitter.uvRows,
                offset = local.position,
                spin = local.rotation,
                sizeScale = local.scale,
                tint = tint,
            )
        }

        val particles = own ?: return null
        particles.light[0] = node.instance.lightAt(node.frame.position)
        return VfxParticleBatch(
            particles = particles,
            look = look,
            matrix = Matrix4f(placement).mul(node.frame.toMatrix(frameMatrix).asMatrix4f()),
            tint = tint,
        )
    }

    private fun placeOwnParticle(particles: VfxParticles) {
        particles.allocate()
        particles.lifetime[0] = 1f
    }
}

/** A renderer node drawn with a material, and the uniforms that material's shader takes. */
abstract class VfxSurfaceNode(spec: VfxSurfaceSpec, node: VfxNodeRuntime, appearance: VfxAppearance) :
    VfxRendererNode(node, spec.tint, appearance) {
    private val uniforms = spec.material.shader?.let { shader ->
        VfxUniformBinding(shader, spec.material.uniforms, spec.material.samplers, node)
    }

    protected fun uniforms(): VfxUniformValues? = uniforms?.evaluate(node.context)
}

class VfxPlaneNode(private val spec: VfxPlaneSpec, node: VfxNodeRuntime) : VfxSurfaceNode(spec, node, spec.particle) {
    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        val batch = batch(placement) ?: return
        into.quads += VfxQuadDraw(spec, batch, uniforms())
    }
}

class VfxMeshNode(private val spec: VfxMeshSpec, node: VfxNodeRuntime) : VfxSurfaceNode(spec, node, spec.particle) {
    private val primitive = spec.primitive

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        val batch = batch(placement) ?: return
        into.meshes += VfxMeshDraw(spec, primitive, batch, uniforms())
    }
}

/**
 * A model drawn through the model renderer. Each particle is submitted as one instance of the model,
 * so the model renderer batches them the way it batches entities.
 */
class VfxModelNode(private val spec: VfxModelSpec, node: VfxNodeRuntime) :
    VfxRendererNode(node, VfxColorValue.WHITE, spec.particle) {
    /** Loaded the first time it is actually drawn. */
    private val model: ModelAttachment? by lazy {
        spec.model.takeIf { it.isNotBlank() }?.let { ModelAttachment(it) }
    }

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        val batch = batch(placement) ?: return
        val attachment = model ?: return
        into.models += VfxModelDraw(spec, attachment, batch)
    }
}
