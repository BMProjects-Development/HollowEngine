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
import net.minecraft.world.level.pathfinder.PathType as BlockPathTypes

class NpcPathNavigation(level: Level, mob: Mob) : GroundPathNavigation(mob, level) {
    private val openedDoors = mutableMapOf<BlockPos, Boolean>()
    private val locomotion = NpcLocomotion(mob)
    internal val passing = PassingSteer(mob)
    internal val facing = WalkFacing(mob)
    private var steeringTarget: Vec3? = null
    private var lookTarget: Vec3? = null

    /** The last point the path turned the head to. */
    private var pathLook: Vec3? = null
    private var backstepPath: Path? = null

    /** The settings of the move under way, taking the place of the NPC's own for as long as it lasts. */
    var settingsOverride: NavigationComponent? = null

    private var aimedTarget: Vec3? = null
    private var aimedVia: List<Vec3> = emptyList()
    private var aimedStop = 0.0
    private var aimedAt = Long.MIN_VALUE

    /** The point the move under way is for, which the path ends at rather than at the middle of its block. */
    val exactTarget: Vec3? get() = aimedTarget.takeIf { isAimFresh() }

    /**
     * The points the move goes on through after [exactTarget], when it passes several: the pace is planned along
     * them, so the NPC rounds the point it is walking to and keeps going instead of stopping there.
     */
    val via: List<Vec3> get() = if (isAimFresh()) aimedVia else emptyList()

    /** How far short of the end of its path the NPC means to stop; it brakes to stop there. */
    val stopDistance: Double get() = if (isAimFresh()) aimedStop else 0.0

    /**
     * Says what the move under way is for: [target], then [via], stopping [stop] short of the last. Whatever drives
     * the NPC, a scripted move or a patrol, says it every tick; a path nobody aims any more, such as a goal's, ends
     * at its last node again.
     */
    fun aim(target: Vec3?, via: List<Vec3> = emptyList(), stop: Double = 0.0) {
        aimedTarget = target
        aimedVia = via
        aimedStop = stop
        aimedAt = level.gameTime
    }

    private fun isAimFresh(): Boolean = level.gameTime - aimedAt <= AIM_TICKS

    /** Zones the NPC keeps out of on every way it walks, until a script lets it in again. */
    val avoidedZones = mutableListOf<Zone>()

    /** Zones the move under way keeps it out of. */
    var moveZones: List<Zone> = emptyList()

    /** The avoid rules on the live world, for what the NPC heads for between the nodes of its path. */
    private var liveAvoid: AvoidRules? = null

