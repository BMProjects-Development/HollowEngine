package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

internal data class FbxSamples(val times: List<Float>, val values: List<FloatArray>)

/** The runtime uses float seconds. Discontinuities occupy adjacent representable times. */
internal fun sampleCurves(
    curves: List<AnimationCurve?>,
    defaults: FloatArray,
    start: Long,
    stop: Long,
    rotationOrder: Model.RotOrder? = null,
    transform: (FloatArray) -> FloatArray = { it },
): FbxSamples {
    fun seconds(time: Long) = ((time - start) / FBX_TIME_UNITS_PER_SECOND).toFloat()
    fun value(time: Long): FloatArray {
        val result = transform(FloatArray(defaults.size) { axis -> curves[axis]?.evaluate(time) ?: defaults[axis] })
        if (result.any { !it.isFinite() }) parseError("non-finite animation sample")
        return result
    }
    fun quaternion(value: FloatArray) = getRotationMatrix(rotationOrder!!, Vec3f(value[0], value[1], value[2])).getRotation(MutableQuatF())
    val samples = sortedMapOf(seconds(start) to value(start), seconds(stop) to value(stop))
    val keyTimes = sortedSetOf(start, stop)
    curves.filterNotNull().forEach { curve -> keyTimes += curve.keys.filter { it in start..stop } }
    keyTimes.forEach { samples[seconds(it)] = value(it) }
    for (curve in curves.filterNotNull()) {
        for (i in 0 until curve.keys.lastIndex) {
            val flag = curve.flag(i)
            if (flag and FbxCurve.CONSTANT == 0) continue
            val next = flag and FbxCurve.CONSTANT_NEXT != 0
            val boundary = if (next) curve.keys[i] else curve.keys[i + 1]
            if (boundary !in start..stop) continue
            val tick = if (next) boundary + 1 else boundary - 1
            val time = if (next) Math.nextUp(seconds(boundary)) else Math.nextDown(seconds(boundary))
            if (time >= 0f && time <= seconds(stop)) samples[time] = value(tick)
        }
    }
    fun subdivide(a: Float, b: Float, depth: Int) {
        if (Math.nextUp(a) >= b) return
        val va = samples.getValue(a)
        val vb = samples.getValue(b)
        val qa = if (rotationOrder != null) quaternion(va) else null
        val qb = if (rotationOrder != null) quaternion(vb) else null
        var split = false
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            val time = a + (b - a) * fraction
            if (time <= a || time >= b) continue
            val actual = value(start + (time.toDouble() * FBX_TIME_UNITS_PER_SECOND).roundToLong())
            if (rotationOrder == null) {
                if (actual.indices.any { axis ->
                        val expected = va[axis] + (vb[axis] - va[axis]) * fraction
                        abs(actual[axis] - expected) > VALUE_TOLERANCE * max(1f, max(abs(actual[axis]), abs(expected)))
                    }) split = true
            } else if (abs(quaternion(actual).dot(qa!!.mix(qb!!, fraction))) < 1f - ROTATION_DOT_TOLERANCE) split = true
        }
        if (rotationOrder != null && va.indices.any { abs(va[it] - vb[it]) > 90f }) split = true
        if (split) {
            if (depth >= MAX_DEPTH || samples.size >= MAX_SAMPLES) parseError("animation exceeds sampling precision or size limit")
            val middle = a + (b - a) * 0.5f
            if (middle <= a || middle >= b) return
            samples[middle] = value(start + (middle.toDouble() * FBX_TIME_UNITS_PER_SECOND).roundToLong())
            subdivide(a, middle, depth + 1)
            subdivide(middle, b, depth + 1)
        }
    }
    samples.keys.toList().zipWithNext { a, b -> subdivide(a, b, 0) }
    return FbxSamples(samples.keys.toList(), samples.values.toList())
}

private const val VALUE_TOLERANCE = 1e-4f
private const val ROTATION_DOT_TOLERANCE = 1e-6f
private const val MAX_DEPTH = 24
private const val MAX_SAMPLES = 100_000
