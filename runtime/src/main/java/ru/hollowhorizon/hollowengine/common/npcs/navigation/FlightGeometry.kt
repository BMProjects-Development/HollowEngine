package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Collision queries for a flying NPC. Positions are the bottom center of its body. */
internal object FlightGeometry {
    private const val MAX_SAMPLE_DISTANCE = 0.25
    private const val MIN_SAMPLE_DISTANCE = 1.0e-3
    private const val SPOT_REACH = 2
    private const val SPOT_RISE = 2
    private const val SPOT_DROP = 1

    /** Body position of [mob] at the air node in cell ([x], [y], [z]). */
    fun nodeCenter(mob: Mob, x: Int, y: Int, z: Int): Vec3 = NpcNavigationGeometry.nodeCenter(mob, x, y.toDouble(), z)

    /** Cell of the air node that places [mob]'s body at [position]. */
    fun cellOf(mob: Mob, position: Vec3): BlockPos {
        val offset = Mth.floor(mob.bbWidth + 1.0f) / 2.0
        return BlockPos(Mth.floor(position.x - offset + 0.5), Mth.floor(position.y + 1.0e-3), Mth.floor(position.z - offset + 0.5))
    }

    fun fits(level: CollisionGetter, mob: Mob, position: Vec3): Boolean =
        NpcNavigationGeometry.isFree(level, mob, NpcNavigationGeometry.bodyBox(mob, position))

    fun canFlyDirectly(level: CollisionGetter, mob: Mob, from: Vec3, to: Vec3): Boolean {
        val step = min(MAX_SAMPLE_DISTANCE, max(mob.bbWidth * 0.5, MIN_SAMPLE_DISTANCE))
        val samples = max(1, ceil(from.distanceTo(to) / step).toInt())
        for (sample in 1..samples) {
            if (!fits(level, mob, from.lerp(to, sample.toDouble() / samples))) return false
        }
        return true
    }

    /** Whether [pos] holds a block with collision or a fluid. */
    fun isGround(level: BlockGetter, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.fluidState.isEmpty || !state.getCollisionShape(level, pos).isEmpty
    }

    /**
     * Finds where [mob] lands straight below [from].
     *
     * @param depth how far down to look, in blocks
     * @return the landing position, or null if there is no ground within [depth], the ground is a fluid,
     * or the body does not fit there
     */
    fun landingBelow(level: CollisionGetter, mob: Mob, from: Vec3, depth: Int): Vec3? {
        val cell = BlockPos.MutableBlockPos()
        val x = Mth.floor(from.x)
        val z = Mth.floor(from.z)
        val top = Mth.floor(from.y)
        for (y in top downTo top - depth) {
            cell.set(x, y, z)
            if (!isGround(level, cell)) continue
            if (!level.getFluidState(cell).isEmpty) return null
            val shape = level.getBlockState(cell).getCollisionShape(level, cell)
            val landing = Vec3(from.x, y + shape.max(Direction.Axis.Y), from.z)
            if (landing.y > from.y + 1.0e-3) continue
            return landing.takeIf { fits(level, mob, it) }
        }
        return null
    }

    /**
     * Picks the point a move toward [target] actually heads for.
     *
     * A flying NPC goes to [target] itself if its body fits there. Otherwise the nearest standing spot is used,
     * falling back to the nearest free air cell for NPCs that fly.
     */
    fun spotFor(level: CollisionGetter, mob: Mob, flight: FlightSettings, target: Vec3): Vec3? {
        if (!flight.enabled) return NpcNavigationGeometry.standingSpot(level, mob, target)
        if (fits(level, mob, target)) return target
        if (flight.flies == FlightMode.ALWAYS) return airSpot(level, mob, target)
        return NpcNavigationGeometry.standingSpot(level, mob, target) ?: airSpot(level, mob, target)
    }

    /** Nearest point to [target] where [mob]'s body fits in the air, or null if none is close. */
    fun airSpot(level: CollisionGetter, mob: Mob, target: Vec3): Vec3? {
        if (fits(level, mob, target)) return target
        val origin = cellOf(mob, target)
        var best: Vec3? = null
        var bestDistance = Double.MAX_VALUE
        for (dy in -SPOT_DROP..SPOT_RISE) for (dx in -SPOT_REACH..SPOT_REACH) for (dz in -SPOT_REACH..SPOT_REACH) {
            val center = nodeCenter(mob, origin.x + dx, origin.y + dy, origin.z + dz)
            val distance = center.distanceToSqr(target)
            if (distance >= bestDistance || !fits(level, mob, center)) continue
            best = center
            bestDistance = distance
        }
        return best
    }
}
