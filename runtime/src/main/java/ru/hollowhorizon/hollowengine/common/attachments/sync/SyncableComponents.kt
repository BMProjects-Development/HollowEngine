package ru.hollowhorizon.hollowengine.common.attachments.sync

import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerEvent

@SubscribeEvent
fun onClone(event: PlayerEvent.Clone) {
    val old = event.oldPlayer
    val new = event.player

    AttachmentRegistry.cloneOwnedState(old, new, dropLooseOnDeath = event.wasDeath)

    new.server?.coroutineScope?.launch {
        yield()
        (new as? ServerPlayer)?.let(EntityStateSync::sendSelfBaseline)
    }
}

@SubscribeEvent
fun onJoin(event: PlayerEvent.Join) {
    val player = event.player as? ServerPlayer ?: return
    EntityStateSync.sendSelfBaseline(player)
}

@SubscribeEvent
fun onRespawn(event: PlayerEvent.Respawn) {
    val player = event.player as? ServerPlayer ?: return
    EntityStateSync.sendSelfBaseline(player)
}

@SubscribeEvent
fun onChangeDimension(event: PlayerEvent.ChangeDimension) {
    val player = event.player as? ServerPlayer ?: return
    player.server?.coroutineScope?.launch {
        yield()
        EntityStateSync.sendSelfBaseline(player)
    }
}
