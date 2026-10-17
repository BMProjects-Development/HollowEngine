package ru.hollowhorizon.hollowengine.client.utils.math

import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.common.utils.math.*
import kotlin.math.sqrt

fun Float.lerp(other: Float, alpha: Float) = this + (other - this) * alpha

fun catmullRom(
    t: Float,
    a: Float,
    b: Float,
    c: Float,
    d: Float,
): Float {
    val v0 = -0.5f * a + 1.5f * b - 1.5f * c + 0.5f * d
    val v1 = a - 2.5f * b + 2 * c - 0.5f * d
    val v2 = -0.5f * a + 0.5f * c
    val tt = t * t
    return v0 * t * tt + v1 * tt + v2 * t + b
}

fun bezier(
    t: Float,
    a: Float,
    b: Float,
    c: Float,
    d: Float,
): Float {
    val ab = a.lerp(b, t)
    val bc = b.lerp(c, t)
    val cd = c.lerp(d, t)
    val abc = ab.lerp(bc, t)
    val bcd = bc.lerp(cd, t)
    return abc.lerp(bcd, t)
}

fun QuatF.Companion.fromLookAt(lookAt: Vec3f, up: Vec3f): QuatF {
    val z = MutableVec3f(0f, 0f, 0f).minus(lookAt).normed()
    val x = up.cross(z, MutableVec3f()).norm()
    val y = z.cross(x, MutableVec3f())
    return fromRotationMatrix(
        Mat3f(
            x.x, y.x, z.x,
            x.y, y.y, z.y,
            x.z, y.z, z.z,
        )
    )
}

fun QuatF.Companion.fromRotationMatrix(m: Mat3f): QuatF = with(m) {
    val trace = m00 + m11 + m22
    if (trace >= 0) {
        val r = sqrt(trace + 1f)
        val s = 0.5f / r
        return QuatF(
            (m21 - m12) * s,
            (m02 - m20) * s,
            (m10 - m01) * s,
            0.5f * r,
        )
    } else {
        if (m00 >= m11 && m00 >= m22) {
            val r = sqrt(m00 - (m11 + m22) + 1f)
            val s = 0.5f / r
            return QuatF(
                0.5f * r,
                (m10 + m01) * s,
                (m02 + m20) * s,
                (m21 - m12) * s,
            )
        } else if (m11 > m22) {
            val r = sqrt(m11 - (m22 + m00) + 1f)
            val s = 0.5f / r
            return QuatF(
                (m10 + m01) * s,
                0.5f * r,
                (m21 + m12) * s,
                (m02 - m20) * s,
            )
        } else {
            val r = sqrt(m22 - (m00 + m11) + 1f)
            val s = 0.5f / r
            return QuatF(
                (m02 + m20) * s,
                (m21 + m12) * s,
                0.5f * r,
                (m10 - m01) * s,
            )
        }
    }
}

inline fun <T> Vec3f.rotateBy(q: QuatF, out: (Float, Float, Float) -> T): T =
    with(q * QuatF(x, y, z, 0f) * q.conjugate()) { out(x, y, z) }

fun QuatF.conjugate() = QuatF(-x, -y, -z, w)

fun Vec3f.floor() = Vec3f(kotlin.math.floor(x), kotlin.math.floor(y), kotlin.math.floor(z))
fun Vec3f.rotateBy(q: QuatF) = rotateBy(q, ::Vec3f)
fun Vec3f.rotateSelfBy(q: QuatF) = rotateBy(q, ::Vec3f)
fun Vec3f.negate() = Vec3f(-x, -y, -z)

val QuatF.Companion.Y180: QuatF
    get() = QuatF(0f, 1f, 0f, 0f)

fun QuatF.opposite() = this * QuatF.Y180
fun QuatF.projectAroundAxis(axis: Vec3f): QuatF {
    val rotationAxis = Vec3f(x, y, z)
    val projectedLength = axis.dot(rotationAxis)
    val projectedAxis = axis.times(projectedLength)
    return if (projectedLength > 0) {
        QuatF(projectedAxis.x, projectedAxis.y, projectedAxis.z, w).normed()
    } else {
        QuatF(-projectedAxis.x, -projectedAxis.y, -projectedAxis.z, -w).normed()
    }
}

val Vec3.xz: Vec2d
    get() = Vec2d(x, z)
val Vec3.xy: Vec2d
    get() = Vec2d(x, y)
val Vec3.yz: Vec2d
    get() = Vec2d(y, z)

fun Mat4f.asMatrix4f(): Matrix4f = Matrix4f(
    m00, m01, m02, m03,
    m10, m11, m12, m13,
    m20, m21, m22, m23,
    m30, m31, m32, m33
).transpose()
fun Mat3f.asMatrix3f(): Matrix3f = Matrix3f(
    m00, m10, m20,
    m01, m11, m21,
    m02, m12, m22,
).transpose()