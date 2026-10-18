package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.util.Mth
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import kotlin.math.sin

/**
 * One tick of flight, passed from navigation to move control.
 *
 * @param aim point to fly toward
 * @param share fraction of [speed] to fly at
 * @param speed full flight speed, in blocks per tick
 * @param facing body yaw a hovering flier keeps, or null to face its course
 */
class FlightCommand(
    val aim: Vec3,
    val share: Double,
    val speed: Double,
    val facing: Float?,
    val settings: FlightSettings,
)

/**
 * Flies an NPC by setting its velocity every tick, with gravity off.
 *
 * Vanilla still applies collisions and air drag after the move. The drag is divided back out when the velocity
 * is read next tick, so collisions and knockback carry over while the drag does not.
 */
internal class FlightControl(private val mob: Mob) {
    /** Drag vanilla applied after the last flight tick, per axis; null if the NPC did not fly. */
    private var drag: Vec3? = null

    fun tick(command: FlightCommand) {
        val settings = command.settings
        val limits = limitsOf(settings, command.speed)
        val toward = command.aim.subtract(mob.position())
        val wanted = command.share * command.speed
        val velocity = flownVelocity()
        val next = when (settings.style) {
            FlightStyle.HOVER -> FlightSteering.hover(velocity, toward, wanted, limits)
            FlightStyle.GLIDE -> FlightSteering.glide(velocity, toward, wanted, bodyFacing(), limits)
        }
        mob.deltaMovement = next
        mob.zza = 0f
        mob.xxa = 0f
        mob.yya = 0f
        mob.speed = command.speed.toFloat()
        mob.resetFallDistance()
        turnBody(command, next, toward)

        val horizontal = if (mob.onGround()) {
            NpcNavigationGeometry.frictionUnder(mob.level(), mob.position()) * AIR_DRAG
        } else {
            AIR_DRAG
        }
        drag = Vec3(horizontal, VERTICAL_DRAG, horizontal)
    }

    /** Called on ticks the NPC does not fly. */
    fun rest() {
        drag = null
    }

    private fun flownVelocity(): Vec3 {
        val motion = mob.deltaMovement
        val drag = drag ?: return motion
        return Vec3(motion.x / drag.x, motion.y / drag.y, motion.z / drag.z)
    }

    private fun turnBody(command: FlightCommand, velocity: Vec3, toward: Vec3) {
        val planar = velocity.horizontalDistance()
        val yaw = when {
            command.settings.style == FlightStyle.GLIDE -> if (planar > MIN_COURSE_SPEED) yawOf(velocity) else return
            command.facing != null -> command.facing
            planar > HOVER_FACE_SPEED -> yawOf(velocity)
            toward.horizontalDistance() > HOVER_FACE_REACH -> yawOf(toward)
            else -> return
        }
        val body = Mth.approachDegrees(mob.yRot, yaw, command.settings.turnRate / TICKS_PER_SECOND)
        mob.yRot = body
        mob.setYBodyRot(body)
    }

    private fun bodyFacing(): Vec3 {
        val yaw = mob.yRot * Mth.DEG_TO_RAD
        return Vec3(-Mth.sin(yaw).toDouble(), 0.0, Mth.cos(yaw).toDouble())
    }

    private fun yawOf(direction: Vec3): Float = (Mth.atan2(direction.z, direction.x) * Mth.RAD_TO_DEG - 90.0).toFloat()

    companion object {
        private const val TICKS_PER_SECOND = 20f

        /** Vanilla air drag per tick: horizontal, and vertical without gravity. */
        private const val AIR_DRAG = 0.91
        private const val VERTICAL_DRAG = 0.98

        private const val MIN_COURSE_SPEED = 1.0e-3
        private const val HOVER_FACE_SPEED = 0.03
        private const val HOVER_FACE_REACH = 0.5

        /** Lowest speed of a glider, as a fraction of its full speed. */
        const val GLIDE_SHARE = 0.45

        fun limitsOf(settings: FlightSettings, speed: Double): FlightLimits {
            val accelerationTicks = settings.flightAcceleration * TICKS_PER_SECOND
            return FlightLimits(
                fullSpeed = speed,
                acceleration = if (accelerationTicks > 0f) speed / accelerationTicks else Double.POSITIVE_INFINITY,
                turn = Math.toRadians(settings.turnRate.toDouble()) / TICKS_PER_SECOND,
                climbSine = sin(Math.toRadians(settings.climbAngle.toDouble().coerceIn(MIN_CLIMB, 90.0))),
                minSpeed = if (settings.style == FlightStyle.GLIDE) speed * GLIDE_SHARE else 0.0,
            )
        }

        private const val MIN_CLIMB = 5.0
    }
}
