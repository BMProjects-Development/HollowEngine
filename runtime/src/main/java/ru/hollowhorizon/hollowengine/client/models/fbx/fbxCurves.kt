package ru.hollowhorizon.hollowengine.client.models.fbx

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

internal fun AnimationCurve.flag(key: Int) = evaluator.flags[key]
internal fun AnimationCurve.evaluate(time: Long) = evaluator.evaluate(time)

/** Expanded tangent data is cached once; sampling a curve then only needs a binary search. */
internal class FbxCurve(private val curve: AnimationCurve) {
    val flags = IntArray(curve.keys.size) { LINEAR }
    private val left = DoubleArray(flags.size)
    private val right = DoubleArray(flags.size)
    private val leftWeight = DoubleArray(flags.size) { 1.0 / 3.0 }
    private val rightWeight = DoubleArray(flags.size) { 1.0 / 3.0 }
    private val attributes = IntArray(flags.size)

    init {
        var key = 0
        if (curve.attributeRefs.isNotEmpty()) {
            curve.attributeRefs.forEachIndexed { index, count -> repeat(count) { attributes[key++] = index } }
        } else for (i in attributes.indices) attributes[i] = if (curve.flags.size == 1) 0 else i
        for (i in flags.indices) {
            val attribute = attributes[i]
            flags[i] = curve.flags.getOrElse(attribute) { LINEAR }
            if (flags[i] and CUBIC != 0) {
                if (attribute * 4 + 3 >= curve.attributes.size) domError(
                    "missing cubic curve attributes",
                    curve.element
                )
                right[i] = data(i, 0)
                if (i + 1 < flags.size) left[i + 1] = data(i, 1)
                val packed = curve.attributes[attribute * 4 + 2].toRawBits()
                if (flags[i] and WEIGHTED_RIGHT != 0) rightWeight[i] = (packed and 0xffff) * 0.0001
                if (i + 1 < flags.size && flags[i] and WEIGHTED_LEFT != 0) leftWeight[i + 1] = (packed ushr 16) * 0.0001
            } else if (i + 1 < flags.size) {
                val slope = if (flags[i] and CONSTANT != 0) 0.0 else slope(i, i + 1)
                right[i] = slope
                left[i + 1] = slope
            }
        }
        for (i in flags.indices) {
            if (flags[i] and CUBIC == 0) continue
            when {
                flags[i] and TCB != 0 -> {
                    val tension = data(i, 0)
                    val continuity = data(i, 1)
                    val bias = data(i, 2)
                    val previous = if (i > 0) slope(i - 1, i) else 0.0
                    val next = if (i < flags.lastIndex) slope(i, i + 1) else 0.0
                    val factor = (if (i == 0 || i == flags.lastIndex) 1.0 else 0.5) * (1 - tension)
                    left[i] = factor * ((1 + bias) * (1 - continuity) * previous + (1 - bias) * (1 + continuity) * next)
                    right[i] =
                        factor * ((1 + bias) * (1 + continuity) * previous + (1 - bias) * (1 - continuity) * next)
                }

                flags[i] and USER == 0 -> {
                    val incomingBias = -left[i]
                    val outgoingBias = right[i]
                    if (abs(left[i] + right[i]) <= 1e-4) {
                        val slope = autoSlope(i, outgoingBias)
                        left[i] = slope
                        right[i] = slope
                    } else {
                        left[i] = autoSlope(i, incomingBias)
                        right[i] = autoSlope(i, outgoingBias)
                    }
                }
            }
        }
        if ((left + right).any { !it.isFinite() } || (leftWeight + rightWeight).any { it !in 0.0..1.0 }) {
            domError("invalid curve tangents", curve.element)
        }
    }

