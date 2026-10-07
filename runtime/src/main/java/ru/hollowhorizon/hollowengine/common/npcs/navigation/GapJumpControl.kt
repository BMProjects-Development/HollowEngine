package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.abs

/** A jump over a gap: where the NPC lands, and the middle of the block it runs up from. */
class GapJump(val landing: Vec3, val takeoff: Vec3)

/** Takes an NPC over a gap its path jumps. */
internal class GapJumpControl(private val mob: NpcEntity) {
    private var current: Vec3? = null
    private var tookOff = false
    private var retreating = false
    private var takeoffSpeed = 0f
    private var sprintingForJump = false

    /** Called at the start of every tick; [jumping] is whether the move control still waits for a jump to leave the ground. */
    fun beginTick(jump: GapJump?, jumping: Boolean) {
        if (sprintingForJump && mob.onGround() && !jumping) {
            mob.isSprinting = false
            sprintingForJump = false
        }
        if (jump?.landing != current) {
            current = jump?.landing
            tookOff = false
            retreating = false
        }
    }

    /** Moves the NPC along [jump] for this tick; true when it took off. */
    fun tick(jump: GapJump): Boolean {
        val walk = mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
        when {
            !mob.onGround() -> {
                face(jump.landing, MAX_TURN)
                mob.speed = if (tookOff) takeoffSpeed else walk.toFloat()
            }

            tookOff && mob.y < jump.landing.y - MISSED_DEPTH -> {
                mob.speed = 0f
                mob.navigation.recomputePath()
            }

            tookOff -> settle(jump.landing, walk)
            retreating -> retreat(jump.takeoff, walk)
            else -> return runUp(jump, walk * mob.npcNavigation.settings.jumps.runUp)
        }
        return false
    }

    private fun runUp(jump: GapJump, speed: Double): Boolean {
        val aligned = abs(face(jump.landing, MAX_TURN)) <= ALIGNMENT
        val level = mob.level()
        val space = NpcNavigationGeometry.jumpSpace(level, mob)
        val position = mob.position()
        val motion = mob.deltaMovement
        val halfWidth = mob.bbWidth * 0.5
        val friction = NpcNavigationGeometry.frictionUnder(level, position)

        if (!aligned) {
            mob.speed = (speed * TURNING_SPEED_FACTOR).toFloat()
            return !staysOnGround(space, halfWidth, position, motion, friction) && takeOff(speed)
        }

        val yaw = mob.yRot * Mth.DEG_TO_RAD
        val dirX = -Mth.sin(yaw).toDouble()
        val dirZ = Mth.cos(yaw).toDouble()
        val body = NpcNavigationGeometry.jumpBody(mob, speed, friction)
        return when (JumpSimulation.decide(space, body, position, motion.x, motion.z, dirX, dirZ, jump.landing)) {
            JumpDecision.TAKE_OFF -> takeOff(speed)
            JumpDecision.RUN -> {
                mob.speed = speed.toFloat()
                false
            }

            JumpDecision.BRAKE -> {
                mob.speed = 0f
                if (!staysOnGround(space, halfWidth, position, motion, friction)) return takeOff(speed)
                retreating = true
                false
            }
        }
    }

    private fun takeOff(speed: Double): Boolean {
        if (!mob.isSprinting) {
            mob.isSprinting = true
            sprintingForJump = true
        }
        mob.speed = speed.toFloat()
        mob.jumpControl.jump()
        tookOff = true
        takeoffSpeed = mob.speed
        return true
    }

    /** Brakes to a stop in the middle of [landing]. */
    private fun settle(landing: Vec3, walk: Double) = approach(landing, walk) { }

    /** Walks back to the middle of the block the run-up starts from; the next tick there runs up again. */
    private fun retreat(takeoff: Vec3, walk: Double) = approach(takeoff, walk) { retreating = false }

    /** Walks slowly toward [target] and stops on it, braking early enough not to slide past; [arrived] once at rest there. */
    private inline fun approach(target: Vec3, walk: Double, arrived: () -> Unit) {
        val dx = target.x - mob.x
        val dz = target.z - mob.z
        val distance = Mth.length(dx, dz)
        val motion = mob.deltaMovement
        val speed = motion.horizontalDistance()
        if (distance < SETTLE_REACH) {
            mob.speed = 0f
            if (speed < JumpSimulation.REST_SPEED * 2.0) arrived()
            return
        }
        face(target, MAX_SETTLE_TURN)
        val closing = (motion.x * dx + motion.z * dz) / distance
        val slide =
            JumpSimulation.slideDistance(speed, NpcNavigationGeometry.frictionUnder(mob.level(), mob.position()))
        mob.speed = if (closing > 0.0 && slide >= distance) 0f else (walk * SETTLE_SPEED_FACTOR).toFloat()
    }

    /** Whether the NPC keeps ground under its feet this tick if it stops pushing on now. */
    private fun staysOnGround(
        space: JumpSpace,
        halfWidth: Double,
        position: Vec3,
        motion: Vec3,
        friction: Double,
    ): Boolean {
        val speed = motion.horizontalDistance()
        if (speed < JumpSimulation.REST_SPEED) return true
        val slide = JumpSimulation.slideDistance(speed, friction)
        val stop = position.add(motion.x / speed * slide, 0.0, motion.z / speed * slide)
        return JumpSimulation.hasSupport(space, halfWidth, stop.x, stop.y, stop.z)
    }

    /** Turns the body toward [target] by at most [maxTurn] degrees; how far off it still faces, in degrees. */
    private fun face(target: Vec3, maxTurn: Float): Float {
        val dx = target.x - mob.x
        val dz = target.z - mob.z
        if (dx * dx + dz * dz < MIN_TURN_DISTANCE_SQ) return 0f
        val targetYaw = (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG - 90.0).toFloat()
        val bodyYaw = Mth.approachDegrees(mob.yBodyRot, targetYaw, maxTurn)
        mob.yRot = bodyYaw
        mob.setYBodyRot(bodyYaw)
        mob.yHeadRot = Mth.approachDegrees(mob.yHeadRot, bodyYaw, MAX_TURN)
        return Mth.wrapDegrees(targetYaw - bodyYaw)
    }

    private companion object {
        /** How fast the body turns toward the landing, in degrees a tick. */
        const val MAX_TURN = 30f
        const val MAX_SETTLE_TURN = 20f

        /** How far off the landing the body may face and still take off, in degrees. */
        const val ALIGNMENT = 10f
        const val TURNING_SPEED_FACTOR = 0.35

        /** How far below the landing the NPC has to end up for its jump to count as missed. */
        const val MISSED_DEPTH = 0.5

        /** How close to the middle of a block the NPC stops when it settles there. */
        const val SETTLE_REACH = 0.15

        /** Its speed, as a share of walking, while it settles: slow enough not to overshoot a block. */
        const val SETTLE_SPEED_FACTOR = 0.5

        const val MIN_TURN_DISTANCE_SQ = 1.0e-4
    }
}
