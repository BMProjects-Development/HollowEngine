@file:UseSerializers(ForResourceLocation::class)
package ru.hollowhorizon.hollowengine.common.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForResourceLocation

@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class SSyncTagDataPacket(val data: Map<ResourceLocation, Set<ResourceLocation>>, val registryType: String) : HollowPacket {
    override fun handle(player: Player) {
        // Update client-side representation if needed

    }
}

