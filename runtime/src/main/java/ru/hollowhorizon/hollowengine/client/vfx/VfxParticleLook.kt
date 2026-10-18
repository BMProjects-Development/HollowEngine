package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.client.vfx.VfxParticleLook.Companion.STRIDE
import ru.hollowhorizon.hollowengine.common.utils.MutableColor
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxAppearance
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxVec3Value

/**
 * The size, turn and color one renderer gives the particles of the emitter it sits under.
 */
class VfxParticleLook(spec: VfxAppearance, node: VfxNodeRuntime, particles: VfxParticles) {
    /** [STRIDE] floats per particle: size, rotation in degrees, then color. */
    val data: FloatArray = particles.channel("look:${node.spec.id}", STRIDE)

    private val uniformSize = spec.uniformSize
    private val size = vector(node, VfxProperty.SIZE, spec.size, 1f)
    private val spin = vector(node, VfxProperty.SPIN, spec.rotation, 0f)
    private val color = VfxSamplers.color(
        spec.color,
        node.expressions,
        salt = VfxProperty.COLOR.hashCode(),
        drive = node.drive(VfxProperty.COLOR),
    )

    private val liveSize = isLive(node, VfxProperty.SIZE, spec.size)
    private val liveSpin = isLive(node, VfxProperty.SPIN, spec.rotation)
    private val liveColor = !VfxSamplers.isFixedPerParticle(spec.color) || node.drive(VfxProperty.COLOR) != null

    private val scratchVector = MutableVec3f()
    private val scratchColor = MutableColor(1f, 1f, 1f, 1f)

    /** Everything, for a particle that has just been born. */
    fun spawn(slot: Int, context: VfxEvalContext) {
        readSize(slot, context)
        readSpin(slot, context)
        readColor(slot, context)
    }

    /** What can change over the life of a particle. */
    fun step(slot: Int, context: VfxEvalContext) {
        if (liveSize) readSize(slot, context)
        if (liveSpin) readSpin(slot, context)
        if (liveColor) readColor(slot, context)
    }

    private fun readSize(slot: Int, context: VfxEvalContext) {
        if (uniformSize) size.evalUniform(context, scratchVector) else size.eval(context, scratchVector)
        val at = slot * STRIDE + SIZE
        data[at] = scratchVector.x
        data[at + 1] = scratchVector.y
        data[at + 2] = scratchVector.z
    }

    private fun readSpin(slot: Int, context: VfxEvalContext) {
        spin.eval(context, scratchVector)
        val at = slot * STRIDE + ROTATION
        data[at] = scratchVector.x
        data[at + 1] = scratchVector.y
        data[at + 2] = scratchVector.z
    }

    private fun readColor(slot: Int, context: VfxEvalContext) {
        color.eval(context, scratchColor)
        val at = slot * STRIDE + COLOR
        data[at] = scratchColor.r
        data[at + 1] = scratchColor.g
        data[at + 2] = scratchColor.b
        data[at + 3] = scratchColor.a
    }

    companion object {
        const val SIZE = 0
        const val ROTATION = 3
        const val COLOR = 6
        const val STRIDE = 10

        /** The look of the one particle a renderer draws when it is not under an emitter. */
        fun unit(): FloatArray = floatArrayOf(1f, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)

        private fun vector(node: VfxNodeRuntime, property: VfxProperty, value: VfxVec3Value, default: Float) =
            VfxSamplers.vec3(
                value,
                node.expressions,
                default,
                VfxRangeMode.PER_PARTICLE,
                property.hashCode(),
                node.drive(property),
            )

        private fun isLive(node: VfxNodeRuntime, property: VfxProperty, value: VfxVec3Value): Boolean =
            !VfxSamplers.isFixedPerParticle(value) || node.drive(property) != null
    }
}
