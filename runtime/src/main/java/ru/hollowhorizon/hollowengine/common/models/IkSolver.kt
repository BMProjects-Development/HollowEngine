package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where the joints of a chain go so its end reaches a goal. It works on positions alone, in whatever space
 * they are given; turning the bones to match is the caller's.
 */
object IkSolver {
    /** The joints, root first, and how much the chain was stretched to get there; 1 is not at all. */
    class Solution(val joints: List<Vec3f>, val stretch: Float)

    private const val EPSILON = 1e-5f
    private const val ITERATIONS = 16
    private const val TOLERANCE = 1e-4f

    /**
     * Brings the last of [joints] to [goal], the first staying where it is.
     */
    fun solve(
        joints: List<Vec3f>,
        goal: Vec3f,
        bendToward: Vec3f?,
        bendAxis: Vec3f,
        maxStretch: Float,
        restReach: Float = Float.POSITIVE_INFINITY,
    ): Solution {
        if (joints.size < 2) return Solution(joints, 1f)
        val lengths = FloatArray(joints.size - 1) { joints[it].distance(joints[it + 1]) }
        val reach = minOf(lengths.sum(), restReach)
        val root = joints.first()
        val distance = root.distance(goal)
        if (reach < EPSILON || distance < EPSILON) return Solution(joints, 1f)

        val stretch = if (distance > reach) (distance / reach).coerceAtMost(maxStretch.coerceAtLeast(1f)) else 1f
        for (i in lengths.indices) lengths[i] *= stretch
        val reached = if (distance > reach * stretch) root + direction(root, goal) * (reach * stretch) else goal

        val solved = when (joints.size) {
            2 -> listOf(root, root + direction(root, goal) * lengths[0])
            3 -> twoBones(joints, lengths[0], lengths[1], reached, bendToward ?: joints[1], bendAxis)
            else -> fabrik(joints, lengths, reached, bendToward)
        }
        return Solution(solved, stretch)
    }

    /** The exact answer for a limb of two bones: the elbow goes where both bones still fit, on the bend's side. */
    private fun twoBones(joints: List<Vec3f>, upper: Float, lower: Float, goal: Vec3f, hint: Vec3f, bendAxis: Vec3f): List<Vec3f> {
        val root = joints[0]
        val toGoal = goal - root
        val along = toGoal.normed()
        val distance = toGoal.length().coerceIn(abs(upper - lower) + EPSILON, upper + lower - EPSILON)

        var normal: Vec3f = along.cross(hint - root, MutableVec3f())
        if (normal.length() < EPSILON) normal = bendAxis - along * (bendAxis dot along)
        if (normal.length() < EPSILON) normal = perpendicular(along)
        val side = normal.normed().cross(along, MutableVec3f()).norm()

        val cos = ((upper * upper + distance * distance - lower * lower) / (2f * upper * distance)).coerceIn(-1f, 1f)
        val sin = sqrt(1f - cos * cos)
        val middle = root + (along * cos + side * sin) * upper
        return listOf(root, middle, root + along * distance)
    }

    private fun fabrik(joints: List<Vec3f>, lengths: FloatArray, goal: Vec3f, pole: Vec3f?): List<Vec3f> {
        val points = joints.map { MutableVec3f(it) }
        val root = Vec3f(joints.first())
        val last = points.lastIndex

        if (root.distance(goal) >= lengths.sum()) {
            val along = direction(root, goal)
            for (i in 1..last) points[i].set(points[i - 1] + along * lengths[i - 1])
            return points
        }

        for (iteration in 0 until ITERATIONS) {
            points[last].set(goal)
            for (i in last - 1 downTo 0) points[i].set(points[i + 1] + direction(points[i + 1], points[i]) * lengths[i])
            points[0].set(root)
            for (i in 1..last) points[i].set(points[i - 1] + direction(points[i - 1], points[i]) * lengths[i - 1])
            if (points[last].distance(goal) < TOLERANCE) break
        }

        if (pole != null) {
            for (i in 1 until last) {
                val axis = points[i + 1] - points[i - 1]
                if (axis.length() < EPSILON) continue
                val turn = rotationBetween(flatten(points[i] - points[i - 1], axis), flatten(pole - points[i - 1], axis))
                points[i].set(points[i - 1] + rotate(points[i] - points[i - 1], turn))
            }
        }
        return points
    }

    /** The shortest turn that points [from] along [to]. */
    fun rotationBetween(from: Vec3f, to: Vec3f): QuatF {
        if (from.length() < EPSILON || to.length() < EPSILON) return QuatF.IDENTITY
        val start = from.normed()
        val end = to.normed()
        val cos = start dot end
        if (cos > 1f - EPSILON) return QuatF.IDENTITY
        if (cos < -1f + EPSILON) {
            val axis = perpendicular(start)
            return QuatF(axis.x, axis.y, axis.z, 0f)
        }
        val axis = start.cross(end, MutableVec3f())
        return MutableQuatF(axis.x, axis.y, axis.z, 1f + cos).norm()
    }

    /** [vector] turned by [rotation]. */
    fun rotate(vector: Vec3f, rotation: QuatF): Vec3f {
        val pure = QuatF(vector.x, vector.y, vector.z, 0f)
        val turned = rotation * pure * rotation.inverted()
        return Vec3f(turned.x, turned.y, turned.z)
    }

    private fun direction(from: Vec3f, to: Vec3f): Vec3f {
        val delta = to - from
        return if (delta.length() < EPSILON) Vec3f.Y_AXIS else delta.normed()
    }

    /** [vector] with its part along [axis] taken out. */
    private fun flatten(vector: Vec3f, axis: Vec3f): Vec3f {
        val unit = axis.normed()
        return vector - unit * (vector dot unit)
    }

    private fun perpendicular(axis: Vec3f): Vec3f {
        val other = if (abs(axis.x) < 0.9f) Vec3f.X_AXIS else Vec3f.Y_AXIS
        return axis.cross(other, MutableVec3f()).norm()
    }
}
