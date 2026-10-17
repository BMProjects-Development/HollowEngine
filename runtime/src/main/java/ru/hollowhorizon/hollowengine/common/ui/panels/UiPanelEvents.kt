package ru.hollowhorizon.hollowengine.common.ui.panels

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT

/** What a player did on [panel], hung on [entity]: the payload the content of the panel sent. On the server. */
class UiPanelEvent(
    val entity: Entity,
    val player: ServerPlayer,
    val panel: String,
    val payload: CompoundTag,
) : Event {
    companion object : EventHandler<UiPanelEvent>()
}

/** A payload a player's panel sent; the server checks the player is near enough to have used it. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class UiPanelEventPacket(
    val entityId: Int,
    val panel: String,
    val payload: @Serializable(ForCompoundNBT::class) CompoundTag = CompoundTag(),
) : HollowPacket {
    override fun handle(player: Player) {
        val sender = player as? ServerPlayer ?: return
        val entity = sender.level().getEntity(entityId) ?: return
        if (entity.distanceToSqr(sender) > MAX_DISTANCE * MAX_DISTANCE) return
        UiPanelEvent.post(UiPanelEvent(entity, sender, panel, payload))
    }

    companion object {
        /** How far from what carries a panel a player may be and still use it, in blocks: a generous reach. */
        const val MAX_DISTANCE = 32.0
    }
}
