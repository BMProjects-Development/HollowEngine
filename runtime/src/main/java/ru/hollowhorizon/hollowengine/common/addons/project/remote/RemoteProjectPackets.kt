package ru.hollowhorizon.hollowengine.common.addons.project.remote

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.ui.ide.remote.RemoteProjectConnection
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
internal class RemoteProjectRequestPacket(val id: Long, val command: RemoteProjectCommand) : HollowPacket {
    override fun handle(player: Player) {
        if (player !is ServerPlayer) return
        player.server.execute {
            ServerRuntimeState.contextOrNull(player.server)?.remoteProject?.submit(player, id, command)
        }
    }
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
internal class RemoteProjectReplyPacket(val id: Long, val reply: RemoteProjectReply) : HollowPacket {
    override fun handle(player: Player) = RemoteProjectConnection.receive(id, reply)
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
internal class RemoteProjectChangedPacket(val change: RemoteProjectChange) : HollowPacket {
    override fun handle(player: Player) = RemoteProjectConnection.receive(change)
}

@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
internal class RemoteProjectAccessPacket(val access: RemoteProjectAccess) : HollowPacket {
    override fun handle(player: Player) = RemoteProjectConnection.receiveAccess(access)
}

@SubscribeEvent
fun onRemoteProjectServerTick(event: TickEvent.Server) {
    val server = event.server
    if (!server.isDedicatedServer || server.tickCount % RemoteProjectAccessCheckInterval != 0) return
    val runtime = ServerRuntimeState.contextOrNull(server) ?: return
    if (runtime.remoteProject.synchronizeAccess(server)) runtime.scriptRuns.requestSynchronize()
}

private const val RemoteProjectAccessCheckInterval = 40
