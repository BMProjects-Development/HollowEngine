package ru.hollowhorizon.hollowengine.addons.physics.collider

import com.github.stephengold.joltjni.*
import com.github.stephengold.joltjni.readonly.ConstShape
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.addons.physics.collider.JoltVolume.Companion.COARSE_MARGIN
import ru.hollowhorizon.hollowengine.common.colliders.ColliderBox
import ru.hollowhorizon.hollowengine.common.colliders.ColliderLines
import ru.hollowhorizon.hollowengine.common.colliders.ColliderVolume
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import net.minecraft.world.phys.Vec3 as McVec3

/**
 * A collider whose shape Jolt knows, placed in its [frame] for one tick or one frame.
 */
internal class JoltVolume(override val frame: ColliderBox, private val shape: JoltColliderShape) : ColliderVolume {
    private val axisX: McVec3 = frame.axisX.normalize()
    private val axisY: McVec3 = frame.axisY.subtract(axisX.scale(axisX.dot(frame.axisY))).normalize()
    private val axisZ: McVec3 = axisX.cross(axisY)

    override val bounds: AABB get() = frame.bounds

    override fun distanceTo(point: McVec3): Double {
        if (!bounds.inflate(COARSE_MARGIN).contains(point)) return distanceToBounds(point)
        val gap = gap(Probe.POINT, point, 1.0, 1.0, 1.0)
        return if (gap <= 0.0) 0.0 else gap + POINT_RADIUS
    }

    override fun clip(start: McVec3, end: McVec3): McVec3? {
        if (!bounds.contains(start) && bounds.clip(start, end).isEmpty) return null
        val length = end.distanceTo(start)
        if (length < EPSILON) return start.takeIf { distanceTo(it) <= RAY_HIT }
        val direction = end.subtract(start).scale(1.0 / length)
        var travelled = 0.0
        repeat(MAX_ADVANCES) {
            val point = start.add(direction.scale(travelled))
            val gap = distanceTo(point)
            if (gap <= RAY_HIT) return point
            travelled += gap
            if (travelled > length) return null
        }
        return null
    }

    override fun penetration(box: AABB): McVec3? {
        if (!box.intersects(bounds)) return null
        val contact =
            query(Probe.BOX, box.center, box.xsize.size(), box.ysize.size(), box.zsize.size(), 0.0) ?: return null
        if (contact.depth <= 0.0) return null
        return contact.axis.scale(-contact.depth)
    }

    override fun sweep(box: AABB, axis: Direction.Axis, distance: Double): Double {
        if (distance == 0.0) return 0.0
        if (penetration(box) != null) return distance
        val direction = McVec3(0.0, 0.0, 0.0).with(axis, if (distance > 0.0) 1.0 else -1.0)
        return travel(box, direction, abs(distance)) * sign(distance)
    }

    override fun escape(box: AABB, direction: McVec3): Double? {
        if (penetration(box) == null) return null
        val far = bounds.size() + box.size() + 1.0
        val outside = box.move(direction.scale(far))
        return far - travel(outside, direction.scale(-1.0), far)
    }

    override fun lerp(next: ColliderVolume, t: Double): ColliderVolume = JoltVolume(frame.lerp(next, t), shape)

    override fun move(x: Double, y: Double, z: Double): ColliderVolume = JoltVolume(frame.move(x, y, z), shape)

    override fun outline(lines: ColliderLines) {
        if (shape.outline.isEmpty()) return frame.outline(lines)
        shape.outline.forEach { (start, end) -> lines.line(toWorld(start), toWorld(end)) }
    }

    private fun toWorld(local: Vec3f): McVec3 =
        frame.center.add(axisX.scale(local.x.toDouble())).add(axisY.scale(local.y.toDouble()))
            .add(axisZ.scale(local.z.toDouble()))

    /**
     * How far from [length] [box] gets along the unit [direction] before it touches the shape. Each step goes as far
     * as the gap left, which cannot reach into a convex shape, and the box stops a hair short of it.
     */
    private fun travel(box: AABB, direction: McVec3, length: Double): Double {
        if (!box.expandTowards(direction.scale(length)).intersects(bounds)) return length
        var travelled = 0.0
        repeat(MAX_ADVANCES) {
            val moved = box.move(direction.scale(travelled))
            val gap = gap(Probe.BOX, moved.center, moved.xsize.size(), moved.ysize.size(), moved.zsize.size())
            if (gap <= CONTACT_GAP * 2.0) return travelled
            travelled += gap - CONTACT_GAP
            if (travelled >= length) return length
        }
        return travelled
    }

