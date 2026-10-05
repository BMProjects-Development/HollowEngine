package ru.hollowhorizon.hollowengine.client.vfx

import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import ru.hollowhorizon.hollowengine.common.vfx.VfxTransform

/**
 * Where a node sits, kept as position, rotation and scale rather than a matrix.
 */
class VfxFrame {
    val position = MutableVec3f()
    val rotation = MutableQuatF()
    val scale = MutableVec3f(Vec3f.ONES)

    fun setIdentity(): VfxFrame {
        position.set(Vec3f.ZERO)
        rotation.setIdentity()
        scale.set(Vec3f.ONES)
        return this
    }

    fun set(other: VfxFrame): VfxFrame {
        position.set(other.position)
        rotation.set(other.rotation)
        scale.set(other.scale)
        return this
    }

    /** This frame becomes [parent] with [local] applied inside it. */
    fun setCombined(parent: VfxFrame, local: VfxTransform): VfxFrame {
        val offset = MutableVec3f(
            local.position.x * parent.scale.x,
            local.position.y * parent.scale.y,
            local.position.z * parent.scale.z,
        ).rotateInPlace(parent.rotation)

        position.set(parent.position).add(offset)
        parent.rotation.mul(eulerOf(local.rotation), rotation)
        scale.set(
            parent.scale.x * local.scale.x,
            parent.scale.y * local.scale.y,
            parent.scale.z * local.scale.z,
        )
        return this
    }

    /** A point of this frame, in whatever space the frame itself is expressed in. */
    fun transformPoint(point: Vec3f, into: MutableVec3f): MutableVec3f {
        val scaled = MutableVec3f(point.x * scale.x, point.y * scale.y, point.z * scale.z)
            .rotateInPlace(rotation)
        return into.set(position).add(scaled)
    }

    /** A direction of this frame: rotated and scaled, but not moved. */
    fun transformDirection(direction: Vec3f, into: MutableVec3f): MutableVec3f {
        val scaled = MutableVec3f(
            direction.x * scale.x,
            direction.y * scale.y,
            direction.z * scale.z,
        ).rotateInPlace(rotation)
        return into.set(scaled)
    }

    fun toMatrix(into: MutableMat4f): MutableMat4f =
        into.setIdentity().translate(position).rotate(rotation).scale(scale)

    /** The part of this frame particles follow, once the author has said what they follow. */
    fun toFollowedMatrix(into: MutableMat4f, rotation: Boolean, scale: Boolean): MutableMat4f {
        into.setIdentity().translate(position)
        if (rotation) into.rotate(this.rotation)
        if (scale) into.scale(this.scale)
        return into
    }

    private fun MutableVec3f.rotateInPlace(quaternion: QuatF): MutableVec3f {
        val rotated = (this as Vec3f).rotateBy(quaternion)
        return set(rotated.x, rotated.y, rotated.z)
    }

    companion object {
        /** Euler degrees as a quaternion, applied X then Y then Z. */
        fun eulerOf(euler: Vec3f): QuatF = eulerRotationXyz(euler)
    }
}
