package ru.hollowhorizon.hollowengine.common.npcs.navigation

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Air node of a path search. A cell can have both a ground node and an air node, so takeoff and landing
 * are separate search steps with their own costs.
 */
internal class AirNode(x: Int, y: Int, z: Int) : Node(x, y, z)

/**
 * The air part of one path search: 26-neighbor moves between free cells and their flight costs.
 *
 * @param pathType path type of a cell for the mob's body, as the search sees it (avoid rules included)
 */
internal class FlightLayer(
    private val level: CollisionGetter,
    private val mob: Mob,
    private val settings: FlightSettings,
    private val pathType: (Int, Int, Int) -> PathType,
) {
    private val nodes = Int2ObjectOpenHashMap<AirNode>()
    private val open = Long2BooleanOpenHashMap()
    private val heights = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private val cell = BlockPos.MutableBlockPos()

    /** Flight cost has nothing to compare with when the NPC never walks. */
    private val multiplier = if (settings.flies == FlightMode.ALWAYS) 1f else settings.flightCost
    private val climbSine = sin(Math.toRadians(settings.climbAngle.toDouble().coerceIn(MIN_CLIMB, 90.0))).toFloat()
    private val tracksHeight = settings.heightCost > 0f || settings.maxHeight > 0f
    private val heightReach = min(MAX_HEIGHT_REACH, ceil(max(settings.cruiseHeight, settings.maxHeight)).toInt() + HEIGHT_SLACK)

    /** Air node at the mob's cell, or right above or below it if that one is blocked. */
    fun start(): AirNode? {
        val origin = FlightGeometry.cellOf(mob, mob.position())
        for (dy in START_OFFSETS) {
            node(origin.x, origin.y + dy, origin.z)?.let { return it }
        }
        return null
    }

    /** Air node of cell ([x], [y], [z]), or null if the body does not fit there. */
    fun node(x: Int, y: Int, z: Int): AirNode? {
        if (!isOpen(x, y, z)) return null
        val hash = Node.createHash(x, y, z)
        nodes.get(hash)?.let { return it }
        val node = AirNode(x, y, z)
        node.type = pathType(x, y, z)
        node.costMalus = mob.getPathfindingMalus(node.type)
        nodes.put(hash, node)
        return node
    }

    /**
     * Writes the open air neighbors of [from] into [output].
     *
     * @param start index in [output] to start writing at
     * @return index after the last written neighbor
     */
    fun neighbors(output: Array<Node>, start: Int, from: Node): Int {
        var count = start
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val x = from.x + dx
            val y = from.y + dy
            val z = from.z + dz
            if (!isOpen(x, y, z) || !cornersOpen(from, dx, dy, dz) || !underCeiling(x, y, z, dy)) continue
            val node = node(x, y, z) ?: continue
            if (!node.closed) output[count++] = node
        }
        return count
    }

    /**
     * Extra cost of flying from [from] to [to], on top of the straight distance.
     *
     * A step steeper than [FlightSettings.climbAngle] counts as long as climbing it at that angle would be.
     * The result includes [FlightSettings.flightCost] and the [FlightSettings.heightCost] penalty.
     */
    fun stepCost(from: Node, to: Node): Float {
        val length = from.distanceTo(to)
        val flown = max(length, abs(to.y - from.y) / climbSine)
        var cost = flown * multiplier - length
        if (settings.heightCost > 0f) cost += flown * settings.heightCost * abs(height(to.x, to.y, to.z) - settings.cruiseHeight)
        return cost
    }

    private fun isOpen(x: Int, y: Int, z: Int): Boolean {
        val key = BlockPos.asLong(x, y, z)
        if (open.containsKey(key)) return open.get(key)
        val type = pathType(x, y, z)
        val result = type !in CLOSED_TYPES && mob.getPathfindingMalus(type) >= 0f &&
                FlightGeometry.fits(level, mob, FlightGeometry.nodeCenter(mob, x, y, z))
        open.put(key, result)
        return result
    }

    /** A diagonal step needs every shorter step it is made of to be open, so it never cuts a corner. */
    private fun cornersOpen(from: Node, dx: Int, dy: Int, dz: Int): Boolean {
        val axes = (if (dx != 0) 1 else 0) + (if (dy != 0) 1 else 0) + (if (dz != 0) 1 else 0)
        if (axes < 2) return true
        for (mx in if (dx == 0) ZERO else intArrayOf(0, dx)) for (my in if (dy == 0) ZERO else intArrayOf(0, dy)) {
            for (mz in if (dz == 0) ZERO else intArrayOf(0, dz)) {
                if (mx == dx && my == dy && mz == dz || mx == 0 && my == 0 && mz == 0) continue
                if (!isOpen(from.x + mx, from.y + my, from.z + mz)) return false
            }
        }
        return true
    }

    /** Moving down is always allowed, so an NPC above the ceiling can still descend. */
    private fun underCeiling(x: Int, y: Int, z: Int, dy: Int): Boolean =
        settings.maxHeight <= 0f || dy < 0 || height(x, y, z) <= settings.maxHeight

    /**
     * Blocks of air below cell ([x], [y], [z]), capped at [heightReach]. Every cell passed on the way down is
     * cached too, so each column is scanned once.
     */
    private fun height(x: Int, y: Int, z: Int): Int {
        if (!tracksHeight) return 0
        val key = BlockPos.asLong(x, y, z)
        val known = heights.get(key)
        if (known >= 0) return known
        for (depth in 0 until heightReach) {
            val below = heights.get(BlockPos.asLong(x, y - depth, z))
            val exact = when {
                below >= 0 -> depth + below
                FlightGeometry.isGround(level, cell.set(x, y - depth - 1, z)) -> depth
                else -> continue
            }
            val found = min(heightReach, exact)
            if (found < heightReach) {
                for (step in 0..depth) heights.put(BlockPos.asLong(x, y - step, z), found - step)
            } else {
                heights.put(key, heightReach)
            }
            return found
        }
        heights.put(key, heightReach)
        return heightReach
    }

    private companion object {
        val CLOSED_TYPES = setOf(PathType.WATER, PathType.LAVA, PathType.WALKABLE_DOOR, PathType.BLOCKED)
        val ZERO = intArrayOf(0)
        val START_OFFSETS = intArrayOf(0, 1, -1)

        /** Keeps the cost of a vertical step finite, in degrees. */
        const val MIN_CLIMB = 5.0

        const val HEIGHT_SLACK = 2
        const val MAX_HEIGHT_REACH = 72
    }
}
