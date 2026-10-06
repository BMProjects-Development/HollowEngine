package ru.hollowhorizon.hollowengine.addons.physics.collider

import com.github.stephengold.joltjni.BoxShapeSettings
import com.github.stephengold.joltjni.CapsuleShapeSettings
import com.github.stephengold.joltjni.ConvexHullShape
import com.github.stephengold.joltjni.ConvexHullShapeSettings
import com.github.stephengold.joltjni.ConvexShapeSettings
import com.github.stephengold.joltjni.CylinderShapeSettings
import com.github.stephengold.joltjni.Quat
import com.github.stephengold.joltjni.RotatedTranslatedShapeSettings
import com.github.stephengold.joltjni.ShapeRefC
import com.github.stephengold.joltjni.ShapeSettings
import com.github.stephengold.joltjni.SphereShapeSettings
import com.github.stephengold.joltjni.Vec3
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A collider's shape built in Jolt for one size of its box. It is laid out in the box's own space, along the
 * box's axes and around its center, measured in blocks; [outline] traces it in the same space.
 */
internal class JoltColliderShape(
    val shape: ShapeRefC,
    val centerOfMass: Vec3f,
    val outline: List<Pair<Vec3f, Vec3f>>,
)

/**
 * Collider shapes in Jolt: as settings for a body of its own density, and built and kept for colliders.
 */
internal object JoltColliderShapes {
    private const val STEPS_PER_BLOCK = 128f

    /** The thinnest any shape is: Jolt fails without a word on shapes of no size. */
    private const val MIN_EXTENT = 0.005f
    private const val RING_SEGMENTS = 24

    private data class Key(val spec: ColliderShapeSpec, val x: Int, val y: Int, val z: Int)

    private val cache = HashMap<Key, JoltColliderShape?>()

    /** [spec] filling a box of [halfExtents], or null when Jolt cannot build it, and the box stands in for it. */
    fun of(spec: ColliderShapeSpec, halfExtents: Vec3f): JoltColliderShape? {
        if (!JoltNatives.isAvailable) return null
        val key = Key(spec, steps(halfExtents.x), steps(halfExtents.y), steps(halfExtents.z))
        return synchronized(cache) {
            if (cache.containsKey(key)) return@synchronized cache[key]
            val extents = Vec3f(key.x.extent(), key.y.extent(), key.z.extent())
            val built = runCatching { build(spec, extents) }
                .onFailure { HollowEngine.LOGGER.warn("Could not build collider shape {}: {}", spec, it.message) }
                .getOrNull()
            cache[key] = built
            built
        }
    }

    /**
     * [spec] filling a box of [half] as Jolt settings, what a body is built from.
     */
    fun settings(spec: ColliderShapeSpec, half: Vec3f, density: Float? = null): ShapeSettings {
        val safe = Vec3f(half.x.coerceAtLeast(MIN_EXTENT), half.y.coerceAtLeast(MIN_EXTENT), half.z.coerceAtLeast(MIN_EXTENT))
        val (solid, axis) = when (spec) {
            is SphereColliderShape -> SphereShapeSettings(minOf(safe.x, safe.y, safe.z)) to ShapeAxis.Y
            is CapsuleColliderShape -> {
                val (length, radius) = alongAxis(safe, spec.axis)
                CapsuleShapeSettings((length - radius).coerceAtLeast(MIN_EXTENT), radius) to spec.axis
            }

            is CylinderColliderShape -> {
                val (halfHeight, radius) = alongAxis(safe, spec.axis)
                CylinderShapeSettings(halfHeight, radius, minOf(CYLINDER_ROUNDING, radius / 2f, halfHeight / 2f)) to spec.axis
            }

            is HullColliderShape -> hullSettings(spec.points, safe) to ShapeAxis.Y
            else -> BoxShapeSettings(Vec3(safe.x, safe.y, safe.z), minOf(BOX_ROUNDING, minOf(safe.x, safe.y, safe.z) / 2f)) to ShapeAxis.Y
        }
        density?.let(solid::setDensity)
        return standing(solid, axis)
    }

