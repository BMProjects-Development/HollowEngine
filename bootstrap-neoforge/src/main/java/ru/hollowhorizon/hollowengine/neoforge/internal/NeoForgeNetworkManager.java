package ru.hollowhorizon.hollowengine.neoforge.internal;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.NotNull;
import ru.hollowhorizon.hollowengine.api.NetworkManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class NeoForgeNetworkManager implements NetworkManager {
    private static final Map<CustomPacketPayload.Type<?>, PacketRegistration<?>> PACKETS = new LinkedHashMap<>();

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        var listener = Minecraft.getInstance().getConnection();
        return listener != null && NetworkRegistry.hasChannel(listener.getConnection(), ConnectionProtocol.PLAY, type.id());
    }

    @SuppressWarnings("unchecked")
    public static void onRegisterPackets(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1.0.0");
        PACKETS.values().forEach(registration -> registration.register(registrar));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> void registerClient(
            CustomPacketPayload.@NotNull Type<T> type,
            @NotNull StreamCodec<RegistryFriendlyByteBuf, T> codec,
            @NotNull BiConsumer<T, Player> consumer
    ) {
        var reg = (PacketRegistration<T>) PACKETS.computeIfAbsent(type, t -> new PacketRegistration<>(type, codec));
        reg.clientHandler = consumer;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends CustomPacketPayload> void registerServer(
            CustomPacketPayload.@NotNull Type<T> type,
            @NotNull StreamCodec<RegistryFriendlyByteBuf, T> codec,
            @NotNull BiConsumer<T, Player> consumer
    ) {
        var reg = (PacketRegistration<T>) PACKETS.computeIfAbsent(type, t -> new PacketRegistration<>(type, codec));
        reg.serverHandler = consumer;
    }

    private static class PacketRegistration<T extends CustomPacketPayload> {
        private final CustomPacketPayload.Type<T> type;
        private final StreamCodec<RegistryFriendlyByteBuf, T> codec;
        private BiConsumer<T, Player> clientHandler;
        private BiConsumer<T, Player> serverHandler;

        public PacketRegistration(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
            this.type = type;
            this.codec = codec;
        }

        public void register(PayloadRegistrar registrar) {
            if (clientHandler != null && serverHandler != null) {
                registrar.playBidirectional(type, codec, (packet, context) -> {
                    if (context.flow().isClientbound()) {
                        clientHandler.accept(packet, context.player());
                    } else {
                        serverHandler.accept(packet, context.player());
                    }
                });
            }
            else if (clientHandler != null) {
                registrar.playToClient(type, codec, (packet, context) -> clientHandler.accept(packet, context.player()));
            }
            else if (serverHandler != null) {
                registrar.playToServer(type, codec, (packet, context) -> serverHandler.accept(packet, context.player()));
            }
        }
    }
}
