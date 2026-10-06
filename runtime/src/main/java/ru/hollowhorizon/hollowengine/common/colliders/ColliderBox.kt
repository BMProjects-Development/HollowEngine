package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.withSign

/**
 * A box in the world: a center and three half-axes, the vectors from the center to the middle of three
 * of its faces. An oriented box has them turned with its bone; a world-aligned one has them along X, Y
 * and Z. A bone that is scaled unevenly can skew them, so nothing here assumes they are perpendicular.
 */
class ColliderBox(override val center: Vec3, val axisX: Vec3, val axisY: Vec3, val axisZ: Vec3) : ColliderVolume {
    private val axes = arrayOf(axisX, axisY, axisZ)

    /** Rows of the inverse of the axes matrix, or null for a box flattened to nothing. */
    private val inverse: Array<Vec3>? = invert(axisX, axisY, axisZ)

    override val frame: ColliderBox get() = this

    /** Half the box's thinnest side. */
    val thinnest: Double get() = axes.minOf { it.length() }

    override val bounds: AABB = run {
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

    override fun distanceTo(point: Vec3): Double {
        val local = toLocal(point) ?: return point.distanceTo(center)
        val nearest = center
            .add(axisX.scale(local.x.coerceIn(-1.0, 1.0)))
            .add(axisY.scale(local.y.coerceIn(-1.0, 1.0)))
            .add(axisZ.scale(local.z.coerceIn(-1.0, 1.0)))
        return nearest.distanceTo(point)
    }

    override fun clip(start: Vec3, end: Vec3): Vec3? {
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

    override fun penetration(box: AABB): Vec3? {
        val between = box.center.subtract(center)
        var best: Vec3? = null
        var bestOverlap = Double.MAX_VALUE

        for (axis in separatingAxes) {
            val distance = between.dot(axis)
            val overlap = reach(axis, box) - abs(distance)
            if (overlap <= 0.0) return null
            if (overlap < bestOverlap) {
                bestOverlap = overlap
                best = axis.scale(if (distance < 0.0) -overlap else overlap)
            }
        }
        return best
    }

    override fun sweep(box: AABB, axis: Direction.Axis, distance: Double): Double {
        if (distance == 0.0) return 0.0
        val between = box.center.subtract(center)
        var enter = Double.NEGATIVE_INFINITY
        var exit = Double.POSITIVE_INFINITY

        for (normal in separatingAxes) {
            val reach = reach(normal, box)
            val start = between.dot(normal)
            val speed = distance * normal.get(axis)
            if (abs(speed) < EPSILON) {
                if (abs(start) >= reach) return distance
                continue
            }
            val first = (-reach - start) / speed
            val second = (reach - start) / speed
            enter = max(enter, min(first, second))
            exit = min(exit, max(first, second))
            if (enter >= exit) return distance
        }
        if (enter !in 0.0..<1.0) return distance
        val allowed = distance * enter - CONTACT_GAP.withSign(distance)
        return if (allowed * distance <= 0.0) 0.0 else allowed
    }

    override fun escape(box: AABB, direction: Vec3): Double? {
        val between = box.center.subtract(center)
        var shortest = Double.POSITIVE_INFINITY

        for (normal in separatingAxes) {
            val reach = reach(normal, box)
            val start = between.dot(normal)
            if (abs(start) >= reach) return null
            val speed = normal.dot(direction)
            if (abs(speed) < EPSILON) continue
            val needed = if (speed > 0.0) (reach - start) / speed else (-reach - start) / speed
            shortest = min(shortest, needed)
        }
        return shortest
    }

    /**
     * The pose [t] of the way from this one to [next]'s box. Axes are blended and kept at their blended length,
     * which for the turn a bone makes in one tick is close enough to the turn itself.
     */
    override fun lerp(next: ColliderVolume, t: Double): ColliderBox {
        val to = next.frame
        return ColliderBox(center.lerp(to.center, t), blend(axisX, to.axisX, t), blend(axisY, to.axisY, t), blend(axisZ, to.axisZ, t))
    }

    override fun sameAs(other: ColliderVolume): Boolean {
        val box = other.frame
        return center == box.center && axisX == box.axisX && axisY == box.axisY && axisZ == box.axisZ
    }

    override fun move(x: Double, y: Double, z: Double): ColliderBox = ColliderBox(center.add(x, y, z), axisX, axisY, axisZ)

    override fun carry(point: Vec3, next: ColliderVolume): Vec3? {
        val local = toLocal(point) ?: return null
        val to = next.frame
        return to.center.add(to.axisX.scale(local.x)).add(to.axisY.scale(local.y)).add(to.axisZ.scale(local.z))
    }

    override fun outline(lines: ColliderLines) {
        val corners = corners()
        EDGES.forEach { (from, to) -> lines.line(corners[from], corners[to]) }
    }

    /** Half the length of the shadows of this collider and of [box] on [normal], put together. */
    private fun reach(normal: Vec3, box: AABB): Double =
        abs(axisX.dot(normal)) + abs(axisY.dot(normal)) + abs(axisZ.dot(normal)) +
            box.xsize / 2.0 * abs(normal.x) + box.ysize / 2.0 * abs(normal.y) + box.zsize / 2.0 * abs(normal.z)

    /** The face normals of both boxes and the crossings of their edges: if any of them separates, both do not overlap. */
    private val separatingAxes: List<Vec3> by lazy {
        buildList {
            add(axisY.cross(axisZ))
            add(axisZ.cross(axisX))
            add(axisX.cross(axisY))
            addAll(WORLD_AXES)
            axes.forEach { edge -> WORLD_AXES.forEach { add(edge.cross(it)) } }
        }.mapNotNull { candidate -> candidate.length().takeIf { it >= EPSILON }?.let { candidate.scale(1.0 / it) } }
    }

    companion object {
        private const val EPSILON = 1.0e-9

        /** The gap a stopped box is left at, the same vanilla leaves against blocks. */
        private const val CONTACT_GAP = 1.0e-7
        private val SIGNS = doubleArrayOf(-1.0, 1.0)
        private val WORLD_AXES = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0))

        /** Pairs of [corners] joined by an edge: they differ in exactly one axis. */
        private val EDGES = (0 until 8).flatMap { from ->
            listOf(1, 2, 4).mapNotNull { bit -> (from or bit).takeIf { from and bit == 0 }?.let { from to it } }
        }

        private fun blend(from: Vec3, to: Vec3, t: Double): Vec3 {
            val blended = from.lerp(to, t)
            val length = blended.length()
            if (length < EPSILON) return blended
            return blended.scale((from.length() + (to.length() - from.length()) * t) / length)
        }

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

