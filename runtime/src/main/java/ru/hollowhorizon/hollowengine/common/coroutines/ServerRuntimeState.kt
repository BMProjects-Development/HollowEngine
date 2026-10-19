package ru.hollowhorizon.hollowengine.common.coroutines

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

private const val HOLLOWENGINE_RUNTIME_FILE = "hollowengine-server-runtime.dat"

private data class ServerRuntimeStateEntry(
    val runtimeContext: ServerRuntimeContext,
    val runtimePath: Path,
)

object ServerRuntimeState {
    private val states = Collections.synchronizedMap(WeakHashMap<MinecraftServer, ServerRuntimeStateEntry>())

    fun create(server: MinecraftServer, levelRoot: Path) {
        states.computeIfAbsent(server) {
            ServerRuntimeStateEntry(
                runtimeContext = ServerRuntimeContext(server, levelRoot),
                runtimePath = levelRoot.resolve("data").resolve(HOLLOWENGINE_RUNTIME_FILE),
            )
        }
    }

    fun load(server: MinecraftServer) {
        val state = entry(server)
        if (!Files.exists(state.runtimePath)) return

        try {
            Files.newInputStream(state.runtimePath).use { stream ->
                val tag = stream.loadAsNBT()
                if (tag is CompoundTag) {
                    entry(server).runtimeContext.deserialize(tag)
                }
            }
        } catch (exception: Exception) {
            HollowEngine.LOGGER.error("Failed to load HollowEngine server runtime from {}", state.runtimePath, exception)
        }
    }

    @SubscribeEvent
    fun onSaveLevel(event: LevelEvent.Save) {
        if (event.level.dimension() != Level.OVERWORLD) return
        save(event.level.server ?: return)
    }

    fun save(server: MinecraftServer) {
        val state = entry(server)

        try {
            Files.createDirectories(state.runtimePath.parent)
            val tag = CompoundTag()
            state.runtimeContext.serialize(tag)
            Files.newOutputStream(state.runtimePath).use { stream -> tag.save(stream) }
        } catch (exception: Exception) {
            HollowEngine.LOGGER.error("Failed to save HollowEngine server runtime to {}", state.runtimePath, exception)
        }
    }

    fun remove(server: MinecraftServer) {
        states.remove(server)?.runtimeContext?.dispose()
    }

    fun context(server: MinecraftServer): ServerRuntimeContext = entry(server).runtimeContext

    /** The server's runtime state, or null outside its lifetime: before it is created or after it stopped. */
    fun contextOrNull(server: MinecraftServer): ServerRuntimeContext? = states[server]?.runtimeContext

    /** Every server with live runtime state, for engine-wide events such as an addon being reloaded. */
    fun servers(): List<MinecraftServer> = synchronized(states) { states.keys.toList() }

    private fun entry(server: MinecraftServer) =
        states[server] ?: error("Server runtime state is not initialized for $server")
}
