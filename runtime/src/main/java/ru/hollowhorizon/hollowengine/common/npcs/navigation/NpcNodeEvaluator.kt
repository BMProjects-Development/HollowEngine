package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.Vec3

class NpcNodeEvaluator(private val settingsOf: () -> NavigationComponent) : WalkNodeEvaluator() {
    private val cardinalNeighbors = arrayOfNulls<Node>(Direction.Plane.HORIZONTAL.count())
    private val diagonalNeighbors = arrayOfNulls<Node>(Direction.Plane.HORIZONTAL.count())
    private var settings = NavigationComponent()
    private var landingRises = IntArray(0)

    init {
        setCanFloat(true)
        setCanOpenDoors(true)
        setCanPassDoors(true)
    }

    override fun prepare(level: PathNavigationRegion, mob: Mob) {
        super.prepare(level, mob)
        settings = settingsOf()
        landingRises = landingRisesOf(settings.jumps)
    }

    override fun getNeighbors(outputArray: Array<Node>, node: Node): Int {
        var count = 0
        var stepHeight = 0
        val aboveType = getCachedPathType(node.x, node.y + 1, node.z)
        val currentType = getCachedPathType(node.x, node.y, node.z)
        if (mob.getPathfindingMalus(aboveType) >= 0.0f && currentType != PathType.STICKY_HONEY) {
            stepHeight = Mth.floor(maxOf(1.0f, mob.maxUpStep()))
        }

        val floorLevel = getFloorLevel(BlockPos(node.x, node.y, node.z))
        for (direction in Direction.Plane.HORIZONTAL) {
            val neighbor = findAcceptedNode(
                node.x + direction.stepX,
                node.y,
                node.z + direction.stepZ,
                stepHeight,
                floorLevel,
                direction,
                currentType,
            )
            cardinalNeighbors[direction.get2DDataValue()] = neighbor
            if (neighbor != null && isNeighborValid(neighbor, node)) outputArray[count++] = neighbor
        }

        for (direction in Direction.Plane.HORIZONTAL) {
            val clockwise = direction.clockWise
            val diagonalX = node.x + direction.stepX + clockwise.stepX
            val diagonalZ = node.z + direction.stepZ + clockwise.stepZ
            val vanillaDiagonal = findAcceptedNode(
                diagonalX,
                node.y,
                diagonalZ,
                stepHeight,
                floorLevel,
                direction,
                currentType,
            )
            val vanillaSidesValid = isDiagonalValid(
                node,
                cardinalNeighbors[direction.get2DDataValue()],
                cardinalNeighbors[clockwise.get2DDataValue()],
            )
            val diagonal = if (vanillaDiagonal != null && vanillaSidesValid && isDiagonalValid(vanillaDiagonal)) {
                vanillaDiagonal
            } else {
                findIndependentDiagonal(node, diagonalX, diagonalZ, floorLevel, vanillaDiagonal)
            }
            diagonalNeighbors[direction.get2DDataValue()] = diagonal ?: vanillaDiagonal
            if (diagonal != null) outputArray[count++] = diagonal
        }

        if (settings.jumps.jumpsGaps && currentType != PathType.WATER) {
            count = addGapJumps(outputArray, count, node, floorLevel)
        }
        return count
    }

    /** Jumps over the gaps around [node], at most one a direction: the nearest ground the NPC lands on. */
    private fun addGapJumps(outputArray: Array<Node>, start: Int, node: Node, floorLevel: Double): Int {
        var count = start
        val takeoff = NpcNavigationGeometry.nodeCenter(mob, node.x, floorLevel, node.z)
        val body by lazy(LazyThreadSafetyMode.NONE) {
            NpcNavigationGeometry.jumpBody(
                mob,
                speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * settings.jumps.runUp,
                friction = NpcNavigationGeometry.frictionUnder(currentContext.level(), takeoff),
            )
        }
        for (direction in Direction.Plane.HORIZONTAL) {
            val clockwise = direction.clockWise
            val cardinal = cardinalNeighbors[direction.get2DDataValue()]
            if (isGap(node, cardinal)) {
                findGapJump(node, takeoff, body, direction.stepX, direction.stepZ)?.let { outputArray[count++] = it }
            }
            val diagonal = diagonalNeighbors[direction.get2DDataValue()]
            if (isGap(node, diagonal)) {
                val stepX = direction.stepX + clockwise.stepX
                val stepZ = direction.stepZ + clockwise.stepZ
                findGapJump(node, takeoff, body, stepX, stepZ)?.let { outputArray[count++] = it }
            }
        }
        return count
    }

    /** Whether the step from [node] onto [neighbor] is no ground to walk on: nothing, a hazard, or a drop. */
    private fun isGap(node: Node, neighbor: Node?): Boolean =
        neighbor == null || neighbor.costMalus < 0.0f || neighbor.y < node.y

    private fun findGapJump(from: Node, takeoff: Vec3, body: JumpBody, stepX: Int, stepZ: Int): Node? {
        val space = NpcNavigationGeometry.jumpSpace(currentContext.level(), mob)
        val maxFall = settings.path.maxFall(mob)
        for (distance in 2..settings.jumps.maxGap + 1) {
            val x = from.x + stepX * distance
            val z = from.z + stepZ * distance
            for (rise in landingRises) {
                val y = from.y + rise
                val type = getCachedPathType(x, y, z)
                val malus = mob.getPathfindingMalus(type)
                if (malus < 0.0f || type == PathType.OPEN || type == PathType.WATER || type == PathType.WALKABLE_DOOR) continue

                val landing = NpcNavigationGeometry.nodeCenter(mob, x, getFloorLevel(BlockPos(x, y, z)), z)
                val jump = JumpSimulation.plan(space, body, takeoff, landing) ?: continue
                if (jump.landing.fall > maxFall) continue

                val node = getNode(x, y, z)
                if (node.closed) return null
                node.type = type
                node.costMalus = maxOf(node.costMalus, malus)
                return node
            }
        }
        return null
    }

