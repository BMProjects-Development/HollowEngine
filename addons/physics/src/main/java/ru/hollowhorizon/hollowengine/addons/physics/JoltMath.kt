package ru.hollowhorizon.hollowengine.addons.physics

import com.github.stephengold.joltjni.Mat44
import com.github.stephengold.joltjni.Quat
import com.github.stephengold.joltjni.RVec3
import com.github.stephengold.joltjni.Vec3
import com.github.stephengold.joltjni.readonly.Mat44Arg
import ru.hollowhorizon.hollowengine.common.utils.math.*
import kotlin.math.abs

internal fun Vec3f.toJolt(): Vec3 = Vec3(x, y, z)

internal fun Vec3f.toJoltPosition(): RVec3 = RVec3(x.toDouble(), y.toDouble(), z.toDouble())

internal fun QuatF.toJolt(): Quat = Quat(x, y, z, w)

internal fun Mat4f.toJolt(): Mat44 = Mat44(
    m00, m10, m20, m30,
    m01, m11, m21, m31,
    m02, m12, m22, m32,
    m03, m13, m23, m33,
)

internal fun Mat44Arg.rotationAndTranslation(rotation: MutableQuatF, translation: MutableVec3f) {
    val orientation = quaternion
    rotation.set(orientation.x, orientation.y, orientation.z, orientation.w)
    val offset = getTranslation()
    translation.set(offset.x, offset.y, offset.z)
}

internal fun Vec3f.rotated(rotation: QuatF): Vec3f {
    val tx = 2f * (rotation.y * z - rotation.z * y)
    val ty = 2f * (rotation.z * x - rotation.x * z)
    val tz = 2f * (rotation.x * y - rotation.y * x)
    return Vec3f(
        x + rotation.w * tx + rotation.y * tz - rotation.z * ty,
        y + rotation.w * ty + rotation.z * tx - rotation.x * tz,
        z + rotation.w * tz + rotation.x * ty - rotation.y * tx,
    )
}

internal fun Vec3f.rotatedInverse(rotation: QuatF): Vec3f = rotated(MutableQuatF(rotation).invert())

/** The shortest rotation that turns +Y into [axis]. */
internal fun rotationFromYTo(axis: Vec3f): QuatF {
    val direction = if (axis.length() > 1.0e-5f) axis.normed() else Vec3f.Y_AXIS
    val cosine = Vec3f.Y_AXIS dot direction
    if (cosine > 0.999999f) return QuatF.IDENTITY
    if (cosine < -0.999999f) return QuatF(0f, 0f, 1f, 0f)

    val axisOfRotation = Vec3f.Y_AXIS.cross(direction, MutableVec3f())
    return MutableQuatF(axisOfRotation.x, axisOfRotation.y, axisOfRotation.z, 1f + cosine).norm()
}

/** A unit vector at right angles to [axis]. */
internal fun perpendicularTo(axis: Vec3f): Vec3f {
    val reference = if (abs(axis.y) < 0.9f) Vec3f.Y_AXIS else Vec3f.X_AXIS
    val perpendicular = axis.cross(reference, MutableVec3f())
    return if (perpendicular.length() > 1.0e-5f) Vec3f(perpendicular.norm()) else Vec3f.X_AXIS
}

internal fun matrixOf(rotation: QuatF, translation: Vec3f): MutableMat4f =
    matrixInto(MutableMat4f(), rotation, translation)

internal fun matrixInto(target: MutableMat4f, rotation: QuatF, translation: Vec3f): MutableMat4f =
    target.setIdentity().rotate(rotation).also { matrix ->
        matrix.m03 = translation.x
        matrix.m13 = translation.y
        matrix.m23 = translation.z
    }
