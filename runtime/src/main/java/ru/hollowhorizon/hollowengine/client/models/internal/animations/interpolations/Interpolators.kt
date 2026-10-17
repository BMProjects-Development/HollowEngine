package ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations

import ru.hollowhorizon.hollowengine.client.utils.math.conjugate
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class Vec3Step(keys: FloatArray, values: Array<Vec3f>) : SteppedInterpolator<Vec3f>(keys, values)

class QuatStep(keys: FloatArray, values: Array<QuatF>) : SteppedInterpolator<QuatF>(keys, values)

class FloatsStep(keys: FloatArray, values: Array<FloatArray>) : SteppedInterpolator<FloatArray>(keys, values)

class LinearSingle(keys: FloatArray, values: Array<FloatArray>) : StaticInterpolator<FloatArray>(keys, values) {
    override fun compute(time: Float): FloatArray {
        if (time <= keys.first() || keys.size == 1) return values.first()
        else if (time >= keys.last()) return values.last()
        else {
            val previousIndex = time.animIndex
            val nextIndex = previousIndex + 1
            val local = time - keys[previousIndex]
            val delta = keys[nextIndex] - keys[previousIndex]
            val alpha = local / delta
            val previousValue = values[previousIndex]
            val nextValue = values[nextIndex]
            val interpolatedValue =
                FloatArray(previousValue.size) { i -> previousValue[i] + (nextValue[i] - previousValue[i]) * alpha }
            return interpolatedValue
        }
    }

}

class Linear(keys: FloatArray, values: Array<Vec3f>) : StaticInterpolator<Vec3f>(keys, values) {
    override fun compute(time: Float): Vec3f {
        if (time <= keys.first() || keys.size == 1) return values.first()
        else if (time >= keys.last()) return values.last()
        else {
            val previousIndex = time.animIndex
            val nextIndex = previousIndex + 1
            val local = time - keys[previousIndex]
            val delta = keys[nextIndex] - keys[previousIndex]
            val alpha = local / delta
            val previousPoint = Vec3f(values[previousIndex])
            val nextPoint = values[nextIndex]
            return previousPoint.mix(nextPoint, alpha)
        }
    }
}

class SphericalLinear(keys: FloatArray, values: Array<QuatF>) : StaticInterpolator<QuatF>(keys, values) {
    override fun compute(time: Float): QuatF {
        if (time <= keys.first() || keys.size == 1) return values.first()
        else if (time >= keys.last()) return values.last()
        else {
            val previousIndex = time.animIndex
            val nextIndex = previousIndex + 1

            val local = time - keys[previousIndex]
            val delta = keys[nextIndex] - keys[previousIndex]
            val alpha = local / delta

            val prev = values[previousIndex]
            val next = values[nextIndex]
            val previousPoint = QuatF(prev.x, prev.y, prev.z, prev.w)
            val nextPoint = QuatF(next.x, next.y, next.z, next.w)

            val r = previousPoint.mix(nextPoint, alpha)
            return QuatF(r.x, r.y, r.z, r.w)
        }
    }
}

class CatmullromQuat(
    keys: FloatArray,
    values: Array<QuatF>
) : StaticInterpolator<QuatF>(keys, values) {
    override fun compute(time: Float): QuatF {
        if (time <= keys.first()) return values.first()
        if (time >= keys.last()) return values.last()

        val i = time.animIndex

        val i0 = (i - 1).coerceAtLeast(0)
        val i1 = i
        val i2 = (i + 1).coerceAtMost(values.lastIndex)
        val i3 = (i + 2).coerceAtMost(values.lastIndex)

        val t0 = keys[i1]
        val t1 = keys[i2]
        val localT = ((time - t0) / (t1 - t0)).coerceIn(0f, 1f)

        val q0 = values[i0]
        val q1 = values[i1]
        val q2 = values[i2]
        val q3 = values[i3]

        return squad(q1, q2, intermediate(q0, q1, q2), intermediate(q1, q2, q3), localT)
    }

    private fun intermediate(q0: QuatF, q1: QuatF, q2: QuatF): QuatF {
        val invQ1 = q1.conjugate()
        val log1 = log(invQ1 * q0)
        val log2 = log(invQ1 * q2)
        val avg = (log1.add(log2, MutableQuatF()) * -0.25f).exp()
        return q1 * avg
    }

    private fun QuatF.exp(): QuatF {
        val v = Vec3f(this.x, this.y, this.z)
        val len = v.length()
        val sinLen = sin(len)
        val cosLen = cos(len)
        val scale = if (len > 1e-6f) sinLen / len else 1f
        return QuatF(cosLen, v.x * scale, v.y * scale, v.z * scale)
    }

    private fun log(q: QuatF): QuatF {
        // q = [w, (x, y, z)]
        val v = Vec3f(q.x, q.y, q.z)
        val len = v.length()
        val angle = atan2(len, q.w)
        val scale = if (len > 1e-6f) angle / len else 0f
        return QuatF(0f, v.x * scale, v.y * scale, v.z * scale)
    }

    private fun squad(q1: QuatF, q2: QuatF, s1: QuatF, s2: QuatF, t: Float): QuatF {
        val slerp1 = q1.mix(q2, t)
        val slerp2 = s1.mix(s2, t)
        return slerp1.mix(slerp2, 2f * t * (1f - t))
    }
}

