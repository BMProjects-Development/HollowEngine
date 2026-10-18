package ru.hollowhorizon.hollowengine.common.utils.math

import org.joml.Quaternionf
import org.joml.Vector3f

/** Euler degrees as a rotation turned about X, then Y, then Z. */
fun eulerRotationXyz(degrees: Vec3f): QuatF = MutableQuatF()
    .setIdentity()
    .rotate(degrees.x.deg, Vec3f.X_AXIS)
    .rotate(degrees.y.deg, Vec3f.Y_AXIS)
    .rotate(degrees.z.deg, Vec3f.Z_AXIS)

/** The Euler degrees [eulerRotationXyz] turns into this rotation. */
fun QuatF.eulerDegreesXyz(): Vec3f {
    val angles = Quaternionf(x, y, z, w).getEulerAnglesXYZ(Vector3f())
    return Vec3f(Math.toDegrees(angles.x.toDouble()).toFloat(), Math.toDegrees(angles.y.toDouble()).toFloat(), Math.toDegrees(angles.z.toDouble()).toFloat())
}