    /** Lines tracing [spec] in a box of [half], in the box's space; the box itself when the shape cannot be built. */
    fun outline(spec: ColliderShapeSpec, half: Vec3f): List<Pair<Vec3f, Vec3f>> =
        of(spec, half)?.outline ?: boxOutline(half)

    fun clear() = synchronized(cache) {
        cache.values.forEach { it?.shape?.close() }
        cache.clear()
    }

    private fun steps(extent: Float): Int = (extent * STEPS_PER_BLOCK).roundToInt().coerceAtLeast(1)

    private fun Int.extent(): Float = (this / STEPS_PER_BLOCK).coerceAtLeast(MIN_EXTENT)

    private fun build(spec: ColliderShapeSpec, half: Vec3f): JoltColliderShape {
        val result = settings(spec, half).create()
        if (result.hasError()) error(result.error)
        val shape = result.get()
        val center = shape.centerOfMass.let { Vec3f(it.x, it.y, it.z) }
        val outline = when (spec) {
            is SphereColliderShape -> sphereOutline(minOf(half.x, half.y, half.z))
            is CapsuleColliderShape -> alongAxis(half, spec.axis).let { (length, radius) ->
                capsuleOutline(spec.axis, (length - radius).coerceAtLeast(MIN_EXTENT), radius)
            }

            is CylinderColliderShape -> alongAxis(half, spec.axis).let { (halfHeight, radius) -> cylinderOutline(spec.axis, halfHeight, radius) }
            is HullColliderShape -> hullOutline(shape, center)
            else -> boxOutline(half)
        }
        return JoltColliderShape(shape, center, outline)
    }

    /** How far the shape reaches along [axis], and how thick it can be across it. */
    private fun alongAxis(half: Vec3f, axis: ShapeAxis): Pair<Float, Float> = when (axis) {
        ShapeAxis.X -> half.x to minOf(half.y, half.z)
        ShapeAxis.Y -> half.y to minOf(half.x, half.z)
        ShapeAxis.Z -> half.z to minOf(half.x, half.y)
    }

    /** A shape Jolt stands on its Y, turned to stand on [axis] instead. */
    private fun standing(settings: ShapeSettings, axis: ShapeAxis): ShapeSettings = when (axis) {
        ShapeAxis.Y -> settings
        ShapeAxis.X -> RotatedTranslatedShapeSettings(Quat.sRotation(Vec3(0f, 0f, 1f), (-PI / 2).toFloat()), settings)
        ShapeAxis.Z -> RotatedTranslatedShapeSettings(Quat.sRotation(Vec3(1f, 0f, 0f), (PI / 2).toFloat()), settings)
    }

    private fun hullSettings(points: List<Vec3f>, half: Vec3f): ConvexShapeSettings {
        val scaled = points.map { Vec3(it.x * half.x, it.y * half.y, it.z * half.z) }
        // A hull of fewer than four points has no inside; the box stands in for it.
        if (scaled.size < MIN_HULL_POINTS) return BoxShapeSettings(Vec3(half.x, half.y, half.z), 0f)
        return ConvexHullShapeSettings(scaled, 0f)
    }

    /** The edges of every face of a built hull, whose points Jolt keeps around its center of mass. */
    private fun hullOutline(shape: ShapeRefC, center: Vec3f): List<Pair<Vec3f, Vec3f>> {
        val hull = shape.ptr as? ConvexHullShape ?: return emptyList()
        val point = { index: Int -> hull.getPoint(index).let { Vec3f(it.x + center.x, it.y + center.y, it.z + center.z) } }
        return buildList {
            for (face in 0 until hull.numFaces) {
                val vertices = IntArray(hull.getNumVerticesInFace(face))
                hull.getFaceVertices(face, vertices.size, vertices)
                vertices.indices.forEach { index -> add(point(vertices[index]) to point(vertices[(index + 1) % vertices.size])) }
            }
        }
    }

