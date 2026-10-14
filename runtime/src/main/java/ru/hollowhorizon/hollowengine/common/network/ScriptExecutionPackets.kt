package ru.hollowhorizon.hollowengine.common.network

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.ui.ide.ScriptExecutionClient
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.scripting.execution.ScriptExecutionStatus
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions

@Serializable
enum class ScriptExecutionOperation { RUN, STOP }

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RequestScriptExecutionPacket(
    val path: String,
    val operation: ScriptExecutionOperation,
    val state: String? = null,
) : HollowPacket {
    override fun handle(player: Player) {
        val serverPlayer = player as? ServerPlayer ?: return
        if (!player.hasPermissions(PlayerPermissions.GAMEMASTER)) {
            ScriptExecutionStatePacket(allowed = false, reset = true).send(serverPlayer)
            return
        }
        val runs = serverPlayer.server.runtimeContext.scriptRuns
        runCatching {
            when (operation) {
                ScriptExecutionOperation.RUN -> runs.start(path, state)
                ScriptExecutionOperation.STOP -> runs.stop(path)
            }
            runs.synchronize()
        }.onFailure { failure ->
            ScriptExecutionStatePacket(mapOf(path to ScriptExecutionState(error = failure.message ?: failure.javaClass.simpleName)))
                .send(serverPlayer)
        }
    }
}

@Serializable
data class ScriptExecutionState(
    val status: ScriptExecutionStatus = ScriptExecutionStatus.IDLE,
    val error: String? = null,
)

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class ScriptExecutionStatePacket(
    val states: Map<String, ScriptExecutionState> = emptyMap(),
    val allowed: Boolean = true,
    val reset: Boolean = false,
) : HollowPacket {
    override fun handle(player: Player) { ScriptExecutionClient.accept(this) }
}
