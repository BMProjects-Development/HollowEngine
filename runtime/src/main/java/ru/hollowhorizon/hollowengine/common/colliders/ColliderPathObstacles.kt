package ru.hollowhorizon.hollowengine.common.colliders

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.bootstrap.runtime.PathObstacles
import ru.hollowhorizon.hollowengine.common.entities.EntityBodies

/**
 * The block cells of each level that solid colliders and blocking bodies fill, for the path search of every
 * mob. It plans around them as it plans around blocks.
 */
internal object ColliderPathObstacles {
    /** How far into a cell a collider has to reach sideways before the cell counts as filled. */
    private const val SIDE_MARGIN = 0.1

    /** The same from below and above: a collider resting on the floor does not fill the cell under it. */
    private const val VERTICAL_MARGIN = 0.05

    private class Occupied(val shapes: List<ColliderVolume>, val cells: LongOpenHashSet)

    private class LevelObstacles {
        val byCell = Long2ObjectOpenHashMap<MutableList<Entity>>()
        val byEntity = HashMap<Entity, Occupied>()

        fun blocks(cell: Long, mob: Mob): Boolean =
            byCell.get(cell)?.any { it !== mob && it.rootVehicle !== mob.rootVehicle } == true
    }

    private val levels = HashMap<Level, LevelObstacles>()

    /** The obstacles [mob]'s path search plans around, or null when its level has none. */
    fun of(mob: Mob): PathObstacles? {
        val level = mob.level() as? ServerLevel ?: return null
        val obstacles = levels[level] ?: return null
        return PathObstacles { x, y, z -> obstacles.blocks(BlockPos.asLong(x, y, z), mob) }
    }

    /** Whether a collider or a blocking body of an entity other than [mob] reaches into [box]. */
    fun overlaps(mob: Mob, box: AABB): Boolean {
        val obstacles = levels[mob.level()] ?: return false
        return obstacles.byEntity.any { (entity, occupied) ->
            entity !== mob && entity.rootVehicle !== mob.rootVehicle &&
                    occupied.shapes.any { it.bounds.intersects(box) && it.penetration(box) != null }
        }
    }

    /** Takes the obstacles of [level] from [entities], those with attachments, as the colliders stand this tick. */
    fun update(level: ServerLevel, entities: List<Entity>) {
        val state = levels[level] ?: LevelObstacles()
        val changed = Long2ObjectOpenHashMap<Entity>()
        val present = HashSet<Entity>()

        for (entity in entities) {
            if (entity.isRemoved) continue
            val shapes = EntityBodies.shapesOf(entity)
            if (shapes.isEmpty()) continue
            present += entity

            val old = state.byEntity[entity]
            if (old != null && sameShapes(old.shapes, shapes)) continue
            val cells = cellsOf(shapes)
            old?.cells?.forEach { cell -> if (cell !in cells) state.remove(entity, cell, changed) }
            cells.forEach { cell -> if (old == null || cell !in old.cells) state.add(entity, cell, changed) }
            state.byEntity[entity] = Occupied(shapes, cells)
        }

        val gone = state.byEntity.keys.filter { it !in present }
        gone.forEach { entity -> state.byEntity.remove(entity)?.cells?.forEach { state.remove(entity, it, changed) } }

        if (state.byEntity.isEmpty()) levels.remove(level) else levels[level] = state
        if (changed.isNotEmpty()) replan(level, changed)
    }

    fun forget(level: Level) {
        levels.remove(level)
    }

    private fun LevelObstacles.add(entity: Entity, cell: Long, changed: Long2ObjectOpenHashMap<Entity>) {
        byCell.computeIfAbsent(cell) { ArrayList(1) }.add(entity)
        changed.put(cell, entity)
    }

    private fun LevelObstacles.remove(entity: Entity, cell: Long, changed: Long2ObjectOpenHashMap<Entity>) {
        val owners = byCell.get(cell) ?: return
        owners.remove(entity)
        if (owners.isEmpty()) byCell.remove(cell)
        changed.put(cell, entity)
    }

    private fun sameShapes(old: List<ColliderVolume>, new: List<ColliderVolume>): Boolean =
        old.size == new.size && old.indices.all { old[it].sameAs(new[it]) }

    private fun cellsOf(shapes: List<ColliderVolume>): LongOpenHashSet {
        val cells = LongOpenHashSet()
        for (shape in shapes) {
            val bounds = shape.bounds
            for (x in Mth.floor(bounds.minX + SIDE_MARGIN)..Mth.floor(bounds.maxX - SIDE_MARGIN)) {
                for (y in Mth.floor(bounds.minY + VERTICAL_MARGIN)..Mth.floor(bounds.maxY - VERTICAL_MARGIN)) {
                    for (z in Mth.floor(bounds.minZ + SIDE_MARGIN)..Mth.floor(bounds.maxZ - SIDE_MARGIN)) {
                        val cell = BlockPos.asLong(x, y, z)
                        if (cell in cells) continue
                        if (shape.penetration(cellBox(x, y, z)) != null) cells.add(cell)
                    }
                }
            }
        }
        return cells
    }

    private fun cellBox(x: Int, y: Int, z: Int) = AABB(
        x + SIDE_MARGIN, y + VERTICAL_MARGIN, z + SIDE_MARGIN,
        x + 1.0 - SIDE_MARGIN, y + 1.0 - VERTICAL_MARGIN, z + 1.0 - SIDE_MARGIN,
    )

    private fun replan(level: ServerLevel, changed: Long2ObjectOpenHashMap<Entity>) {
        val pos = BlockPos.MutableBlockPos()
        for (mob in level.navigatingMobs.toList()) {
            val navigation = mob.navigation
            val near = changed.long2ObjectEntrySet().any { entry ->
                val owner = entry.value
                owner !== mob && owner.rootVehicle !== mob.rootVehicle &&
                        navigation.shouldRecomputePath(pos.set(BlockPos.getX(entry.longKey), BlockPos.getY(entry.longKey), BlockPos.getZ(entry.longKey)))
            }
            if (near) navigation.recomputePath()
        }
    }
}
