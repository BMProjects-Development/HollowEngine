package ru.hollowhorizon.hollowengine.common.npcs.navigation

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.max

/**
 * What one path search keeps an NPC off, and what the cells it may still go through cost on top of their length.
 */
internal class AvoidRules(
    private val level: BlockGetter,
    private val settings: AvoidSettings,
    private val zones: List<Zone>,
    private val bodyHeight: Int,
    start: BlockPos,
    startsInWater: Boolean,
) {
    private class BlockRule(val matches: (BlockState) -> Boolean, val cost: Float)

    private val blockRules = settings.blocks.mapNotNull { entry -> matcher(entry.block)?.let { BlockRule(it, entry.cost) } }
    private val costs = Long2FloatOpenHashMap().apply { defaultReturnValue(Float.NaN) }
    private val mutable = BlockPos.MutableBlockPos()

    private val escapedZones = zones.filter { it.cost == null && it.contains(start.x + 0.5, start.y + 0.5, start.z + 0.5) }.toSet()
    private val escapedBlocks = blockRules.filter { it.cost < 0f && touches(start.x, start.y, start.z, it) }.toSet()
    private val escapesWater = startsInWater

    /** True when nothing is avoided, so a search may skip asking. */
    val isEmpty: Boolean = blockRules.isEmpty() && zones.isEmpty() && settings.swim

    /** What standing in cell ([x], [y], [z]) costs on top of walking there; [FORBIDDEN] when the NPC may not. */
    fun cost(x: Int, y: Int, z: Int): Float {
        if (isEmpty) return 0f
        val key = BlockPos.asLong(x, y, z)
        val cached = costs.get(key)
        if (!cached.isNaN()) return cached
        val cost = computeCost(x, y, z)
        costs.put(key, cost)
        return cost
    }

    /**
     * Whether a straight walk from [from] to [to] crosses a cell that costs anything or is forbidden, besides the
     * cells it starts and ends in: those the path chose. Cutting a corner may only cut through cells that are free.
     */
    fun crosses(from: Vec3, to: Vec3): Boolean {
        if (isEmpty) return false
        val first = BlockPos.containing(from.x, from.y + FEET_LIFT, from.z)
        val last = BlockPos.containing(to.x, to.y + FEET_LIFT, to.z)
        val steps = ceil(Mth.length(to.x - from.x, to.z - from.z) / SAMPLE_STEP).toInt().coerceAtLeast(1)
        for (step in 1 until steps) {
            val point = from.lerp(to, step.toDouble() / steps)
            val x = Mth.floor(point.x)
            val y = Mth.floor(point.y + FEET_LIFT)
            val z = Mth.floor(point.z)
            if (x == first.x && y == first.y && z == first.z || x == last.x && y == last.y && z == last.z) continue
            if (cost(x, y, z) != 0f) return true
        }
        return false
    }

    private fun computeCost(x: Int, y: Int, z: Int): Float {
        var total = 0f
        for (zone in zones) {
            if (!zone.contains(x + 0.5, y + 0.5, z + 0.5)) continue
            val cost = if (zone in escapedZones) ESCAPE_COST else zone.cost ?: return FORBIDDEN
            total = max(total, cost)
        }
        for (rule in blockRules) {
            if (!touches(x, y, z, rule)) continue
            val cost = if (rule.cost >= 0f) rule.cost else if (rule in escapedBlocks) ESCAPE_COST else return FORBIDDEN
            total = max(total, cost)
        }
        if (!settings.swim && isWater(x, y, z) && isWater(x, y + 1, z)) {
            if (!escapesWater) return FORBIDDEN
            total = max(total, ESCAPE_COST)
        }
        return total
    }

    /** Whether the floor under cell ([x], [y], [z]) or a block the body fills there is one [rule] is about. */
    private fun touches(x: Int, y: Int, z: Int, rule: BlockRule): Boolean {
        for (dy in -1 until bodyHeight) {
            if (rule.matches(level.getBlockState(mutable.set(x, y + dy, z)))) return true
        }
        return false
    }

    private fun isWater(x: Int, y: Int, z: Int): Boolean = level.getFluidState(mutable.set(x, y, z)).`is`(FluidTags.WATER)

    companion object {
        const val FORBIDDEN = -1f

        /** What a block the NPC may not enter costs while it walks out of one it stands in. */
        const val ESCAPE_COST = 16f

        /** How far apart, in blocks, a straight walk is looked at, and how far above the floor its feet are. */
        private const val SAMPLE_STEP = 0.25
        private const val FEET_LIFT = 0.01

        /** What an entry of the block list names: a block id, or a block tag written `#namespace:path`; null when neither. */
        private fun matcher(text: String): ((BlockState) -> Boolean)? {
            val name = text.trim()
            if (name.startsWith("#")) {
                val id = ResourceLocation.tryParse(name.drop(1)) ?: return null
                val tag = TagKey.create(Registries.BLOCK, id)
                return { it.`is`(tag) }
            }
            val id = ResourceLocation.tryParse(name) ?: return null
            val block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null) ?: return null
            return { it.`is`(block) }
        }
    }
}