    /**
     * How far apart [probe] at [at] and the shape are, zero when they touch or overlap, at most [COARSE_MARGIN].
     */
    private fun gap(probe: Probe, at: McVec3, sizeX: Double, sizeY: Double, sizeZ: Double): Double {
        query(probe, at, sizeX, sizeY, sizeZ, FINE_MARGIN)?.let { return it.separation }
        return query(probe, at, sizeX, sizeY, sizeZ, COARSE_MARGIN)?.separation?.coerceAtLeast(FINE_MARGIN)
            ?: COARSE_MARGIN
    }

    private fun query(probe: Probe, at: McVec3, sizeX: Double, sizeY: Double, sizeZ: Double, margin: Double): Contact? {
        val scratch = SCRATCH.get()
        val offset = at.subtract(frame.center)
        scratch.probeTransform.setTranslation(Vec3(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        scratch.probeScale.set(sizeX.toFloat(), sizeY.toFloat(), sizeZ.toFloat())

        scratch.shapeTransform.setAxisX(axisX.toJolt())
        scratch.shapeTransform.setAxisY(axisY.toJolt())
        scratch.shapeTransform.setAxisZ(axisZ.toJolt())
        val center = shape.centerOfMass
        scratch.shapeTransform.setTranslation(
            axisX.scale(center.x.toDouble()).add(axisY.scale(center.y.toDouble())).add(axisZ.scale(center.z.toDouble()))
                .toJolt()
        )

        scratch.settings.setMaxSeparationDistance(margin.toFloat())
        scratch.collector.reset()
        CollisionDispatch.sCollideShapeVsShape(
            probe.shape, shape.shape, scratch.probeScale, UNIT_SCALE,
            scratch.probeTransform, scratch.shapeTransform,
            scratch.probeIds, scratch.shapeIds, scratch.settings, scratch.collector,
        )
        if (!scratch.collector.hadHit()) return null
        val hit = scratch.collector.hit
        val axis = hit.penetrationAxis
        val direction = McVec3(axis.x.toDouble(), axis.y.toDouble(), axis.z.toDouble())
        val length = direction.length()
        return Contact(
            hit.penetrationDepth.toDouble(),
            if (length < EPSILON) McVec3(0.0, 1.0, 0.0) else direction.scale(1.0 / length)
        )
    }

    private fun distanceToBounds(point: McVec3): Double {
        val x = maxOf(bounds.minX - point.x, 0.0, point.x - bounds.maxX)
        val y = maxOf(bounds.minY - point.y, 0.0, point.y - bounds.maxY)
        val z = maxOf(bounds.minZ - point.z, 0.0, point.z - bounds.maxZ)
        return sqrt(x * x + y * y + z * z)
    }

    private class Contact(val depth: Double, val axis: McVec3) {
        val separation: Double get() = (-depth).coerceAtLeast(0.0)
    }

    private enum class Probe(val shape: ConstShape) {
        BOX(BoxShape(Vec3(0.5f, 0.5f, 0.5f), 0f)), POINT(SphereShape(POINT_RADIUS.toFloat())),
    }

    private class Scratch {
        val probeTransform: Mat44 = Mat44.sIdentity()
        val shapeTransform: Mat44 = Mat44.sIdentity()
        val probeScale = Vec3(1f, 1f, 1f)
        val probeIds = SubShapeIdCreator()
        val shapeIds = SubShapeIdCreator()
        val settings = CollideShapeSettings()
        val collector = ClosestHitCollideShapeCollector()
    }

    private companion object {
        const val EPSILON = 1.0e-9

        /** How far around the shape a gap is measured exactly in one go; see [gap]. */
        const val FINE_MARGIN = 0.2

        /** How far around the shape gaps are measured at all; farther than this is just "far". */
        const val COARSE_MARGIN = 1.0

        /** The gap a stopped box is left at, the same vanilla leaves against blocks. */
        const val CONTACT_GAP = 1.0e-7

        /** How close a ray has to come to count as touching. */
        const val RAY_HIT = 1.0e-3
        const val POINT_RADIUS = 1.0e-3
        const val MIN_SIZE = 1.0e-4
        const val MAX_ADVANCES = 64

        val UNIT_SCALE = Vec3(1f, 1f, 1f)
        val SCRATCH: ThreadLocal<Scratch> = ThreadLocal.withInitial(::Scratch)

        fun Double.size(): Double = coerceAtLeast(MIN_SIZE)

        fun AABB.size(): Double = sqrt(xsize * xsize + ysize * ysize + zsize * zsize)

        fun McVec3.toJolt() = Vec3(x.toFloat(), y.toFloat(), z.toFloat())
    }
}
