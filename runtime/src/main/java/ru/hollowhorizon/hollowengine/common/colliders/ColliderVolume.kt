package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/** A collider where it stands in the world for one tick or one frame. */
interface ColliderVolume {
    /** The box the collider fills: where it is, how it is turned, how big it is. */
    val frame: ColliderBox

    val center: Vec3 get() = frame.center

    val bounds: AABB

    /** How far [point] is from the shape; zero inside it. */
    fun distanceTo(point: Vec3): Double

    /** Where the segment from [start] to [end] first touches the shape; [start] itself when it begins inside. */
    fun clip(start: Vec3, end: Vec3): Vec3?

    /**
     * How far [box] has to move to stop overlapping the shape, along the shortest way, pointing away from
     * it; null when they do not overlap.
     */
    fun penetration(box: AABB): Vec3?

    /**
     * How far from [distance] [box] can move along [axis] before it touches the shape. A box that already
     * overlaps it is let go: it has to be able to walk out.
     */
    fun sweep(box: AABB, axis: Direction.Axis, distance: Double): Double

    /** How far [box] has to move along the unit [direction] to stop overlapping the shape; null when they do not overlap. */
    fun escape(box: AABB, direction: Vec3): Double?

    /** How far up [box] has to move to stop overlapping the shape; null when they do not overlap. */
    fun lift(box: AABB): Double? = escape(box, UP)

    /** The pose [t] of the way from this one to [next], the same collider a tick later. */
    fun lerp(next: ColliderVolume, t: Double): ColliderVolume

    fun move(x: Double, y: Double, z: Double): ColliderVolume

    /** Whether this is the very pose [other] is: a collider that has not moved. */
    fun sameAs(other: ColliderVolume): Boolean = frame.sameAs(other.frame)

    /** Where [point], carried by this collider, is once it has become [next]: how a moving collider moves what it holds. */
    fun carry(point: Vec3, next: ColliderVolume): Vec3? = frame.carry(point, next.frame)

    /** Traces the shape with lines, for the hitbox view and the editor. */
    fun outline(lines: ColliderLines)

    /**
     * Where this collider first touched [box] on its way here from [previous], over the last tick.
     */
    fun firstTouch(previous: ColliderVolume?, box: AABB): ColliderTouch? {
        if (previous == null) return penetration(box)?.let { ColliderTouch(this, it) }
        val steps = touchSteps(previous.frame, frame, box)
        for (step in 1..steps) {
            val pose = if (step == steps) this else previous.lerp(this, step.toDouble() / steps)
            pose.penetration(box)?.let { return ColliderTouch(pose, it) }
        }
        return null
    }
}

private val UP = Vec3(0.0, 1.0, 0.0)

/** Where line tracing a collider go. */
fun interface ColliderLines {
    fun line(start: Vec3, end: Vec3)
}

/** A collider overlapping a box: the [pose] it overlapped it in, and the [overlap] that pushes the box out of it. */
class ColliderTouch(val pose: ColliderVolume, val overlap: Vec3)

/** How many poses between [previous] and [next] are tried, so that none skips over [box] or over the collider itself. */
private fun touchSteps(previous: ColliderBox, next: ColliderBox, box: AABB): Int {
    val travel = previous.corners().zip(next.corners()).maxOf { (from, to) -> from.distanceTo(to) }
    val thinnest = minOf(box.xsize, box.ysize, box.zsize, next.thinnest * 2.0).coerceAtLeast(MIN_STEP)
    return ceil(travel / (thinnest / 2.0)).toInt().coerceIn(1, MAX_STEPS)
}

/** The most poses tried along one tick of a collider's motion. */
private const val MAX_STEPS = 16

/** The thinnest anything is taken to be when working out how many poses to try. */
private const val MIN_STEP = 0.05
