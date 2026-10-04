package ru.hollowhorizon.hollowengine.common.entities.objects

import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import java.util.UUID

/**
 * The objects in each level, by UUID: how a child finds its parent every tick, and what the scene window
 * lists.
 */
object WorldObjects {
    private val server = HashMap<Level, HashMap<UUID, WorldObjectEntity>>()
    private val client = HashMap<Level, HashMap<UUID, WorldObjectEntity>>()

    /** Registered once by the client at start-up, called from either side's thread. */
    @Volatile
    private var changeListeners: List<(Level) -> Unit> = emptyList()

    private fun side(level: Level) = if (level.isClientSide) client else server

    fun find(level: Level, uuid: UUID): WorldObjectEntity? = side(level)[level]?.get(uuid)

    fun all(level: Level): Collection<WorldObjectEntity> = side(level)[level]?.values.orEmpty()

    /** Called with the level whenever an object enters or leaves it, or changes what the scene shows of it. */
    @Synchronized
    fun onChanged(listener: (Level) -> Unit) {
        changeListeners += listener
    }

    internal fun add(entity: WorldObjectEntity) {
        val objects = side(entity.level()).getOrPut(entity.level()) { HashMap() }
        if (objects.put(entity.uuid, entity) !== entity) changed(entity.level())
    }

    internal fun remove(entity: WorldObjectEntity) {
        val objects = side(entity.level())[entity.level()] ?: return
        if (objects[entity.uuid] !== entity) return
        objects.remove(entity.uuid)
        changed(entity.level())
    }

    internal fun changed(level: Level) {
        changeListeners.forEach { it(level) }
    }

    internal fun forget(level: Level) {
        side(level).remove(level) ?: return
        changed(level)
    }
}

@SubscribeEvent
fun onWorldObjectsLevelUnload(event: LevelEvent.Unload) {
    WorldObjects.forget(event.level)
}
