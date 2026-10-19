package ru.hollowhorizon.hollowengine.common.events.level

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

open class LevelEvent(val level: Level) : Event {
    class Save(level: Level) : LevelEvent(level) {
        companion object : EventHandler<Save>()
    }

    class Load(level: Level) : LevelEvent(level) {
        companion object : EventHandler<Load>()
    }

    /** The entities saved in [chunk] have been read and added to the level. */
    class ChunkEntitiesLoaded(val serverLevel: ServerLevel, val chunk: ChunkPos) : LevelEvent(serverLevel) {
        companion object : EventHandler<ChunkEntitiesLoaded>()
    }

    /** The level is closing, on either side: whatever is kept per level has to let go of it. */
    class Unload(level: Level) : LevelEvent(level) {
        companion object : EventHandler<Unload>()
    }
}
