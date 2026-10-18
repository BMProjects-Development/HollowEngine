package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Plays several clips at once and blends them by where one or two parameters fall among the points the
 * clips are placed at: walking into running by speed, or forward, sideways and backward walking by the
 * direction of movement.
 */
@Serializable
@SerialName("hollowengine:animator/state/blend")
data class BlendStateSpec(
    override val id: String,
    val motions: List<BlendMotion> = emptyList(),
    val x: AnimationExpression = AnimationExpression.ZERO,
    val y: AnimationExpression? = null,
    val playMode: AnimationPlayMode = AnimationPlayMode.Loop,
    val speed: AnimationExpression = AnimationExpression.ONE,
    val xRange: BlendRange = BlendRange.UNIT,
    val yRange: BlendRange = BlendRange.SIGNED,
) : AnimationControllerStateSpec() {
    val isPlanar: Boolean get() = y != null

    /**
     * This blend with one parameter or two. A plane reads its points as directions, so one that still
     * spans the default line range is widened to both sides.
     */
    fun withPlanar(planar: Boolean): BlendStateSpec = if (!planar) copy(y = null)
    else copy(y = y ?: AnimationExpression.ZERO, xRange = if (xRange == BlendRange.UNIT) BlendRange.SIGNED else xRange)

    override fun withId(id: String) = copy(id = id)

    override fun expressions() = listOfNotNull(x, y, speed)

    fun withMotion(index: Int, motion: BlendMotion): BlendStateSpec =
        copy(motions = motions.toMutableList().also { it[index] = motion })

    fun withoutMotion(index: Int): BlendStateSpec = copy(motions = motions.filterIndexed { at, _ -> at != index })
}

/**
 * One clip of blend and point it plays fully at; [y] is ignored while the blend has one parameter.
 * [speed] scales how fast the clip plays on its own.
 */
@Serializable
data class BlendMotion(
    val animation: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val speed: Float = 1f,
)

/** A span of one blend parameter, from [min] to [max]. */
@Serializable
data class BlendRange(val min: Float = 0f, val max: Float = 1f) {
    fun clamp(value: Float): Float = value.coerceIn(min, max)

    companion object {
        val UNIT = BlendRange(0f, 1f)
        val SIGNED = BlendRange(-1f, 1f)
    }
}

/**
 * How much each clip of a blend contributes at a given point. Weights are never negative and sum to one.
 */
object BlendWeights {
    private const val ANGLE_SCALE = 2f
    private const val EPSILON = 1e-6f

    fun of(spec: BlendStateSpec, x: Float, y: Float): FloatArray =
        if (spec.isPlanar) planar(spec.motions, x, y) else linear(spec.motions.map(BlendMotion::x), x)

    /**
     * Along one axis: the two points around [x] share the weight, and past either end the outermost point
     * takes it all.
     */
    fun linear(points: List<Float>, x: Float): FloatArray {
        val weights = FloatArray(points.size)
        if (points.isEmpty()) return weights

        val order = points.indices.sortedBy { points[it] }
        val first = order.first()
        val last = order.last()
        when {
            x <= points[first] -> weights[first] = 1f
            x >= points[last] -> weights[last] = 1f
            else -> {
                val upper = order.indexOfFirst { points[it] >= x }
                val below = order[upper - 1]
                val above = order[upper]
                val span = points[above] - points[below]
                val t = if (span <= EPSILON) 1f else (x - points[below]) / span
                weights[below] = 1f - t
                weights[above] = t
            }
        }
        return weights
    }

    fun planar(motions: List<BlendMotion>, x: Float, y: Float): FloatArray {
        val count = motions.size
        val weights = FloatArray(count)
        if (count == 0) return weights
        if (count == 1) {
            weights[0] = 1f
            return weights
        }

        val length = hypot(x, y)
        var total = 0f
        for (i in 0 until count) {
            val from = motions[i]
            val fromLength = hypot(from.x, from.y)
            var influence = 1f
            for (j in 0 until count) {
                if (j == i) continue
                val to = motions[j]
                val toLength = hypot(to.x, to.y)
                val mean = (fromLength + toLength) / 2f
                if (mean <= EPSILON) continue

                val directed = fromLength > EPSILON && toLength > EPSILON
                val edgeRadial = (toLength - fromLength) / mean
                val edgeAngle = if (directed) angle(from.x, from.y, to.x, to.y) * ANGLE_SCALE else 0f
                val edgeSquared = edgeRadial * edgeRadial + edgeAngle * edgeAngle
                if (edgeSquared <= EPSILON) continue

                val pointRadial = (length - fromLength) / mean
                val pointAngle = if (directed && length > EPSILON) angle(from.x, from.y, x, y) * ANGLE_SCALE else 0f
                influence = min(influence, 1f - (pointRadial * edgeRadial + pointAngle * edgeAngle) / edgeSquared)
            }
            weights[i] = influence.coerceAtLeast(0f)
            total += weights[i]
        }

        if (total <= EPSILON) {
            weights.fill(0f)
            weights[nearest(motions, x, y)] = 1f
            return weights
        }
        for (i in 0 until count) weights[i] /= total
        return weights
    }

    private fun angle(ax: Float, ay: Float, bx: Float, by: Float): Float = atan2(ax * by - ay * bx, ax * bx + ay * by)

    private fun nearest(motions: List<BlendMotion>, x: Float, y: Float): Int =
        motions.indices.minBy { hypot(motions[it].x - x, motions[it].y - y) }
}
