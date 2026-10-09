package ru.hollowhorizon.hollowengine.common.models

import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
            3 -> twoBones(joints, lengths[0], lengths[1], reached, bendToward, bendAxis)
            else -> fabrik(joints, lengths, reached, bendToward)
        }
        return Solution(solved, stretch)
    }

    /**
     * A limb of two bones bent at its middle joint as at a hinge. The lower bone turns about the hinge until
     * its end is as far from the root as the goal; then the whole limb swings onto the goal and, with a [pole],
     * turns about the line to the goal until the bend faces it.
     */
    private fun twoBones(
        joints: List<Vec3f>,
        upper: Float,
        lower: Float,
        goal: Vec3f,
        pole: Vec3f?,
        bendAxis: Vec3f,
    ): List<Vec3f> {
        val root = joints[0]
        val knee = root + direction(root, joints[1]) * upper
        val end = knee + direction(joints[1], joints[2]) * lower
        val straight = knee + direction(root, knee) * lower

        val hinge = hingeAxis(root, knee, end, pole, bendAxis)
        val fromKnee = end - knee
        val center = knee + hinge * (hinge dot fromKnee)
        val radial = fromKnee - hinge * (hinge dot fromKnee)
        val radius = radial.length()
        val bent = if (radius < EPSILON) end else {
            val u = radial / radius
            val v = hinge.cross(u, MutableVec3f())
            val toRoot = root - center
            val flat = toRoot - hinge * (hinge dot toRoot)
            val spread = flat.length()
            val base = (toRoot dot toRoot) + radius * radius
            val nearest = sqrt((base - 2f * radius * spread).coerceAtLeast(0f))
            val farthest = sqrt(base + 2f * radius * spread)
            val distance =
                goal.distance(root).coerceIn(nearest + EPSILON, (farthest - EPSILON).coerceAtLeast(nearest + EPSILON))
            val facing = atan2(flat dot v, flat dot u)
            val opening = if (spread < EPSILON) 0f else acos(
                ((base - distance * distance) / (2f * radius * spread)).coerceIn(
                    -1f,
                    1f
                )
            )
            val options = listOf(
                facing + opening,
                facing - opening
            ).map { angle -> center + (u * cos(angle) + v * sin(angle)) * radius }
            if (pole != null) options.minBy { (it - straight) dot (pole - knee) } else options.minBy { it.distance(end) }
        }

        val swing = rotationBetween(bent - root, goal - root)
        var placedKnee = root + rotate(knee - root, swing)
        val placedEnd = root + direction(root, goal) * root.distance(bent)

        if (pole != null) {
            val axis = direction(root, goal)
            val tilted = flatten(rotate(hinge, swing), axis)
            val wanted = flatten((goal - root).cross(pole - root, MutableVec3f()), axis)
            if (tilted.length() > EPSILON && wanted.length() > EPSILON) {
                placedKnee = root + rotate(placedKnee - root, rotationBetween(tilted, wanted))
            }
        }
        return listOf(root, placedKnee, placedEnd)
    }

    /** What the lower bone turns about: square to the limb and the pole, or the limb's own bend, or [bendAxis]. */
    private fun hingeAxis(root: Vec3f, knee: Vec3f, end: Vec3f, pole: Vec3f?, bendAxis: Vec3f): Vec3f {
        val reach = end - root
        pole?.let { reach.cross(it - root, MutableVec3f()) }?.takeIf { it.length() > EPSILON }
            ?.let { return it.normed() }
        (knee - root).cross(end - knee, MutableVec3f()).takeIf { it.length() > EPSILON }?.let { return it.normed() }
        if (bendAxis.length() > EPSILON) return bendAxis.normed()
        return perpendicular(direction(root, end))
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
                val turn =
                    rotationBetween(flatten(points[i] - points[i - 1], axis), flatten(pole - points[i - 1], axis))
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
