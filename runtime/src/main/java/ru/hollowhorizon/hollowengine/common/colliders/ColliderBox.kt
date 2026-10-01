package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A box in the world: a center and three half-axes, the vectors from the center to the middle of three
 * of its faces. An oriented box has them turned with its bone; a world-aligned one has them along X, Y
 * and Z. A bone that is scaled unevenly can skew them, so nothing here assumes they are perpendicular.
 */
class ColliderBox(val center: Vec3, val axisX: Vec3, val axisY: Vec3, val axisZ: Vec3) {
    private val axes = arrayOf(axisX, axisY, axisZ)

    /** Rows of the inverse of the axes matrix, or null for a box flattened to nothing. */
    private val inverse: Array<Vec3>? = invert(axisX, axisY, axisZ)

    val bounds: AABB = run {
        val rx = abs(axisX.x) + abs(axisY.x) + abs(axisZ.x)
        val ry = abs(axisX.y) + abs(axisY.y) + abs(axisZ.y)
        val rz = abs(axisX.z) + abs(axisY.z) + abs(axisZ.z)
        AABB(center.x - rx, center.y - ry, center.z - rz, center.x + rx, center.y + ry, center.z + rz)
    }

    /** The eight corners of the box. */
    fun corners(): List<Vec3> = buildList {
        for (x in SIGNS) for (y in SIGNS) for (z in SIGNS) {
            add(center.add(axisX.scale(x)).add(axisY.scale(y)).add(axisZ.scale(z)))
        }
    }

    /** [point] in the box's own coordinates, where the box spans -1..1 on every axis. */
    fun toLocal(point: Vec3): Vec3? {
        val rows = inverse ?: return null
        val relative = point.subtract(center)
        return Vec3(rows[0].dot(relative), rows[1].dot(relative), rows[2].dot(relative))
    }

    fun contains(point: Vec3): Boolean {
        val local = toLocal(point) ?: return false
        return abs(local.x) <= 1.0 && abs(local.y) <= 1.0 && abs(local.z) <= 1.0
    }

    /** How far [point] is from the box; zero inside it. */
    fun distanceTo(point: Vec3): Double {
        val local = toLocal(point) ?: return point.distanceTo(center)
        val nearest = center
            .add(axisX.scale(local.x.coerceIn(-1.0, 1.0)))
            .add(axisY.scale(local.y.coerceIn(-1.0, 1.0)))
            .add(axisZ.scale(local.z.coerceIn(-1.0, 1.0)))
        return nearest.distanceTo(point)
    }

    /** Where the segment from [start] to [end] first touches the box; [start] itself when it begins inside. */
    fun clip(start: Vec3, end: Vec3): Vec3? {
        val from = toLocal(start) ?: return null
        val to = toLocal(end) ?: return null
        var enter = 0.0
        var exit = 1.0
        for (axis in 0 until 3) {
            val origin = from.component(axis)
            val delta = to.component(axis) - origin
            if (abs(delta) < EPSILON) {
                if (abs(origin) > 1.0) return null
                continue
            }
            val first = (-1.0 - origin) / delta
            val second = (1.0 - origin) / delta
            enter = max(enter, min(first, second))
            exit = min(exit, max(first, second))
            if (enter > exit) return null
        }
        return start.add(end.subtract(start).scale(enter))
    }

    /**
     * How far [box] has to move to stop overlapping this collider, along the axis where that is
     * shortest, pointing away from the collider; null when they do not overlap.
     */
    fun penetration(box: AABB): Vec3? {
        val half = Vec3(box.xsize / 2.0, box.ysize / 2.0, box.zsize / 2.0)
        val between = box.center.subtract(center)
        var best: Vec3? = null
        var bestOverlap = Double.MAX_VALUE

        for (candidate in separatingAxes()) {
            val length = candidate.length()
            if (length < EPSILON) continue
            val axis = candidate.scale(1.0 / length)
            val reach = axes.sumOf { abs(it.dot(axis)) } +
                half.x * abs(axis.x) + half.y * abs(axis.y) + half.z * abs(axis.z)
            val distance = between.dot(axis)
            val overlap = reach - abs(distance)
            if (overlap <= 0.0) return null
            if (overlap < bestOverlap) {
                bestOverlap = overlap
                best = axis.scale(if (distance < 0.0) -overlap else overlap)
            }
        }
        return best
    }

    /** The face normals of both boxes and the crossings of their edges: if any of them separates, both do not overlap. */
    private fun separatingAxes(): List<Vec3> = buildList {
        add(axisY.cross(axisZ))
        add(axisZ.cross(axisX))
        add(axisX.cross(axisY))
        addAll(WORLD_AXES)
        axes.forEach { edge -> WORLD_AXES.forEach { add(edge.cross(it)) } }
    }

    companion object {
        private const val EPSILON = 1.0e-9
        private val SIGNS = doubleArrayOf(-1.0, 1.0)
        private val WORLD_AXES = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))

        /**
         * The unit cube around the origin carried by [matrix], which places it relative to [origin].
         * Keeping the matrix relative keeps it precise however far the entity is from the world origin.
         */
        fun of(matrix: Mat4f, origin: Vec3): ColliderBox {
            val point = MutableVec3f()
            matrix.transform(Vec3f.ZERO, 1f, point)
            val center = origin.add(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
            return ColliderBox(center, matrix.axis(1f, 0f, 0f), matrix.axis(0f, 1f, 0f), matrix.axis(0f, 0f, 1f))
        }

        /** A box square to the world, [halfSize] from [center] along each axis. */
        fun aligned(center: Vec3, halfSize: Vec3): ColliderBox = ColliderBox(
            center,
            Vec3(halfSize.x, 0.0, 0.0),
            Vec3(0.0, halfSize.y, 0.0),
            Vec3(0.0, 0.0, halfSize.z),
        )

        private fun Mat4f.axis(x: Float, y: Float, z: Float): Vec3 {
            val result = transform(Vec3f(x * 0.5f, y * 0.5f, z * 0.5f), 0f, MutableVec3f())
            return Vec3(result.x.toDouble(), result.y.toDouble(), result.z.toDouble())
        }

        private fun Vec3.component(axis: Int): Double = when (axis) {
            0 -> x
            1 -> y
            else -> z
        }

        private fun invert(x: Vec3, y: Vec3, z: Vec3): Array<Vec3>? {
            val yz = y.cross(z)
            val determinant = x.dot(yz)
            if (abs(determinant) < EPSILON) return null
            val scale = 1.0 / determinant
            return arrayOf(yz.scale(scale), z.cross(x).scale(scale), x.cross(y).scale(scale))
        }
    }
}
