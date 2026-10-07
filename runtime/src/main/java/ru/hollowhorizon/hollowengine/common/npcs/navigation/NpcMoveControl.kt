package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.math.abs
import kotlin.math.max

class NpcMoveControl(mob: NpcEntity) : MoveControl(mob) {
    companion object {
        private const val MIN_DISTANCE_FOR_TURN_SQ = 0.04
        private const val BODY_TURN_DEAD_ZONE = 3f
        private const val MAX_BODY_TURN = 15f
        private const val MAX_HEAD_TURN = 20f
        private const val MAX_JUMP_ANGLE = 30f
        private const val TURNING_SPEED_FACTOR = 0.35f
        private const val HEIGHT_EPSILON = 1.0e-3
        private const val DIAGONAL_JUMP_DISTANCE_SQ = 2.25
    }

    /** The jump over a gap the step the NPC is on makes; the navigation sets it every tick. */
    var gapJump: GapJump? = null

    private val gapJumps = GapJumpControl(mob)

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
                val dx = this.wantedX - mob.x
                val dz = this.wantedZ - mob.z
                val dy = this.wantedY - mob.y
                val distSq = dx * dx + dy * dy + dz * dz
                val horizontalDistSq = dx * dx + dz * dz

                if (distSq < 2.5e-7) {
                    mob.zza = 0f
                    return
                }

                var yawDelta = 0f
                var headingFactor = 1f
                if (horizontalDistSq >= MIN_DISTANCE_FOR_TURN_SQ) {
                    val targetYaw = (Mth.atan2(dz, dx) * (180 / Math.PI) - 90.0).toFloat()
                    yawDelta = Mth.wrapDegrees(targetYaw - mob.yBodyRot)
                    if (abs(yawDelta) >= BODY_TURN_DEAD_ZONE) {
                        val bodyYaw = rotlerp(mob.yBodyRot, targetYaw, MAX_BODY_TURN)
                        mob.yRot = bodyYaw
                        mob.setYBodyRot(bodyYaw)
                        mob.yHeadRot = rotlerp(mob.yHeadRot, bodyYaw, MAX_HEAD_TURN)
                    }
                    headingFactor = max(0f, Mth.cos(Mth.wrapDegrees(targetYaw - mob.yRot) * Mth.DEG_TO_RAD))
                }
                mob.speed = (this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat() * headingFactor

                val diagonalApproach = abs(dx) > mob.bbWidth * 0.5 && abs(dz) > mob.bbWidth * 0.5
                val jumpDistanceSq = if (diagonalApproach) {
                    DIAGONAL_JUMP_DISTANCE_SQ
                } else {
                    max(1.0, mob.bbWidth.toDouble())
                }
                val surfacePos = BlockPos.containing(wantedX, wantedY - HEIGHT_EPSILON, wantedZ)
                val hasStepableSurface = NpcNavigationGeometry.hasStepableSurface(
                    mob.level(),
                    surfacePos,
                    mob.maxUpStep().toDouble(),
                    mob.position(),
                )
                val needsJump = dy > mob.maxUpStep() + HEIGHT_EPSILON &&
                        horizontalDistSq < jumpDistanceSq && !hasStepableSurface
                if (needsJump && abs(yawDelta) > MAX_JUMP_ANGLE) {
                    mob.speed *= TURNING_SPEED_FACTOR
                } else if (needsJump) {
                    mob.jumpControl.jump()
                    this.operation = Operation.JUMPING
                }
            }

            Operation.JUMPING -> {
                mob.speed = (this.speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)).toFloat()
                if (mob.onGround()) {
                    this.operation = Operation.WAIT
                }
            }

            else -> {
                mob.zza = 0f
            }
        }
    }

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