    fun evaluate(time: Long): Float {
        val keys = curve.keys
        val values = curve.values
        if (keys.isEmpty()) return curve.element.scope["Default"]?.get(0)?.parseAsFloat ?: 0f
        if (time <= keys.first()) return values.first()
        if (time >= keys.last()) return values.last()
        val search = keys.binarySearch(time)
        if (search >= 0) return values[search]
        val index = -search - 2
        val alpha = (time - keys[index]).toDouble() / (keys[index + 1] - keys[index])
        if (flags[index] and CONSTANT != 0) return if (flags[index] and CONSTANT_NEXT != 0) values[index + 1] else values[index]
        if (flags[index] and CUBIC == 0) return (values[index] + (values[index + 1] - values[index]) * alpha).toFloat()
        val a = rightWeight[index]
        val b = leftWeight[index + 1]
        var low = 0.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) * 0.5
            if (bezier(mid, 0.0, a, 1.0 - b, 1.0) < alpha) low = mid else high = mid
        }
        val seconds = (keys[index + 1] - keys[index]) / FBX_TIME_UNITS_PER_SECOND
        val first = values[index].toDouble()
        val last = values[index + 1].toDouble()
        return bezier(
            (low + high) * 0.5, first, first + right[index] * seconds * a, last - left[index + 1] * seconds * b, last
        ).toFloat()
    }

    private fun data(key: Int, component: Int) = curve.attributes[attributes[key] * 4 + component].toDouble()
    private fun duration(a: Int, b: Int) = (curve.keys[b] - curve.keys[a]) / FBX_TIME_UNITS_PER_SECOND
    private fun slope(a: Int, b: Int) = (curve.values[b] - curve.values[a]) / duration(a, b)

    private fun autoSlope(i: Int, bias: Double): Double {
        val flags = flags[i]
        val before = i > 0
        val after = i < curve.keys.lastIndex
        val value = curve.values[i].toDouble()
        val previous = if (before) curve.values[i - 1].toDouble() else value
        val next = if (after) curve.values[i + 1].toDouble() else value
        if (flags and CLAMP != 0 && min(
                if (before) abs(previous - value) else Double.POSITIVE_INFINITY,
                if (after) abs(next - value) else Double.POSITIVE_INFINITY
            ) <= 1e-6
        ) return 0.0
        if (!before || !after) {
            if (flags and PROGRESSIVE != 0 || (!before && !after)) return 0.0
            var result = if (before) slope(i - 1, i) else slope(i, i + 1)
            if (flags and TIME_INDEPENDENT == 0) {
                val excess = max(abs(bias) / 100.0 - 5.0, 0.0)
                result += excess * excess * sign(bias) * 40.0
            }
            return result
        }
        val dl = slope(i - 1, i)
        val dr = slope(i, i + 1)
        var result = slope(i - 1, i + 1)
        if (flags and TIME_INDEPENDENT == 0) {
            val ratio = duration(i - 1, i) / duration(i - 1, i + 1)
            result = (result + dl * (1.0 - ratio) + dr * ratio) * 0.5
            val weight = abs(bias) / 100.0
            val target = if (bias > 0.0) dr else dl
            val delta = target - result
            result += (target - result) * weight
            val excess = max(weight - 5.0, 0.0)
            result += excess * excess * sign(if (abs(delta) > 1e-5) delta else bias) * 40.0
        }
        if (flags and PROGRESSIVE != 0) {
            val direction = sign(result)
            val leftLimit =
                if (leftWeight[i] > 0) direction * (value - previous) / (leftWeight[i] * duration(i - 1, i)) else 0.0
            val rightLimit =
                if (rightWeight[i] > 0) direction * (next - value) / (rightWeight[i] * duration(i, i + 1)) else 0.0
            result = direction * min(abs(result), max(0.0, min(leftLimit, rightLimit)))
        }
        return result
    }

    private fun bezier(t: Double, a: Double, b: Double, c: Double, d: Double): Double {
        val s = 1 - t
        return s * s * s * a + 3 * s * s * t * b + 3 * s * t * t * c + t * t * t * d
    }

    companion object {
        const val CONSTANT = 0x2
        const val LINEAR = 0x4
        const val CUBIC = 0x8
        const val CONSTANT_NEXT = 0x100
        private const val TCB = 0x200
        private const val USER = 0x400
        private const val CLAMP = 0x1000
        private const val TIME_INDEPENDENT = 0x2000
        private const val PROGRESSIVE = 0x4000
        private const val WEIGHTED_RIGHT = 0x1000000
        private const val WEIGHTED_LEFT = 0x2000000
    }
}
