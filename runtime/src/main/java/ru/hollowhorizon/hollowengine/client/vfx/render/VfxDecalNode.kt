package ru.hollowhorizon.hollowengine.client.vfx.render

import net.minecraft.client.renderer.LightTexture
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Quaternionf
import ru.hollowhorizon.hollowengine.client.decals.DecalDraw
import ru.hollowhorizon.hollowengine.client.vfx.VfxNodeRuntime
import ru.hollowhorizon.hollowengine.client.vfx.VfxParticleLook
import ru.hollowhorizon.hollowengine.client.vfx.VfxRangeMode
import ru.hollowhorizon.hollowengine.client.vfx.VfxSamplers
import ru.hollowhorizon.hollowengine.common.vfx.VfxDecalSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty

/** Casts the decal of a [VfxDecalSpec] node, once in its place or on each particle of its emitter. */
class VfxDecalNode(private val spec: VfxDecalSpec, node: VfxNodeRuntime) : VfxRendererNode(node, spec.tint, spec.particle) {
    private val opacity = VfxSamplers.driven(
        VfxSamplers.scalar(spec.opacity, node.expressions, 1f, VfxRangeMode.PER_PARTICLE),
        node.drive(VfxProperty.DECAL_OPACITY),
    )

    override fun collect(into: VfxDrawList, placement: Matrix4f) {
        val batch = batch(placement) ?: return
        val alpha = opacity.eval(node.context).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val particles = batch.particles
        val look = batch.look
        for (slot in 0 until particles.count) {
            val at = batch.lookOf(slot)
            val spin = at + VfxParticleLook.ROTATION
            val size = at + VfxParticleLook.SIZE
            val color = at + VfxParticleLook.COLOR
            val matrix = Matrix4f(batch.matrix)
                .translate(
                    particles.positionX[slot] + batch.offset.x,
                    particles.positionY[slot] + batch.offset.y,
                    particles.positionZ[slot] + batch.offset.z,
                )
                .rotate(
                    Quaternionf().rotateZYX(
                        (look[spin + 2] + batch.spin.z) * Mth.DEG_TO_RAD,
                        (look[spin + 1] + batch.spin.y) * Mth.DEG_TO_RAD,
                        (look[spin] + batch.spin.x) * Mth.DEG_TO_RAD,
                    )
                )
                .scale(look[size] * batch.sizeScale.x, look[size + 1] * batch.sizeScale.y, look[size + 2] * batch.sizeScale.z)
            val tint = floatArrayOf(
                look[color] * batch.tint[0],
                look[color + 1] * batch.tint[1],
                look[color + 2] * batch.tint[2],
                look[color + 3] * batch.tint[3] * alpha,
            )
            val light = if (spec.decal.emissive) LightTexture.FULL_BRIGHT else particles.light[slot]
            into.decals += DecalDraw(spec.decal, matrix, light, tint, carried = source == null)
        }
    }
}
