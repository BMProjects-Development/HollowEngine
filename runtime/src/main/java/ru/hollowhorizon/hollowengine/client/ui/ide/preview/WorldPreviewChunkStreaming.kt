package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.level.ChunkPos
import ru.hollowhorizon.hollowengine.bridge.client.CameraChunkCache
import ru.hollowhorizon.hollowengine.common.network.CameraChunksPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.MAX_CHUNK_CAMERAS
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.network.CommonNetworkManager

internal class WorldPreviewChunkStreaming {
    private val channel =
        CustomPacketPayload.Type<CameraChunksPacket>(HollowPacket.nameFor(CameraChunksPacket::class).rl)
    private var level: ClientLevel? = null
    private var player: LocalPlayer? = null
    private var sent = emptyList<Long>()
    private var lastUpdate = 0L

    fun update(minecraft: Minecraft, centers: Collection<ChunkPos>) {
        val level = minecraft.level
        val player = minecraft.player
        if (this.level !== level || this.player !== player) {
            this.level = level
            this.player = player
            sent = emptyList()
            lastUpdate = 0L
        }
        if (level == null || player == null) return

        val allowed = minecraft.hasSingleplayerServer() || player.hasPermissions(PlayerPermissions.GAMEMASTER)
        val requested =
            if (allowed) centers.asSequence().distinct().take(MAX_CHUNK_CAMERAS).map(ChunkPos::toLong).sorted().toList()
            else emptyList()
        if (sent == requested) return
        val now = System.nanoTime()
        if (requested.isNotEmpty() && lastUpdate != 0L && now - lastUpdate < UPDATE_INTERVAL_NANOS) return
        if (!CommonNetworkManager.canSendToServer(channel)) return
        if (requested.isNotEmpty()) (level.chunkSource as CameraChunkCache).`hollowengine$enableCameraChunks`()
        CameraChunksPacket(level.dimension().location(), requested).send()
        sent = requested
        lastUpdate = now
    }

    fun stop(minecraft: Minecraft) {
        update(minecraft, emptyList())
        level = null
        player = null
        sent = emptyList()
    }

    private companion object {
        const val UPDATE_INTERVAL_NANOS = 200_000_000L
    }
}
