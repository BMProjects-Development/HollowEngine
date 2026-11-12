package ru.hollowhorizon.hollowengine.common.network

import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ChunkTrackingView
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.bridge.world.CameraChunkTracker
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForResourceLocation
import kotlin.math.abs

internal const val CAMERA_CHUNK_RADIUS = 8
internal const val MAX_CHUNK_CAMERAS = 4

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
internal class CameraChunksPacket(
    val dimension: @Serializable(ForResourceLocation::class) ResourceLocation,
    val centers: List<Long>,
) : HollowPacket {
    override fun handle(player: Player) {
        val editor = player as? ServerPlayer ?: return
        val level = editor.serverLevel()
        if (dimension != level.dimension().location() || centers.size > MAX_CHUNK_CAMERAS) return
        val tracker = level.chunkSource.chunkMap as CameraChunkTracker
        if (!editor.mayStreamCameraChunks()) {
            tracker.`hollowengine$trackCameraChunks`(editor, emptyList())
            return
        }

        val radius = minOf(
            CAMERA_CHUNK_RADIUS,
            editor.requestedViewDistance().coerceIn(2, editor.server.playerList.viewDistance)
        )
        val limit = (Level.MAX_LEVEL_SIZE shr 4) - radius - 2
        val views = centers.distinct().map(::ChunkPos)
            .filter { abs(it.x.toLong()) <= limit && abs(it.z.toLong()) <= limit && level.worldBorder.isWithinBounds(it) }
            .map { ChunkTrackingView.Positioned(it, radius) }
        tracker.`hollowengine$trackCameraChunks`(editor, views)
    }
}

internal fun ServerPlayer.mayStreamCameraChunks(): Boolean =
    hasPermissions(PlayerPermissions.GAMEMASTER) || server.isSingleplayerOwner(gameProfile)
