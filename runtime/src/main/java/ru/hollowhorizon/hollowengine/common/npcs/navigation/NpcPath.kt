package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import kotlin.math.abs
import kotlin.math.max

/** A path with gap jumps and air nodes. */
class NpcPath private constructor(
    private val nodeList: MutableList<Node>,
    target: BlockPos,
    reached: Boolean,
    private val jumps: BooleanArray,
    private val aloft: BooleanArray,
) : Path(nodeList, target, reached) {
    /** Whether the step onto node [index] is a jump over a gap. */
    fun isJumpTo(index: Int): Boolean = jumps.getOrElse(index) { false }

    fun isAloft(index: Int): Boolean = aloft.getOrElse(index) { false }

    /** Whether the step onto node [index] is a flight, including a landing. */
    fun isFlownTo(index: Int): Boolean = isAloft(index) || index > 0 && isAloft(index - 1)

    override fun copy(): Path = NpcPath(nodeList, target, canReach(), jumps, aloft).also {
        it.nextNodeIndex = nextNodeIndex
    }

    companion object {
        /**
         * Builds an [NpcPath] from a search result.
         *
         * @param straighten pull the path into straight lines where nothing is in the way; a line never crosses
         * cells that [avoid] charges for and never joins walking to flying
         */
        internal fun of(path: Path, level: CollisionGetter, mob: Mob, straighten: Boolean, avoid: AvoidRules?): NpcPath {
            val nodes = FlightSteps.collapse(List(path.nodeCount, path::getNode), isAir = { it is AirNode })
            val aloft = BooleanArray(nodes.size) { nodes[it] is AirNode }
            val jumps = BooleanArray(nodes.size) {
                it > 0 && !aloft[it] && !aloft[it - 1] && isGapJump(nodes[it - 1], nodes[it])
            }
            val kept = if (straighten) {
                PathStraightening.keep(
                    nodes,
                    jumps,
                    canSkip = { it.type in SKIPPABLE },
                    canWalk = { from, to ->
                        when {
                            from is AirNode && to is AirNode -> canFlyStraight(level, mob, from, to, avoid)
                            from !is AirNode && to !is AirNode -> canWalkStraight(level, mob, from, to, avoid)
                            else -> false
                        }
                    },
                )
            } else {
                nodes.indices.toList()
            }
            return NpcPath(
                kept.mapTo(ArrayList(kept.size)) { nodes[it] },
                path.target,
                path.canReach(),
                BooleanArray(kept.size) { jumps[kept[it]] },
                BooleanArray(kept.size) { aloft[kept[it]] },
            )
        }

        private fun canWalkStraight(level: CollisionGetter, mob: Mob, from: Node, to: Node, avoid: AvoidRules?): Boolean {
            val start = NpcNavigationGeometry.nodeCenter(
                mob,
                from.x,
                WalkNodeEvaluator.getFloorLevel(level, from.asBlockPos()),
                from.z
            )
            val end = NpcNavigationGeometry.nodeCenter(
                mob,
                to.x,
                WalkNodeEvaluator.getFloorLevel(level, to.asBlockPos()),
                to.z
            )
            if (start.distanceToSqr(end) > MAX_STRAIGHT * MAX_STRAIGHT) return false
            return NpcNavigationGeometry.canWalkDirectly(level, mob, start, end) && avoid?.crosses(start, end) != true
        }

        private fun canFlyStraight(level: CollisionGetter, mob: Mob, from: Node, to: Node, avoid: AvoidRules?): Boolean {
            val start = FlightGeometry.nodeCenter(mob, from.x, from.y, from.z)
            val end = FlightGeometry.nodeCenter(mob, to.x, to.y, to.z)
            if (start.distanceToSqr(end) > MAX_STRAIGHT * MAX_STRAIGHT) return false
            return FlightGeometry.canFlyDirectly(level, mob, start, end) && avoid?.crosses(start, end) != true
        }

        /** The ground a straight line may cross without the NPC having to stop on it, as doors and hazards make it. */
        private val SKIPPABLE = setOf(PathType.WALKABLE, PathType.WATER_BORDER, PathType.OPEN)

        /** The longest straight line a path is pulled into, in blocks. */
        private const val MAX_STRAIGHT = 24.0
    }
}

/** Whether the step from [from] to [to] skips over at least one block: no walking step does. */
internal fun isGapJump(from: Node, to: Node): Boolean = max(abs(to.x - from.x), abs(to.z - from.z)) > 1

internal object FlightSteps {
    /**
     * Removes in-place takeoff and landing steps: when a ground node and an air node of the same cell follow
     * each other, only the ground node is kept.
     */
    fun <T> collapse(nodes: List<T>, isAir: (T) -> Boolean, sameCell: (T, T) -> Boolean = { a, b -> a == b }): List<T> {
        val kept = ArrayList<T>(nodes.size)
        for (node in nodes) {
            val last = kept.lastOrNull()
            if (last == null || !sameCell(last, node) || isAir(last) == isAir(node)) {
                kept += node
            } else if (isAir(last)) {
                kept[kept.lastIndex] = node
            }
        }
        return kept
    }
}

/** String pulling: the nodes of a path the NPC can go between in straight lines. */
internal object PathStraightening {
    /**
     * The indices of [nodes] to keep. From each kept node the line runs on as long as [canWalk] goes from it
     * to the next node and every node it passes is one [canSkip] lets go; the ends of jumps, flagged in
     * [jumps] by the index of the node they land on, are always kept.
     */
    fun <T> keep(nodes: List<T>, jumps: BooleanArray, canSkip: (T) -> Boolean, canWalk: (T, T) -> Boolean): List<Int> {
        if (nodes.size <= 2) return nodes.indices.toList()
        val kept = arrayListOf(0)
        val last = nodes.lastIndex
        var anchor = 0
        while (anchor < last) {
            var end = anchor + 1
            if (!jumps[end]) {
                while (end < last && !jumps[end + 1] && canSkip(nodes[end]) && canWalk(
                        nodes[anchor],
                        nodes[end + 1]
                    )
                ) end++
            }
            kept += end
            anchor = end
        }
        return kept
    }
}
