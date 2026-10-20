package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.common.coroutines.Ref
import ru.hollowhorizon.hollowengine.common.coroutines.entityRef
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.items.ItemFilter
import ru.hollowhorizon.hollowengine.common.npcs.query.EntityQuery
import ru.hollowhorizon.hollowengine.common.npcs.query.EntityQuerySort
import ru.hollowhorizon.hollowengine.common.npcs.query.entityQuery
import ru.hollowhorizon.hollowengine.common.npcs.registry.ServerNpcs
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/**
 * The NPCs of the current world, whether their chunks are loaded or not: `npcs[uuid]`,
 * `npcs["village:blacksmith"]`, `npcs.near(level, pos, radius)`.
 */
val npcs: ServerNpcs get() = currentServer.runtimeContext.npcs

fun <T : Entity> NpcEntity.findEntities(query: EntityQuery<T>): List<Ref<T>> {
    if (query.limit == 0) return emptyList()

    val bounds = AABB.ofSize(position(), query.radius * 2.0, query.radius * 2.0, query.radius * 2.0)
    val entities = level().getEntitiesOfClass(query.type, bounds) { entity ->
        entity !== this &&
                distanceToSqr(entity) <= query.radius * query.radius &&
                (!query.aliveOnly || entity.isAlive) &&
                (!query.visibleOnly || hasLineOfSight(entity)) &&
                query.predicate(entity)
    }

    val ordered = when (query.sort) {
        EntityQuerySort.NONE -> entities
        EntityQuerySort.NEAREST -> entities.sortedBy(::distanceToSqr)
        EntityQuerySort.FARTHEST -> entities.sortedByDescending(::distanceToSqr)
    }

    val server = server ?: return emptyList()
    return with(server) {
        ordered.take(query.limit).map { entityRef(it.uuid, query.type) }
    }
}

fun <T : Entity> NpcEntity.findNearestEntity(query: EntityQuery<T>): Ref<T>? {
    val nearestQuery = EntityQuery(
        type = query.type,
        radius = query.radius,
        aliveOnly = query.aliveOnly,
        visibleOnly = query.visibleOnly,
        limit = 1,
        sort = EntityQuerySort.NEAREST,
        predicate = query.predicate,
    )
    return findEntities(nearestQuery).firstOrNull()
}

fun NpcEntity.findPlayers(
    radius: Double = 16.0,
    filter: (ServerPlayer) -> Boolean = { true },
): List<Ref<ServerPlayer>> = findEntities(entityQuery {
    this.radius = radius
    filter(filter)
})

fun NpcEntity.findNpcs(
    radius: Double = 16.0,
    filter: (NpcEntity) -> Boolean = { true },
): List<Ref<NpcEntity>> = findEntities(entityQuery {
    this.radius = radius
    filter(filter)
})

fun NpcEntity.findItems(
    filter: ItemFilter,
    radius: Double = 16.0,
    limit: Int = Int.MAX_VALUE,
): List<Ref<ItemEntity>> = findEntities(entityQuery {
    this.radius = radius
    this.limit = limit
    aliveOnly = false
    filter { item ->
        !item.isRemoved && !item.item.isEmpty && !item.hasPickUpDelay() && filter.matches(item.item)
    }
})

fun NpcEntity.findNearestItem(filter: ItemFilter, radius: Double = 16.0): Ref<ItemEntity>? =
    findItems(filter, radius, limit = 1).firstOrNull()
