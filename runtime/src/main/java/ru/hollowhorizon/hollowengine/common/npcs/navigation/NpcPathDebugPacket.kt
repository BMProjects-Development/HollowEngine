package ru.hollowhorizon.hollowengine.common.npcs.navigation

import kotlinx.serialization.Serializable
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.render.NpcPathDebugRenderer
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler

@Serializable
data class NpcPathDebugNode(
    val x: Int,
    val y: Int,
    val z: Int,
    val type: String,
    val costMalus: Float,
    val jump: Boolean = false,
)

@Serializable
data class NpcPathDebugPoint(
    val x: Double,
    val y: Double,
    val z: Double,
)

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
data class NpcPathDebugPacket(
    val entityId: Int,
    val nodes: List<NpcPathDebugNode>,
    val nextNodeIndex: Int,
    val targetX: Int,
    val targetY: Int,
    val targetZ: Int,
    val reached: Boolean,
    val steeringTarget: NpcPathDebugPoint,
) : HollowPacket {
    override fun handle(player: Player) {
        NpcPathDebugRenderer.update(this)
    }
}
