package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.PathNavigationRegion
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathFinder

class NpcPathFinder(
    private val evaluator: NpcNodeEvaluator,
    maxVisitedNodes: Int,
    private val settingsOf: () -> NavigationComponent,
) : PathFinder(evaluator, maxVisitedNodes) {
    override fun findPath(
        region: PathNavigationRegion,
        mob: Mob,
        targetPositions: Set<BlockPos>,
        maxRange: Float,
        accuracy: Int,
        searchDepthMultiplier: Float,
    ): Path? {
        val path = super.findPath(region, mob, targetPositions, maxRange, accuracy, searchDepthMultiplier) ?: return null
        return NpcPath.of(path, region, mob, settingsOf().path.straighten, evaluator.avoid)
    }

    override fun distance(first: Node, second: Node): Float {
        return super.distance(first, second) + evaluator.additionalTravelCost(first, second)
    }
}
