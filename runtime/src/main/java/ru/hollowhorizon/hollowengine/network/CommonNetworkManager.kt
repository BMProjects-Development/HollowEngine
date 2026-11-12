package ru.hollowhorizon.hollowengine.network

import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.NetworkManager
import ru.hollowhorizon.hollowengine.common.coroutines.coroutineScope
import ru.hollowhorizon.hollowengine.common.network.HollowAddonPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.ByteBufFormat
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.deserializeNoInline
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.serializeNoInline
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.function.BiConsumer

object CommonNetworkManager : NetworkManager {
    lateinit var manager: NetworkManager

    override fun canSendToServer(type: CustomPacketPayload.Type<*>): Boolean = manager.canSendToServer(type)

    override fun <T : CustomPacketPayload> registerClient(
        type: CustomPacketPayload.Type<T>,
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        consumer: BiConsumer<T, Player>,
    ) {
        manager.registerClient(type, codec, consumer)
    }

    override fun <T : CustomPacketPayload> registerServer(
        type: CustomPacketPayload.Type<T>,
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        consumer: BiConsumer<T, Player>,
    ) {
        manager.registerServer(type, codec, consumer)
    }

    internal fun init(manager: NetworkManager) {
        this.manager = manager
        registerClient(AddonPacketPayload.TYPE, AddonPacketPayload.CODEC, HollowAddonPacketRegistry::dispatchClient)
        registerServer(AddonPacketPayload.TYPE, AddonPacketPayload.CODEC, HollowAddonPacketRegistry::dispatchServer)
    }

    fun <T : HollowPacket> registerPacket(type: Class<T>) {
        val annotation = type.getAnnotation(HollowPacketHandler::class.java)
        val location = CustomPacketPayload.Type<T>(HollowPacket.nameFor(type).rl)
        val isClient = isPhysicalClient
        val codec: StreamCodec<RegistryFriendlyByteBuf, T> = CustomPacketPayload.codec(
            { packet, buffer ->
                ByteBufFormat.serializeNoInline(packet, type, buffer)
            },
            { buffer ->
                ByteBufFormat.deserializeNoInline(buffer, type)
            }
        )

        when (annotation.toTarget) {
            HollowPacketHandler.Direction.TO_CLIENT -> {
                registerClient(location, codec, HollowPacket::handle)
            }

            HollowPacketHandler.Direction.TO_SERVER -> {
                registerServer(location, codec, HollowPacket::handle)
            }

            HollowPacketHandler.Direction.ANY -> {
                registerClient(location, codec, HollowPacket::handle)
                registerServer(location, codec, HollowPacket::handle)
            }
        }
    }

    fun sendToServer(packet: HollowPacket) {
        val connection = Minecraft.getInstance().connection
        if (connection != null) connection.send(packet.toVanilla(false))
    }

    fun sendToClient(player: ServerPlayer, packet: HollowPacket) {
        if (player.hasDisconnected()) return

        player.server.coroutineScope.launch {
            while (player.connection == null && !player.hasDisconnected()) {
                yield() // Если пакет отправляется до инициализации, то ждём каждый тик
            }
            if (!player.hasDisconnected()) {
                player.connection.send(packet.toVanilla(true))
            } else {
                HollowEngine.LOGGER.warn("Player ${player.name.string} removed, but packet ${HollowPacket.nameFor(packet::class)} still trying to send")
            }
        }
    }

    fun sendAddonToServer(packet: HollowAddonPacket) {
        val connection = Minecraft.getInstance().connection
        if (connection != null) connection.send(HollowAddonPacketRegistry.encodeForServer(packet).toVanilla(false))
    }

    fun sendAddonToClient(player: ServerPlayer, packet: HollowAddonPacket) {
        if (player.hasDisconnected()) return
        val vanillaPacket = HollowAddonPacketRegistry.encodeForClient(packet).toVanilla(true)

        player.server.coroutineScope.launch {
            while (player.connection == null && !player.hasDisconnected()) {
                yield()
            }
            if (!player.hasDisconnected()) {
                player.connection.send(vanillaPacket)
            } else {
                HollowEngine.LOGGER.warn("Player ${player.name.string} removed, but addon packet still trying to send")
            }
        }
    }

    private fun HollowPacket.toVanilla(toClient: Boolean): Packet<*> =
        if (toClient) ClientboundCustomPayloadPacket(this)
        else ServerboundCustomPayloadPacket(this)

    private fun AddonPacketPayload.toVanilla(toClient: Boolean): Packet<*> =
        if (toClient) ClientboundCustomPayloadPacket(this)
        else ServerboundCustomPayloadPacket(this)

    fun sendTrackingEntity(entity: Entity, packet: HollowPacket) {
        val chunkCache = entity.level().chunkSource
        if (chunkCache is ServerChunkCache) {
            chunkCache.broadcastAndSend(entity, packet.toVanilla(true))
        } else {
            throw IllegalStateException("Cannot send clientbound payloads on the client")
        }
    }

    fun sendAddonTrackingEntity(entity: Entity, packet: HollowAddonPacket) {
        val chunkCache = entity.level().chunkSource
        if (chunkCache is ServerChunkCache) {
            chunkCache.broadcastAndSend(entity, HollowAddonPacketRegistry.encodeForClient(packet).toVanilla(true))
        } else {
            throw IllegalStateException("Cannot send clientbound payloads on the client")
        }
    }
}