    /** What the NPC's path search and its steps go by now. */
    val settings: NavigationComponent
        get() = settingsOverride ?: mob.navigationComponent ?: DEFAULT_SETTINGS

    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        val evaluator = NpcNodeEvaluator({ settings }, ::zones)
        nodeEvaluator = evaluator
        return NpcPathFinder(evaluator, maxVisitedNodes) { settings }
    }

    override fun tick() {
        steeringTarget = null
        lookTarget = null
        super.tick()
        closePassedDoors()
        val currentPath = path?.takeUnless(Path::isDone)
        if (currentPath !== backstepPath) onPathChanged(currentPath)
        val jump = currentPath?.let(::gapJumpOf)
        val moveControl = mob.moveControl as? NpcMoveControl
        moveControl?.gapJump = jump
        moveControl?.stride = null
        if (currentPath == null) {
            locomotion.pause()
            return
        }
        openDoorAhead(currentPath)

        if (jump != null) {
            locomotion.pause()
            moveToward(jump.landing)
        } else {
            walk(currentPath, moveControl)
        }
        sendDebugPath(currentPath)
    }

    /**
     * Faces [target] while the NPC walks, turning by at most [turnSpeed] degrees a tick, for as long as it is
     * asked every tick; whether the body faces it now.
     */
    internal fun faceWhileWalking(target: Vec3, turnSpeed: Float): Boolean {
        facing.look(target, turnSpeed)
        val yaw = (Mth.atan2(target.z - mob.z, target.x - mob.x) * Mth.RAD_TO_DEG - 90.0).toFloat()
        return abs(Mth.wrapDegrees(yaw - mob.yRot)) <= 1f
    }

    private fun walk(currentPath: Path, moveControl: NpcMoveControl?) {
        val ahead = ahead(currentPath)
        val movement = settings.movement
        val held = facing.held()
        val heading = steer(currentPath, ahead, locomotion.headingDistance(speedModifier))
        val makesWay = settings.avoid.entities
        moveToward(if (makesWay) passing.around(heading, ahead, ::canHeadFor) else heading)
        val waits = makesWay && passing.waits
        if (waits) locomotion.pause()
        val share = if (waits) 0.0 else locomotion.share(ahead, movement, speedModifier)
        moveControl?.stride = Stride(
            share,
            held?.yaw,
            held?.turnSpeed ?: 0f,
            movement.sidewaysSpeed.toDouble(),
            movement.backwardSpeed.toDouble(),
        )
        val look = held?.look ?: ahead.pointAt(locomotion.lookDistance(speedModifier)).add(0.0, mob.eyeHeight * LOOK_HEIGHT, 0.0)
        lookAt(look)
    }

    /**
     * Turns the head to [look], unless something else, such as an attack goal, aimed it this tick: the path is
     * only where the head goes when nothing else wants it.
     */
    private fun lookAt(look: Vec3) {
        val control = mob.lookControl
        val ours = pathLook
        val taken = control.isLookingAtTarget &&
                (ours == null || control.wantedX != ours.x || control.wantedY != ours.y || control.wantedZ != ours.z)
        if (taken) return
        control.setLookAt(look.x, look.y, look.z, HEAD_TURN, HEAD_PITCH)
        pathLook = look
        lookTarget = look
    }

    private fun ahead(currentPath: Path): PathAhead {
        val npcPath = currentPath as? NpcPath
        val points = arrayListOf(mob.position())
        var length = 0.0
        for (index in currentPath.nextNodeIndex until currentPath.nodeCount) {
            val node = groundPosAtNode(currentPath, index)
            val point = if (index == currentPath.nodeCount - 1) exactEnd(node) ?: node else node
            length += Mth.length(point.x - points.last().x, point.z - points.last().z)
            points += point
            val last = index == currentPath.nodeCount - 1
            if (last) {
                for (point in via) {
                    length += Mth.length(point.x - points.last().x, point.z - points.last().z)
                    points += point
                    if (length > PLANNING_REACH) return PathAhead(points, stopShort = null)
                }
                return PathAhead(points, stopShort = stopDistance)
            }
            if (npcPath?.isJumpTo(index + 1) == true) return PathAhead(points, stopShort = 0.0)
            if (length > PLANNING_REACH || !canCutCorner(currentPath.getNode(index).type)) break
        }
        return PathAhead(points, stopShort = null)
    }

    /**
     * Where the NPC heads this tick: the point [distance] along the path ahead when it can walk there in a line,
     * else the farthest node before it that it can, else a way around the corner to the next node.
     */
    private fun steer(currentPath: Path, ahead: PathAhead, distance: Double): Vec3 {
        val next = ahead.points[1]
        if (!mob.onGround()) return next
        val start = mob.position()
        val heading = ahead.pointAt(distance)
        if (canHeadFor(start, heading)) return heading
        for (index in ahead.nodeBefore(distance) downTo 1) {
            val node = ahead.points[index]
            if (canHeadFor(start, node)) return node
        }
        if (!canCutCorner(currentPath.nextNode.type) || abs(next.y - start.y) > mob.maxUpStep()) return next
        return NpcNavigationGeometry.findWalkableWaypoint(level, mob, start, next)
            ?: NpcNavigationGeometry.findSqueezeWaypoint(level, mob, start, next)
            ?: next.also {
                recomputePath()
            }
    }

    /** Whether the NPC can walk from [start] to [point] in a line without cutting across what it avoids. */
    private fun canHeadFor(start: Vec3, point: Vec3): Boolean =
        NpcNavigationGeometry.canWalkDirectly(level, mob, start, point) && liveAvoid?.crosses(start, point) != true

    private fun zones(): List<Zone> = if (moveZones.isEmpty()) avoidedZones.toList() else avoidedZones + moveZones

    private fun onPathChanged(currentPath: Path?) {
        updateBackstep(currentPath)
        liveAvoid = currentPath?.let {
            AvoidRules(level, settings.avoid, zones(), Mth.ceil(mob.bbHeight), mob.blockPosition(), mob.isInWater)
        }
    }

    /**
     * A short walk to a point behind the NPC, when its move faces the path, is taken backward: it keeps facing
     * the way it did. The walk is judged anew for every new path, so a backstep goes on through replanning.
     */
    private fun updateBackstep(currentPath: Path?) {
        backstepPath = currentPath
        val reach = settings.movement.backstep
        if (currentPath == null || reach <= 0f || !facing.facesPath) {
            facing.backstep = null
            return
        }
        val ahead = ahead(currentPath)
        val end = ahead.points.last()
        val forward = mob.yRot * Mth.DEG_TO_RAD
        val toEndX = end.x - mob.x
        val toEndZ = end.z - mob.z
        val distance = Mth.length(toEndX, toEndZ)
        val behind = distance > MIN_BACKSTEP &&
                (-Mth.sin(forward) * toEndX + Mth.cos(forward) * toEndZ) / distance < BACKSTEP_COS
        val reachesEnd = ahead.points.size == currentPath.nodeCount - currentPath.nextNodeIndex + 1
        val flat = ahead.points.all { abs(it.y - mob.y) <= mob.maxUpStep() }
        facing.backstep = if (reachesEnd && flat && ahead.length <= reach && (behind || facing.isBackstepping)) {
            facing.backstep ?: mob.yRot
        } else {
            null
        }
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
        if (!takesOff && !landed) {
            val end = if (index == currentPath.nodeCount - 1) exactEnd(groundPosAtNode(currentPath, index)) else null
            val passed = if (end != null) {
                Mth.length(end.x - mob.x, end.z - mob.z) <= maxOf(stopDistance, EXACT_REACH)
            } else {
                passes(currentPath, index)
            }
            if (passed) currentPath.advance()
            doStuckDetection(tempMobPos)
            return
        }

        val node = currentPath.getNextEntityPos(mob)
        val reached = abs(mob.x - node.x) < TAKEOFF_REACH && abs(mob.z - node.z) < TAKEOFF_REACH && abs(mob.y - node.y) < 1.0
        val settled = !landed || mob.onGround() && (isAtRest() || !takesOff && carriesOnToward(currentPath, index + 1))
        if (reached && settled) currentPath.advance()
        doStuckDetection(tempMobPos)
    }

    private fun exactEnd(lastNode: Vec3): Vec3? {
        val target = exactTarget ?: return null
        if (abs(target.x - lastNode.x) > EXACT_SPAN || abs(target.z - lastNode.z) > EXACT_SPAN) return null
        if (abs(target.y - lastNode.y) > 1.0) return null
        return Vec3(target.x, lastNode.y, target.z)
    }

    private fun passes(currentPath: Path, index: Int): Boolean {
        maxDistanceToWaypoint = if (mob.bbWidth > 0.75f) mob.bbWidth / 2.0f else 0.75f - mob.bbWidth / 2.0f
        val node = currentPath.getNodePos(index)
        val reach = maxDistanceToWaypoint.toDouble()
        if (abs(mob.x - (node.x + 0.5)) < reach && abs(mob.z - (node.z + 0.5)) < reach && abs(mob.y - node.y) < 1.0) return true
        if (index + 1 >= currentPath.nodeCount || !canCutCorner(currentPath.getNode(index).type)) return false
        if (!mob.position().closerThan(Vec3.atBottomCenterOf(node), CUT_REACH)) return false
        return canMoveDirectly(tempMobPos, currentPath.getEntityPosAtNode(mob, index + 1))
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

    private fun moveToward(target: Vec3) {
        steeringTarget = target
        mob.moveControl.setWantedPosition(target.x, target.y, target.z, speedModifier)
    }

    private fun openDoorAhead(currentPath: Path) {
        val node = currentPath.nextNode
        if (node.type != BlockPathTypes.WALKABLE_DOOR) return
        val state = level.getBlockState(node.asBlockPos())
        if (!DoorBlock.isWoodenDoor(state)) return
        val door = state.block as DoorBlock
        door.setOpen(mob, level, state, node.asBlockPos(), true)
        openedDoors.putIfAbsent(node.asBlockPos(), false)
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
            lookTarget?.let { NpcPathDebugPoint(it.x, it.y, it.z) },
            locomotion.share.toFloat(),
        ).sendTrackingEntity(mob)
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
        private const val DEBUG_SYNC_INTERVAL = 10
        private const val DOOR_NEAR_DISTANCE_SQ = 2.25

        /** How far along the path, in blocks, the pace is planned. */
        private const val PLANNING_REACH = 16.0

        /** How high on the NPC, as a share of its eye height, the point it looks at along its path lies. */
        private const val LOOK_HEIGHT = 0.85
        private const val HEAD_TURN = 10f
        private const val HEAD_PITCH = 20f

        /** A point behind the NPC by more than this, as the cosine of the angle off its facing, is walked to backward. */
        private const val BACKSTEP_COS = -0.5
        private const val MIN_BACKSTEP = 0.3

        /** How close to the middle of the node before a jump the NPC gets before it starts its run-up. */
        private const val TAKEOFF_REACH = 0.3

        /** How many ticks what a move aims at holds without being said again. */
        private const val AIM_TICKS = 1L

        /** How near a node, in blocks, the NPC may cut it short, as vanilla allows. */
        private const val CUT_REACH = 2.0

        /** How close to the point it walks to the NPC has to come to be there. */
        private const val EXACT_REACH = 0.1

        /** How far from the middle of the last node's block, each way, a point still counts as in it. */
        private const val EXACT_SPAN = 0.75

        /** Blocks per tick below which an NPC that jumped onto a node has stopped on it. */
        private const val SETTLED_SPEED = 0.02

        /** How close to the way on, as a cosine, the NPC has to move for a landing to need no stop: about 30 degrees. */
        private const val CARRY_ON_COS = 0.866
    }
}
