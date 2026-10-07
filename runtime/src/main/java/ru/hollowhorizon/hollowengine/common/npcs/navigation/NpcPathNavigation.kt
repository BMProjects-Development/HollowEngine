package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntity
import ru.hollowhorizon.hollowengine.common.utils.isProduction
import kotlin.math.abs
import kotlin.math.min
import net.minecraft.world.level.pathfinder.PathType as BlockPathTypes

class NpcPathNavigation(level: Level, mob: Mob) : GroundPathNavigation(mob, level) {
    private val openedDoors = mutableMapOf<BlockPos, Boolean>()
    private var steeringTarget: Vec3? = null

    /** The settings of the move under way, taking the place of the NPC's own for as long as it lasts. */
    var settingsOverride: NavigationComponent? = null

    /** What the NPC's path search and its steps go by now. */
    val settings: NavigationComponent
        get() = settingsOverride ?: mob.navigationComponent ?: DEFAULT_SETTINGS

    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        val evaluator = NpcNodeEvaluator { settings }
        nodeEvaluator = evaluator
        return NpcPathFinder(evaluator, maxVisitedNodes) { settings }
    }

    override fun tick() {
        steeringTarget = null
        super.tick()
        closePassedDoors()
        val currentPath = path?.takeUnless(Path::isDone)
        val jump = currentPath?.let(::gapJumpOf)
        (mob.moveControl as? NpcMoveControl)?.gapJump = jump
        if (currentPath == null) return
        val node = currentPath.nextNode

        if (node.type == BlockPathTypes.WALKABLE_DOOR) {
            val state = level.getBlockState(node.asBlockPos())
            if (DoorBlock.isWoodenDoor(state)) {
                val door = state.block as DoorBlock
                door.setOpen(mob, level, state, node.asBlockPos(), true)
                openedDoors.putIfAbsent(node.asBlockPos(), false)
            }
        }

        if (jump != null) moveToward(jump.landing) else updateSteering(currentPath)
        if (steeringTarget == null) {
            val nodePosition = currentPath.getNextEntityPos(mob)
            steeringTarget = Vec3(nodePosition.x, getGroundY(nodePosition), nodePosition.z)
        }
        sendDebugPath(currentPath)
    }

    /**
     * The node a jump takes off from is where its run-up starts: the NPC goes onto it before heading for the
     * landing, instead of cutting toward the gap. A node it jumped onto it settles on first: it goes on only once
     * it has stopped there, or while the jump still carries it the way the path goes on. Otherwise what is left
     * of the jump would carry it off a narrow landing as it turned, and the next jump would start from whatever
     * the last one left rather than from rest, the way the path search tried it.
     */
    override fun followThePath() {
        val currentPath = path as? NpcPath
        val index = currentPath?.nextNodeIndex ?: return super.followThePath()
        val takesOff = currentPath.isJumpTo(index + 1)
        val landed = currentPath.isJumpTo(index)
        if (!takesOff && !landed) return super.followThePath()

        val node = currentPath.getNextEntityPos(mob)
        val reached = abs(mob.x - node.x) < TAKEOFF_REACH && abs(mob.z - node.z) < TAKEOFF_REACH && abs(mob.y - node.y) < 1.0
        val settled = !landed || mob.onGround() && (isAtRest() || !takesOff && carriesOnToward(currentPath, index + 1))
        if (reached && settled) currentPath.advance()
        doStuckDetection(tempMobPos)
    }

    private fun isAtRest(): Boolean = mob.deltaMovement.horizontalDistance() < SETTLED_SPEED

    /** Whether the NPC already moves toward node [index], so that walking on there needs no turn; true past the end. */
    private fun carriesOnToward(currentPath: Path, index: Int): Boolean {
        if (index >= currentPath.nodeCount) return true
        val next = currentPath.getEntityPosAtNode(mob, index)
        val motion = mob.deltaMovement
        val dx = next.x - mob.x
        val dz = next.z - mob.z
        val distance = Mth.length(dx, dz)
        val speed = motion.horizontalDistance()
        if (distance < 1.0e-3 || speed < 1.0e-3) return false
        return (motion.x * dx + motion.z * dz) / (distance * speed) >= CARRY_ON_COS
    }

    /** The jump the step the NPC is on makes over a gap, or null when it walks it. */
    private fun gapJumpOf(currentPath: Path): GapJump? {
        val index = currentPath.nextNodeIndex
        if (index == 0 || (currentPath as? NpcPath)?.isJumpTo(index) != true) return null
        return GapJump(groundPosAtNode(currentPath, index), groundPosAtNode(currentPath, index - 1))
    }

    private fun groundPosAtNode(currentPath: Path, index: Int): Vec3 {
        val position = currentPath.getEntityPosAtNode(mob, index)
        return Vec3(position.x, getGroundY(position), position.z)
    }

    override fun canMoveDirectly(from: Vec3, to: Vec3): Boolean {
        if (!mob.onGround()) return false
        val groundedFrom = Vec3(from.x, mob.y, from.z)
        val groundedTo = Vec3(to.x, getGroundY(to), to.z)
        return NpcNavigationGeometry.canWalkDirectly(level, mob, groundedFrom, groundedTo)
    }

    private fun updateSteering(currentPath: Path) {
        if (!mob.onGround()) return

        val start = Vec3(mob.x, mob.y, mob.z)
        val firstIndex = currentPath.nextNodeIndex
        val lastIndex = min(firstIndex + MAX_LOOKAHEAD_NODES, currentPath.nodeCount - 1)
        for (index in lastIndex downTo firstIndex + 1) {
            if (!canSteerPast(currentPath, firstIndex, index)) continue

            val nodePosition = currentPath.getEntityPosAtNode(mob, index)
            val target = Vec3(nodePosition.x, getGroundY(nodePosition), nodePosition.z)
            if (!NpcNavigationGeometry.canWalkDirectly(level, mob, start, target)) continue

            moveToward(target)
            return
        }

        if (!canCutCorner(currentPath.nextNode.type)) return
        val nodePosition = currentPath.getNextEntityPos(mob)
        val target = Vec3(nodePosition.x, getGroundY(nodePosition), nodePosition.z)
        val waypoint = NpcNavigationGeometry.findWalkableWaypoint(level, mob, start, target)
            ?: NpcNavigationGeometry.findSqueezeWaypoint(level, mob, start, target)
            ?: return
        moveToward(waypoint)
    }

    private fun moveToward(target: Vec3) {
        steeringTarget = target
        mob.moveControl.setWantedPosition(target.x, target.y, target.z, speedModifier)
    }

    private fun sendDebugPath(currentPath: Path) {
        if (isProduction || level.isClientSide || mob.tickCount % DEBUG_SYNC_INTERVAL != 0) return
        val steeringTarget = steeringTarget ?: return
        val target = currentPath.target
        val npcPath = currentPath as? NpcPath
        NpcPathDebugPacket(
            mob.id,
            List(currentPath.nodeCount) { index ->
                val node = currentPath.getNode(index)
                NpcPathDebugNode(node.x, node.y, node.z, node.type.name, node.costMalus, npcPath?.isJumpTo(index) == true)
            },
            currentPath.nextNodeIndex,
            target.x,
            target.y,
            target.z,
            currentPath.canReach(),
            NpcPathDebugPoint(steeringTarget.x, steeringTarget.y, steeringTarget.z),
        ).sendTrackingEntity(mob)
    }

    /** Whether the NPC may steer straight from node [fromIndex] toward node [toIndex]: no door, hazard or jump between. */
    private fun canSteerPast(currentPath: Path, fromIndex: Int, toIndex: Int): Boolean {
        val npcPath = currentPath as? NpcPath
        for (index in fromIndex..toIndex) {
            if (!canCutCorner(currentPath.getNode(index).type)) return false
            if (index > fromIndex && npcPath?.isJumpTo(index) == true) return false
        }
        return true
    }

    private fun closePassedDoors() {
        val iterator = openedDoors.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val distance = mob.position().distanceToSqr(entry.key.center)
            if (distance <= DOOR_NEAR_DISTANCE_SQ) {
                entry.setValue(true)
            } else if (entry.value) {
                closeDoor(entry.key)
                iterator.remove()
            }
        }
    }

    private fun closeDoor(pos: BlockPos) {
        val state = level.getBlockState(pos)
        val door = state.block as? DoorBlock ?: return
        if (DoorBlock.isWoodenDoor(state)) door.setOpen(mob, level, state, pos, false)
    }

    companion object {
        private val DEFAULT_SETTINGS = NavigationComponent()
        private const val MAX_LOOKAHEAD_NODES = 4
        private const val DEBUG_SYNC_INTERVAL = 10
        private const val DOOR_NEAR_DISTANCE_SQ = 2.25

        /** How close to the middle of the node before a jump the NPC gets before it starts its run-up. */
        private const val TAKEOFF_REACH = 0.3

        /** Blocks per tick below which an NPC that jumped onto a node has stopped on it. */
        private const val SETTLED_SPEED = 0.02

        /** How close to the way on, as a cosine, the NPC has to move for a landing to need no stop: about 30 degrees. */
        private const val CARRY_ON_COS = 0.866
    }
}
