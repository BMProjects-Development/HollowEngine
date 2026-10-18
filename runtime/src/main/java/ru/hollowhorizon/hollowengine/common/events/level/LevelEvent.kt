package ru.hollowhorizon.hollowengine.common.events.level

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

    /** The level is closing, on either side: whatever is kept per level has to let go of it. */
    class Unload(level: Level) : LevelEvent(level) {
        companion object : EventHandler<Unload>()
    }
}
