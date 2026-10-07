package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class NpcMoveControl(mob: NpcEntity) : MoveControl(mob) {
    companion object {
        private const val MIN_DISTANCE_FOR_TURN_SQ = 0.04
        private const val BODY_TURN_DEAD_ZONE = 3f
        private const val MAX_BODY_TURN = 20f
        private const val MAX_HEAD_TURN = 20f
        private const val MAX_JUMP_ANGLE = 30f
        private const val TURNING_SPEED_FACTOR = 0.35f
        private const val HEIGHT_EPSILON = 1.0e-3
        /** How near the block it climbs, past its own half width, the NPC jumps the vanilla way. */
        private const val STEP_REACH = 0.25

        /**
         * How far from the block, past its half width, the NPC steps back for room to climb it with some margin,
         * and how much more ground there has to be behind it.
         */
        private const val STEP_BACK = 0.6
        private const val STEP_BACK_SLACK = 0.1

        /**
         * How far, in degrees, the way it walks may be off the way the body faces before the body turns in
         * earnest: inside it a small change of course is a step to the side, as a person makes it.
         */
        private const val SIDESTEP_ANGLE = 20f

        /** How fast the body drifts after its course inside [SIDESTEP_ANGLE], in degrees a tick. */
        private const val DRIFT_TURN = 3f

        /** Outside it, the share of what is left of a turn the body makes in a tick, and the least it makes. */
        private const val TURN_RESPONSE = 0.35f
        private const val MIN_TURN = 4f

        private const val MIN_WALK_DISTANCE = 1.0e-3
    }

    /** The jump over a gap the step the NPC is on makes; the navigation sets it every tick. */
    var gapJump: GapJump? = null

    /** How to take the step toward the wanted position along a path; the navigation sets it every tick. */
    var stride: Stride? = null

    private val gapJumps = GapJumpControl(mob)

    /** The block the NPC steps back from to climb it, while it does, and the last one it stepped back from. */
    private var backingOffFrom: BlockPos? = null
    private var backedOffFrom: BlockPos? = null

    override fun tick() {
        val jump = gapJump
        gapJumps.beginTick(jump, operation == Operation.JUMPING)
        if (jump != null && operation == Operation.MOVE_TO) {
            operation = if (gapJumps.tick(jump)) Operation.JUMPING else Operation.WAIT
            return
        }
        when (this.operation) {
            Operation.STRAFE -> {
                val speed = (this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat()
                var forward = this.strafeForwards
                var right = this.strafeRight
                var norm = Mth.sqrt(forward * forward + right * right)
                if (norm < 1.0f) norm = 1.0f
                val scale = speed / norm
                forward *= scale
                right *= scale

                val yawRad = mob.yRot * Mth.DEG_TO_RAD
                val sin = Mth.sin(yawRad)
                val cos = Mth.cos(yawRad)

                val deltaX = forward * cos - right * sin
                val deltaZ = right * cos + forward * sin

                if (!this.isWalkable(deltaX, deltaZ)) {
                    this.strafeForwards = 1.0f
                    this.strafeRight = 0.0f
                }

                this.mob.speed = speed
                this.mob.zza = this.strafeForwards
                this.mob.xxa = this.strafeRight
                this.operation = Operation.WAIT
            }

            Operation.MOVE_TO -> {
                this.operation = Operation.WAIT
                val stride = stride
                if (stride != null) walk(stride) else moveToWanted()
            }

            Operation.JUMPING -> {
                mob.speed = (this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat()
                mob.xxa = 0f
                if (mob.onGround()) {
                    this.operation = Operation.WAIT
                }
            }

            else -> {
                mob.zza = 0f
                mob.xxa = 0f
            }
        }
    }

    private fun walk(stride: Stride) {
        val dx = wantedX - mob.x
        val dz = wantedZ - mob.z
        val dy = wantedY - mob.y
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < MIN_WALK_DISTANCE) {
            mob.speed = 0f
            mob.xxa = 0f
            return
        }

        val course = yawOf(dx, dz)
        val held = stride.facing
        if (held != null) {
            turnBody(held, stride.turnSpeed)
        } else {
            val error = abs(Mth.wrapDegrees(course - mob.yRot))
            val turn = if (error <= SIDESTEP_ANGLE) DRIFT_TURN else (error * TURN_RESPONSE).coerceIn(MIN_TURN, MAX_BODY_TURN)
            turnBody(course, turn)
        }

        val off = abs(Mth.wrapDegrees(course - mob.yRot))
        val direction = SpeedPlan.directionShare(off.toDouble(), stride.sideways, stride.backward)
        var share = stride.share * direction
        val climbing = dy > mob.maxUpStep() + HEIGHT_EPSILON || !mob.onGround() && dy > HEIGHT_EPSILON
        if (climbing) share = max(share, 1.0)
        if (held == null && off > SIDESTEP_ANGLE) share *= max(0f, Mth.cos(off * Mth.DEG_TO_RAD))
        val input = (speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * sqrt(max(0.0, share))).toFloat()
        mob.speed = input

        val yaw = mob.yRot * Mth.DEG_TO_RAD
        val towardX = (dx / distance).toFloat()
        val towardZ = (dz / distance).toFloat()
        mob.zza = input * (-towardX * Mth.sin(yaw) + towardZ * Mth.cos(yaw))
        mob.xxa = input * (towardX * Mth.cos(yaw) + towardZ * Mth.sin(yaw))

        if (climb(dy, towardX, towardZ, input, if (held == null) off else 0f)) operation = Operation.JUMPING
    }

    /** Toward a wanted position nothing planned a path to: turns to it and walks there at the speed asked for. */
    private fun moveToWanted() {
        mob.xxa = 0f
        val dx = this.wantedX - mob.x
        val dz = this.wantedZ - mob.z
        val dy = this.wantedY - mob.y
        if (dx * dx + dy * dy + dz * dz < 2.5e-7) {
            mob.zza = 0f
            return
        }

        var yawDelta = 0f
        var headingFactor = 1f
        if (dx * dx + dz * dz >= MIN_DISTANCE_FOR_TURN_SQ) {
            val targetYaw = yawOf(dx, dz)
            yawDelta = Mth.wrapDegrees(targetYaw - mob.yBodyRot)
            if (abs(yawDelta) >= BODY_TURN_DEAD_ZONE) {
                turnBody(targetYaw, MAX_BODY_TURN)
                mob.yHeadRot = rotlerp(mob.yHeadRot, mob.yRot, MAX_HEAD_TURN)
            }
            headingFactor = max(0f, Mth.cos(Mth.wrapDegrees(targetYaw - mob.yRot) * Mth.DEG_TO_RAD))
        }
        mob.speed = (this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat() * headingFactor
        if (stepUp(dy, abs(yawDelta))) operation = Operation.JUMPING
    }

    private fun climb(dy: Double, towardX: Float, towardZ: Float, input: Float, offCourse: Float): Boolean {
        if (dy <= mob.maxUpStep() + HEIGHT_EPSILON || isStepable()) {
            if (mob.onGround()) {
                backingOffFrom = null
                backedOffFrom = null
            }
            return false
        }
        val step = BlockPos.containing(wantedX, wantedY, wantedZ)
        if (backingOffFrom == step) {
            if (distanceToStep() < mob.bbWidth * 0.5 + STEP_BACK) {
                backOff()
                return false
            }
            backingOffFrom = null
        }
        if (!mob.onGround()) return false
        val level = mob.level()
        val body = NpcNavigationGeometry.jumpBody(mob, input.toDouble(), NpcNavigationGeometry.frictionUnder(level, mob.position()))
        val motion = mob.deltaMovement
        val decision = JumpSimulation.decide(
            NpcNavigationGeometry.jumpSpace(level, mob), body, mob.position(), motion.x, motion.z,
            towardX.toDouble(), towardZ.toDouble(), stepTop(), sprinting = false,
        )
        return when (decision) {
            JumpDecision.TAKE_OFF -> {
                mob.jumpControl.jump()
                true
            }
            JumpDecision.RUN -> false
            JumpDecision.BRAKE -> {
                if (backedOffFrom != step && distanceToStep() < mob.bbWidth * 0.5 + STEP_REACH && hasRoomBehind(towardX, towardZ)) {
                    backingOffFrom = step
                    backedOffFrom = step
                    backOff()
                    false
                } else {
                    stepUp(dy, offCourse)
                }
            }
        }
    }

    /** Walks the other way from the block it climbs, as hard as it would walk toward it. */
    private fun backOff() {
        mob.zza = -mob.zza
        mob.xxa = -mob.xxa
    }

    /** Whether the NPC can walk the step back from the block it climbs, going along ([towardX], [towardZ]). */
    private fun hasRoomBehind(towardX: Float, towardZ: Float): Boolean {
        val reach = STEP_BACK + STEP_BACK_SLACK
        val behind = mob.position().add(-towardX * reach, 0.0, -towardZ * reach)
        return NpcNavigationGeometry.canWalkDirectly(mob.level(), mob, mob.position(), behind)
    }

    /**
     * Jumps up onto the block in front when it is too high to step onto; slows down instead while the body is
     * still [offCourse] degrees off it. True when it jumped.
     */
    private fun stepUp(dy: Double, offCourse: Float): Boolean {
        val needsJump = dy > mob.maxUpStep() + HEIGHT_EPSILON && distanceToStep() < mob.bbWidth * 0.5 + STEP_REACH && !isStepable()
        if (!needsJump) return false
        if (offCourse > MAX_JUMP_ANGLE) {
            val forward = mob.zza
            val sideways = mob.xxa
            mob.speed *= TURNING_SPEED_FACTOR
            mob.zza = forward * TURNING_SPEED_FACTOR
            mob.xxa = sideways * TURNING_SPEED_FACTOR
            return false
        }
        mob.jumpControl.jump()
        return true
    }

    private fun stepTop(): Vec3 = Vec3(Mth.floor(wantedX) + 0.5, wantedY, Mth.floor(wantedZ) + 0.5)

    /** How far, in blocks, the NPC's middle is from the block the wanted position stands on, sideways. */
    private fun distanceToStep(): Double {
        val minX = Mth.floor(wantedX).toDouble()
        val minZ = Mth.floor(wantedZ).toDouble()
        val dx = max(0.0, max(minX - mob.x, mob.x - (minX + 1.0)))
        val dz = max(0.0, max(minZ - mob.z, mob.z - (minZ + 1.0)))
        return sqrt(dx * dx + dz * dz)
    }

    /** Whether the wanted position stands on something low enough to step onto, as a stair or a slab. */
    private fun isStepable(): Boolean = NpcNavigationGeometry.hasStepableSurface(
        mob.level(),
        BlockPos.containing(wantedX, wantedY - HEIGHT_EPSILON, wantedZ),
        mob.maxUpStep().toDouble(),
        mob.position(),
    )

    private fun turnBody(yaw: Float, maxTurn: Float) {
        val body = rotlerp(mob.yRot, yaw, maxTurn)
        mob.yRot = body
        mob.setYBodyRot(body)
    }

    private fun yawOf(dx: Double, dz: Double): Float = (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG - 90.0).toFloat()

    private fun isWalkable(relativeX: Float, relativeZ: Float): Boolean {
        val pathNavigation = mob.navigation
        val nodeEvaluator = pathNavigation.nodeEvaluator
        return nodeEvaluator.getPathType(
            PathfindingContext(mob.level(), mob), Mth.floor(
                mob.x + relativeX.toDouble()
            ), mob.blockY, Mth.floor(mob.z + relativeZ.toDouble())
        ) == PathType.WALKABLE

    }
}