    private fun landingRisesOf(jumps: JumpSettings): IntArray = LANDING_RISES.filter { rise ->
        when {
            rise > 0 -> jumps.jumpUp
            rise < 0 -> jumps.jumpDown
            else -> jumps.jumpLevel
        }
    }.toIntArray()

    /**
     * Vanilla gives up on a fall past the mob's limit. Water at the bottom takes the fall, however deep: the
     * NPC drops into it instead of looking for a way down.
     */
    override fun findAcceptedNode(
        x: Int,
        y: Int,
        z: Int,
        verticalDeltaLimit: Int,
        nodeFloorLevel: Double,
        direction: Direction,
        pathType: PathType,
    ): Node? {
        val node = super.findAcceptedNode(x, y, z, verticalDeltaLimit, nodeFloorLevel, direction, pathType)
        if (node == null || node.type != PathType.BLOCKED || node.y >= y || !settings.path.dropDown || !canFloat()) return node
        return findWaterBelow(x, node.y, z) ?: node
    }

    private fun findWaterBelow(x: Int, fromY: Int, z: Int): Node? {
        val bottom = maxOf(mob.level().minBuildHeight, fromY - MAX_WATER_DROP)
        for (y in fromY downTo bottom) {
            when (val type = getCachedPathType(x, y, z)) {
                PathType.OPEN -> continue
                PathType.WATER -> {
                    val malus = mob.getPathfindingMalus(type)
                    if (malus < 0.0f) return null
                    val node = getNode(x, y, z)
                    node.type = type
                    node.costMalus = malus
                    return node
                }
                else -> return null
            }
        }
        return null
    }

    private fun findIndependentDiagonal(
        from: Node,
        x: Int,
        z: Int,
        fromFloor: Double,
        vanillaCandidate: Node?,
    ): Node? {
        if (vanillaCandidate != null &&
            isDiagonalValid(vanillaCandidate) &&
            canTraverseDiagonal(from, vanillaCandidate, fromFloor)
        ) {
            return vanillaCandidate
        }

        for (yOffset in DIAGONAL_Y_OFFSETS) {
            val y = from.y + yOffset
            if (vanillaCandidate?.x == x && vanillaCandidate.y == y && vanillaCandidate.z == z) continue

            val pathType = getCachedPathType(x, y, z)
            val malus = mob.getPathfindingMalus(pathType)
            if (malus < 0.0f || pathType == PathType.OPEN || pathType == PathType.WALKABLE_DOOR) continue

            val candidate = getNode(x, y, z)
            candidate.type = pathType
            candidate.costMalus = maxOf(candidate.costMalus, malus)
            if (isDiagonalValid(candidate) && canTraverseDiagonal(from, candidate, fromFloor)) {
                return candidate
            }
        }
        return null
    }

    private fun canTraverseDiagonal(from: Node, to: Node, fromFloor: Double): Boolean {
        val toFloor = getFloorLevel(BlockPos(to.x, to.y, to.z))
        val fromPosition = NpcNavigationGeometry.nodeCenter(mob, from.x, fromFloor, from.z)
        val toPosition = NpcNavigationGeometry.nodeCenter(mob, to.x, toFloor, to.z)
        return NpcNavigationGeometry.findWalkableWaypoint(
            currentContext.level(),
            mob,
            fromPosition,
            toPosition,
        ) != null || NpcNavigationGeometry.canJumpDirectly(
            currentContext.level(),
            mob,
            fromPosition,
            toPosition,
        ) || NpcNavigationGeometry.canDropDirectly(
            currentContext.level(),
            mob,
            fromPosition,
            toPosition,
        ) || NpcNavigationGeometry.canSqueezeDiagonally(
            currentContext.level(),
            mob,
            fromPosition,
            toPosition,
        )
    }

    /** What the step from [from] to [to] costs on top of its length: a jump is worth [JumpSettings.jumpCost] blocks of walking. */
    internal fun additionalTravelCost(from: Node, to: Node): Float {
        if (isGapJump(from, to)) return settings.jumps.jumpCost

        val fromFloor = getFloorLevel(BlockPos(from.x, from.y, from.z))
        val toFloor = getFloorLevel(BlockPos(to.x, to.y, to.z))
        if (toFloor - fromFloor <= mob.maxUpStep() + HEIGHT_EPSILON) return 0.0f

        val surfacePos = BlockPos(to.x, to.y - 1, to.z)
        return if (NpcNavigationGeometry.hasStepableSurface(
                currentContext.level(),
                surfacePos,
                mob.maxUpStep().toDouble(),
                NpcNavigationGeometry.nodeCenter(mob, from.x, fromFloor, from.z),
            )
        ) {
            0.0f
        } else {
            settings.jumps.jumpCost
        }
    }

    private companion object {
        const val HEIGHT_EPSILON = 1.0e-3
        val DIAGONAL_Y_OFFSETS = intArrayOf(0, 1, -1)

        /** Where a jump over a gap may land, relative to where it takes off, in the order they are tried. */
        val LANDING_RISES = intArrayOf(0, 1, -1, -2)

        /** How deep under a ledge water is still looked for. */
        const val MAX_WATER_DROP = 64
    }
}
