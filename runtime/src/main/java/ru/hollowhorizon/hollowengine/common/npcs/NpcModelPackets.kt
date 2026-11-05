package ru.hollowhorizon.hollowengine.common.npcs

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.editor.WorldNpcModels
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.npcs.registry.npcs
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions

/** Asks which model the living NPC with the author's [id] shows, for an editor that checks what is played on it. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class RequestNpcModelPacket(val id: String) : HollowPacket {
    override fun handle(player: Player) {
        val server = (player as? ServerPlayer)?.server ?: return
        if (!player.hasPermissions(PlayerPermissions.GAMEMASTER)) return
        val npc = server.npcs.alive(id)
        NpcModelPacket(id, npc != null, npc?.let(NpcAnimationRuntime::modelOf)).send(player)
    }
}

/**
 * Which model the NPC with [id] shows.
 *
 * @param alive whether the world has a living NPC with [id] at all.
 * @param model its model, or null when it has none.
 */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class NpcModelPacket(val id: String, val alive: Boolean, val model: String?) : HollowPacket {
    override fun handle(player: Player) = WorldNpcModels.answer(id, alive, model)
}