    private fun boxOutline(half: Vec3f): List<Pair<Vec3f, Vec3f>> {
        val corners = (0 until 8).map { index ->
            Vec3f(if (index and 1 == 0) -half.x else half.x, if (index and 2 == 0) -half.y else half.y, if (index and 4 == 0) -half.z else half.z)
        }
        return (0 until 8).flatMap { from ->
            listOf(1, 2, 4).mapNotNull { bit -> (from or bit).takeIf { from and bit == 0 }?.let { corners[from] to corners[it] } }
        }
    }

    private fun sphereOutline(radius: Float) = buildList {
        ring(Vec3f.ZERO, Vec3f.X_AXIS, Vec3f.Y_AXIS, radius)
        ring(Vec3f.ZERO, Vec3f.Y_AXIS, Vec3f.Z_AXIS, radius)
        ring(Vec3f.ZERO, Vec3f.Z_AXIS, Vec3f.X_AXIS, radius)
    }

    private fun capsuleOutline(axis: ShapeAxis, halfHeight: Float, radius: Float) = buildList {
        val (along, across, other) = frameOf(axis)
        val top = along * halfHeight
        val bottom = along * -halfHeight
        ring(top, across, other, radius)
        ring(bottom, across, other, radius)
        listOf(across, other).forEach { side ->
            add(top + side * radius to bottom + side * radius)
            add(top - side * radius to bottom - side * radius)
            arc(top, side, along, radius)
            arc(bottom, side, along * -1f, radius)
        }
    }

    private fun cylinderOutline(axis: ShapeAxis, halfHeight: Float, radius: Float) = buildList {
        val (along, across, other) = frameOf(axis)
        val top = along * halfHeight
        val bottom = along * -halfHeight
        ring(top, across, other, radius)
        ring(bottom, across, other, radius)
        listOf(across, other).forEach { side ->
            add(top + side * radius to bottom + side * radius)
            add(top - side * radius to bottom - side * radius)
        }
    }

    /** [axis] and two axes across it. */
    private fun frameOf(axis: ShapeAxis): Triple<Vec3f, Vec3f, Vec3f> = when (axis) {
        ShapeAxis.X -> Triple(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS)
        ShapeAxis.Y -> Triple(Vec3f.Y_AXIS, Vec3f.Z_AXIS, Vec3f.X_AXIS)
        ShapeAxis.Z -> Triple(Vec3f.Z_AXIS, Vec3f.X_AXIS, Vec3f.Y_AXIS)
    }

    /** A circle around [center] in the plane of [first] and [second]. */
    private fun MutableList<Pair<Vec3f, Vec3f>>.ring(center: Vec3f, first: Vec3f, second: Vec3f, radius: Float) =
        curve(center, first, second, radius, 0.0, 2 * PI, RING_SEGMENTS)

    /** Half a circle from -[side] to [side], bulging toward [over]. */
    private fun MutableList<Pair<Vec3f, Vec3f>>.arc(center: Vec3f, side: Vec3f, over: Vec3f, radius: Float) =
        curve(center, side, over, radius, 0.0, PI, RING_SEGMENTS / 2)

    private fun MutableList<Pair<Vec3f, Vec3f>>.curve(
        center: Vec3f,
        first: Vec3f,
        second: Vec3f,
        radius: Float,
        from: Double,
        to: Double,
        segments: Int,
    ) {
        fun at(angle: Double) = center + first * (cos(angle).toFloat() * radius) + second * (sin(angle).toFloat() * radius)
        for (segment in 0 until segments) {
            add(at(from + (to - from) * segment / segments) to at(from + (to - from) * (segment + 1) / segments))
        }
    }

    /** How round a cylinder's edges are, at most: Jolt needs some, and wants less than the cylinder itself. */
    private const val CYLINDER_ROUNDING = 0.02f

    /** How round a body's box edges are, at most; rounder boxes roll and collide more smoothly. */
    private const val BOX_ROUNDING = 0.02f
    private const val MIN_HULL_POINTS = 4
}
