package ru.hollowhorizon.hollowengine.addons.physics.world

import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.JoltNatives
import java.util.IdentityHashMap

/**
 * The simulation of each level, created when something in that level first needs one.
 *
 * Client and server levels are kept apart: in a single-player game both run at once on their own threads,
 * and each side opens and closes only its own.
 */
object PhysicsWorlds {
    private val client = IdentityHashMap<Level, PhysicsWorld>()
    private val server = IdentityHashMap<Level, PhysicsWorld>()

    private fun worldsOf(level: Level) = if (level.isClientSide) client else server

    /** The simulation of [level], if one has been started. */
    fun find(level: Level): PhysicsWorld? = worldsOf(level)[level]

    fun of(level: Level): PhysicsWorld? {
        val worlds = worldsOf(level)
        worlds[level]?.let { return it }
        if (!JoltNatives.isAvailable) return null

        return runCatching { PhysicsWorld(level) }
            .onFailure { HollowEngine.LOGGER.error("Could not start a physics world", it) }
            .getOrNull()
            ?.also { worlds[level] = it }
    }

    /** Closes every client simulation but the one of [level], the level the player is in now. */
    fun retainClient(level: Level?) {
        val iterator = client.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key === level) continue
            entry.value.close()
            iterator.remove()
        }
    }

    /** The server's simulations, one per loaded level that has one. */
    val serverWorlds: Collection<PhysicsWorld> get() = server.values

    fun close(level: Level) {
        worldsOf(level).remove(level)?.close()
    }

    fun closeAll() {
        retainClient(null)
        server.values.forEach(PhysicsWorld::close)
        server.clear()
    }
}
