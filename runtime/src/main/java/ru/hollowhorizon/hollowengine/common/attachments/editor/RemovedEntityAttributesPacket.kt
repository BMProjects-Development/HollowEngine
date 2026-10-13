package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.utils.mutableAttributes

/** Vanilla syncs attribute values and additions, but has no packet for removing an instance. */
@Serializable
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
class RemovedEntityAttributesPacket(val entityId: Int, val attributes: Set<String>) : HollowPacket {
    override fun handle(player: Player) {
        val entity = player.level().getEntity(entityId) as? LivingEntity ?: return
        attributes.forEach { id ->
            val location = ResourceLocation.tryParse(id) ?: return@forEach
            val holder = BuiltInRegistries.ATTRIBUTE.getHolder(location).orElse(null) ?: return@forEach
            entity.attributes.mutableAttributes.remove(holder)
        }
    }
}
