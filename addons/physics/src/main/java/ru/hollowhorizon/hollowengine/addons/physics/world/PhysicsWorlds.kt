package ru.hollowhorizon.hollowengine.addons.physics.world

import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import java.util.IdentityHashMap

/**
 * The simulation of each level, created when something in that level first needs one.
 */
object PhysicsWorlds {
    private val worlds = IdentityHashMap<Level, PhysicsWorld>()

    /** The simulation of [level], if one has been started. */
    fun find(level: Level): PhysicsWorld? = worlds[level]

    fun of(level: Level): PhysicsWorld? {
        worlds[level]?.let { return it }
        if (!JoltNatives.isAvailable) return null

        return runCatching { PhysicsWorld(level) }
            .onFailure { HollowEngine.LOGGER.error("Could not start a physics world", it) }
            .getOrNull()
            ?.also { worlds[level] = it }
    }

    fun retainOnly(level: Level?) {
        if (worlds.isEmpty()) return

        val iterator = worlds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key === level) continue
            entry.value.close()
            iterator.remove()
        }
    }

    fun closeAll() = retainOnly(null)
}
