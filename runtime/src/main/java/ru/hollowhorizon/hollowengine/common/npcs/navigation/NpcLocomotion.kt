package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.math.max
import kotlin.math.min

/**
 * What the navigation tells the move control for one tick of walking a path, besides where to head: how fast,
 * as a [SpeedPlan] share of the speed the move asked for, and which way the body faces.
 */
class Stride(
    val share: Double,
    val facing: Float?,
    val turnSpeed: Float,
    val sideways: Double,
    val backward: Double,
)

/**
 * The pace of an NPC along its path: it speeds up over its acceleration time, speeds up more on long straights,
 * and brakes in good time for sharp turns, for jumps and for the end of the path, where it stops short as asked. It
 * also says how far ahead the NPC heads for, so it rounds turns, and how far ahead it looks.
 */
internal class NpcLocomotion(private val mob: Mob) {
    private val ramp = SpeedRamp()
    private var walking = false

    /** The share it walks at, as of the last tick. */
    val share: Double get() = ramp.share

    /** Stops counting: whatever moves the NPC next, it picks the pace up from how fast it really goes then. */
    fun pause() {
        walking = false
    }

    fun share(ahead: PathAhead, settings: MovementSettings, speedModifier: Double): Double {
        val full = fullSpeed(speedModifier)
        if (!walking) {
            ramp.reset(if (full > 0.0) mob.deltaMovement.horizontalDistance() / full else 0.0)
            walking = true
        }
        val brakeTicks = settings.deceleration * TICKS_PER_SECOND.toDouble()
        val cap = SpeedPlan.straightShare(
            ahead.straightAhead(STRAIGHT_TURN),
            settings.straightLength.toDouble(),
            settings.straightBoost.toDouble(),
        )
        var limit = cap
        for (turn in ahead.turns) {
            limit = min(limit, SpeedPlan.brakingShare(turn.distance, SpeedPlan.cornerShare(turn.degrees), full, brakeTicks))
        }
        val stopShort = ahead.stopShort
        if (stopShort != null) {
            val end = SpeedPlan.brakingShare(ahead.length - stopShort, 0.0, full, brakeTicks)
            limit = min(limit, max(MIN_ARRIVAL_SHARE, end))
        }
        return ramp.next(limit, cap, settings.acceleration * TICKS_PER_SECOND.toDouble())
    }

    /** How far along the path the NPC heads for: farther the faster it goes, so turns become arcs. */
    fun headingDistance(speedModifier: Double): Double =
        (fullSpeed(speedModifier) * share * HEADING_TICKS).coerceIn(MIN_HEADING, MAX_HEADING)

    /** How far along the path the NPC looks: about where it will be in a second. */
    fun lookDistance(speedModifier: Double): Double = max(MIN_LOOK, fullSpeed(speedModifier) * share * LOOK_TICKS)

    /** How fast, in blocks a tick, the NPC walks on this ground at [speedModifier] once it has picked up speed. */
    private fun fullSpeed(speedModifier: Double): Double {
        val friction = NpcNavigationGeometry.frictionUnder(mob.level(), mob.position())
        val speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * speedModifier
        return JumpSimulation.groundAcceleration(speed, friction) / (1.0 - JumpSimulation.groundDrag(friction))
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f

        /** Turns gentler than this, in degrees, still count as a straight. */
        const val STRAIGHT_TURN = 15.0

        /** The slowest it walks toward the end of its path, so that it gets there. */
        const val MIN_ARRIVAL_SHARE = 0.15

        const val HEADING_TICKS = 8.0
        const val MIN_HEADING = 0.8
        const val MAX_HEADING = 3.0

        const val LOOK_TICKS = 20.0
        const val MIN_LOOK = 2.0
    }
}
