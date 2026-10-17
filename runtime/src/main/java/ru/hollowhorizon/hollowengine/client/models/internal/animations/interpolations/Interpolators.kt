package ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations

import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

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