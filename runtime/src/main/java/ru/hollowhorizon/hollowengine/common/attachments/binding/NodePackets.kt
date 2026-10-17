package ru.hollowhorizon.hollowengine.common.attachments.binding

import kotlinx.serialization.Serializable
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForUuid
import java.util.*

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
data class EntitySnapshotRemovePacket(
    val entityId: Int,
) : HollowPacket {
    override fun handle(player: Player) {
        val entity = player.level().getEntity(entityId) ?: return
        AttachmentRegistry.componentsById(entity).clear()
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
data class EntitySnapshotUpdatePacket(
    val entityId: Int,
    val snapshot: EntitySnapshot,
) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.hasPermissions(PlayerPermissions.GAMEMASTER)) return
        val entity = player.level().getEntity(entityId) ?: return
        AttachmentRegistry.updateEntitySnapshot(entity, snapshot)
    }
}

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
data class NodeTransformUpdatePacket(
    val snapshotId: @Serializable(ForUuid::class) UUID,
    val nodeId: @Serializable(ForUuid::class) UUID? = null,
    val transform: ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent,
) : HollowPacket {
    override fun handle(player: Player) {
        if (!player.hasPermissions(PlayerPermissions.GAMEMASTER)) return
        val server = player.server ?: return
        for (level in server.allLevels) {
            if (NodeRuntimeState.service(level)
                    .updateTransform(snapshotId, transform, nodeId = nodeId)
            ) {
                return
            }
        }
        HollowEngine.LOGGER.warn(
            "NodeTransformUpdatePacket ignored: snapshot {} not found in any server level",
            snapshotId
        )
    }
}

