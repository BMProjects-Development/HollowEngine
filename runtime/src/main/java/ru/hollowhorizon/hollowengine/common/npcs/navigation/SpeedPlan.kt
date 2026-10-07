package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How fast an NPC walks, as a share of its full walking speed. */
internal object SpeedPlan {
    /** The share a turn of [degrees] between two stretches of the path is taken at. */
    fun cornerShare(degrees: Double): Double {
        val sharpness = smoothstep(((degrees - GENTLE_TURN) / (SHARP_TURN - GENTLE_TURN)).coerceIn(0.0, 1.0))
        return 1.0 - (1.0 - SHARP_TURN_SHARE) * sharpness
    }

    /**
     * The share on a straight stretch [straight] blocks long ahead: [boost] once it is [length] blocks or more,
     * none below half that, and smoothly between, so boost fades out well before turn.
     */
    fun straightShare(straight: Double, length: Double, boost: Double): Double {
        if (boost <= 1.0 || length <= 0.0) return 1.0
        val half = length / 2.0
        return 1.0 + (boost - 1.0) * smoothstep(((straight - half) / half).coerceIn(0.0, 1.0))
    }

    /**
     * The share walking [degrees] off way that body faces: all of it straight ahead, [sideways] at a right
     * angle, [backward] straight back, and between them in proportion.
     */
    fun directionShare(degrees: Double, sideways: Double, backward: Double): Double = when {
        degrees <= 90.0 -> lerp(1.0, sideways, degrees / 90.0)
        else -> lerp(sideways, backward, (min(degrees, 180.0) - 90.0) / 90.0)
    }

    /**
     * The highest share [distance] blocks before a point it has to be down to [end] at, braking at rate that
     * stops [fullSpeed], blocks a tick, in [brakeTicks]: speed that brakes evenly, as a body does.
     */
    fun brakingShare(distance: Double, end: Double, fullSpeed: Double, brakeTicks: Double): Double {
        if (fullSpeed <= 0.0) return end
        if (brakeTicks <= 0.0) return if (distance > 0.0) Double.POSITIVE_INFINITY else end
        val deceleration = fullSpeed / brakeTicks
        val endSpeed = end * fullSpeed
        return sqrt(endSpeed * endSpeed + 2.0 * deceleration * max(0.0, distance)) / fullSpeed
    }

    fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)

    /** The t in 0..1 [smoothstep] gives [value] for. */
    fun inverseSmoothstep(value: Double): Double = 0.5 - sin(asin(1.0 - 2.0 * value.coerceIn(0.0, 1.0)) / 3.0)

    private fun lerp(from: Double, to: Double, t: Double) = from + (to - from) * t

    /** Turns gentler than this, in degrees, keep full speed. */
    private const val GENTLE_TURN = 20.0

    /** Turns this sharp or sharper are taken at [SHARP_TURN_SHARE]. */
    private const val SHARP_TURN = 130.0
    private const val SHARP_TURN_SHARE = 0.35
}

/**
 * The share an NPC walks at, from tick to tick: it rises to what it may walk at along a smoothstep curve that
 * takes acceleration time from standing to [cap], and drops at once to anything lower, which
 * [SpeedPlan.brakingShare] has already made gradual.
 */
internal class SpeedRamp {
    var share = 0.0
        private set

    /** Starts from [share], as when NPC was moved by something else until now. */
    fun reset(share: Double) {
        this.share = max(0.0, share)
    }

    /** The share for next tick, toward [target] but rising no faster than [accelerationTicks] allow. */
    fun next(target: Double, cap: Double, accelerationTicks: Double): Double {
        val goal = max(0.0, target)
        share = if (goal <= share || accelerationTicks <= 0.0 || cap <= 0.0) {
            goal
        } else {
            val progress = SpeedPlan.inverseSmoothstep(share / cap) + 1.0 / accelerationTicks
            min(goal, cap * SpeedPlan.smoothstep(min(1.0, progress)))
        }
        return share
    }
}
