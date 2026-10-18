package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-tick limits of a flier. Speeds are in blocks per tick.
 *
 * @property acceleration largest velocity change per tick
 * @property turn largest course change per tick, in radians
 * @property climbSine sine of the steepest climb or dive
 * @property minSpeed speed a glider never drops below; 0 for a hovering flier
 */
internal class FlightLimits(
    val fullSpeed: Double,
    val acceleration: Double,
    val turn: Double,
    val climbSine: Double,
    val minSpeed: Double,
)

/** Computes the next-tick velocity of a flier. All speeds are in blocks per tick. */
internal object FlightSteering {
    private const val EPSILON = 1.0e-6

    /**
     * Velocity of a hovering flier: moves straight toward its goal and can stop or reverse at any time.
     *
     * @param toward offset from the flier to its goal; it never overshoots this in one tick
     * @param speed wanted speed
     */
    fun hover(velocity: Vec3, toward: Vec3, speed: Double, limits: FlightLimits): Vec3 {
        val distance = toward.length()
        var wanted = if (distance < EPSILON) Vec3.ZERO else toward.scale(min(speed, distance) / distance)
        val vertical = limits.fullSpeed * limits.climbSine
        if (abs(wanted.y) > vertical) wanted = Vec3(wanted.x, vertical * sign(wanted.y), wanted.z)
        val change = wanted.subtract(velocity)
        val length = change.length()
        if (length <= limits.acceleration || length < EPSILON) return wanted
        return velocity.add(change.scale(limits.acceleration / length))
    }

    /**
     * Velocity of a gliding flier: turns its course toward the goal by at most [FlightLimits.turn] per tick.
     *
     * Yaw and pitch turn separately, so a glider reaches a point straight above it by circling up.
     *
     * @param toward offset from the flier to its goal
     * @param speed wanted speed, raised to [FlightLimits.minSpeed]
     * @param facing direction to start from while the flier has no velocity yet
     */
    fun glide(velocity: Vec3, toward: Vec3, speed: Double, facing: Vec3, limits: FlightLimits): Vec3 {
        val current = velocity.length()
        val heading = if (current > EPSILON) velocity.scale(1.0 / current) else level(facing)
        val maxPitch = asin(limits.climbSine.coerceIn(0.0, 1.0))
        val pitch = asin(heading.y.coerceIn(-1.0, 1.0)).coerceIn(-maxPitch, maxPitch)
        val yaw = yawOf(level(heading))
        val wanted = if (toward.lengthSqr() < EPSILON) heading else toward.normalize()
        val wantedPitch = asin(wanted.y.coerceIn(-1.0, 1.0)).coerceIn(-maxPitch, maxPitch)
        val wantedYaw = if (Mth.length(wanted.x, wanted.z) > EPSILON) yawOf(wanted) else yaw

        var yawStep = Math.IEEEremainder(wantedYaw - yaw, 2.0 * Math.PI)
        var pitchStep = wantedPitch - pitch
        val arc = sqrt(pitchStep * pitchStep + yawStep * yawStep * cos(pitch) * cos(pitch))
        if (arc > limits.turn) {
            yawStep *= limits.turn / arc
            pitchStep *= limits.turn / arc
        }
        val course = direction(yaw + yawStep, pitch + pitchStep)
        val target = max(speed, limits.minSpeed)
        val next = if (current < target) min(target, current + limits.acceleration) else max(target, current - limits.acceleration)
        return course.scale(next)
    }

    /**
     * Highest speed from which a flier can still slow down to [endSpeed] within [distance], losing [deceleration]
     * per tick. Accounts for moving in whole ticks, so it brakes slightly earlier than the continuous formula.
     */
    fun brakingSpeed(distance: Double, endSpeed: Double, deceleration: Double): Double {
        if (deceleration.isInfinite()) return if (distance > 0.0) Double.POSITIVE_INFINITY else endSpeed
        val lead = endSpeed + deceleration * 0.5
        return sqrt(lead * lead + 2.0 * deceleration * max(0.0, distance)) - deceleration * 0.5
    }

    private fun yawOf(direction: Vec3): Double = atan2(-direction.x, direction.z)

    private fun direction(yaw: Double, pitch: Double): Vec3 =
        Vec3(-sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))

    /** Horizontal unit vector of [direction], or +Z if it is vertical. */
    private fun level(direction: Vec3): Vec3 {
        val planar = Mth.length(direction.x, direction.z)
        return if (planar > EPSILON) Vec3(direction.x / planar, 0.0, direction.z / planar) else Vec3(0.0, 0.0, 1.0)
    }
}

/** Smooth deterministic noise for [FlightSettings.wobble]. */
internal object FlightWobble {
    /**
     * @param seed per-entity seed
     * @param time time multiplied by the wobble rate
     * @return offset with each axis in -1..1
     */
    fun offset(seed: Int, time: Double): Vec3 = Vec3(
        noise(seed * AXES, time),
        noise(seed * AXES + 1, time) * VERTICAL_SHARE,
        noise(seed * AXES + 2, time),
    )

    /** Value noise: random values at whole [time] values, smoothly interpolated. */
    private fun noise(seed: Int, time: Double): Double {
        val whole = floor(time)
        val t = time - whole
        val step = whole.toLong()
        return Mth.lerp(SpeedPlan.smoothstep(t), lattice(seed, step), lattice(seed, step + 1))
    }

    private fun lattice(seed: Int, step: Long): Double {
        var hash = seed.toLong() * SEED_PRIME + step * STEP_PRIME
        hash = (hash xor (hash ushr 33)) * MIX
        hash = hash xor (hash ushr 29)
        return (hash and LATTICE_MASK).toDouble() / LATTICE_MASK.toDouble() * 2.0 - 1.0
    }

    private const val AXES = 3
    private const val VERTICAL_SHARE = 0.6
    private const val LATTICE_MASK = 0xFFFFFFL
    private const val SEED_PRIME = -0x61C8864680B583EBL
    private const val STEP_PRIME = -0x3D4D51C2D82B14B1L
    private const val MIX = -0xAE502812AA7333L
}
