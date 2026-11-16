package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint

/**
 * Describes what a node script is attached to. Every host exposes the [server] it belongs to so
 * lifecycle handlers ([onSave]/[onLoad]) can build a [SerializationContext].
 */
sealed interface NodeHost {
    val server: MinecraftServer

    class Server(override val server: MinecraftServer) : NodeHost

    class OfEntity(val entity: Entity) : NodeHost {
        override val server: MinecraftServer
            get() = entity.server ?: error("Entity ${entity.uuid} is not attached to a server")
    }
}

class NodeBinding(
    val host: NodeHost,
    val scope: CoroutineScope,
    internal val initialTag: CompoundTag? = null,
) {
    internal val server: MinecraftServer = host.server
    internal var fingerprint: ScriptFingerprint.Fingerprint? = null
    internal val stop = NodeStop(scope.coroutineContext.job, server.dispatcher) { exception ->
        HollowEngine.LOGGER.error("Error in node script onStop handler", exception)
    }.also { server.runtimeContext.nodeStops.track(it.completion) }
}
