package ru.hollowhorizon.hollowengine.fabric.internal;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import ru.hollowhorizon.hollowengine.api.NetworkManager;

import java.util.function.BiConsumer;

public class FabricNetworkManager implements NetworkManager {
    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    @Override
    public <T extends CustomPacketPayload> void registerClient(CustomPacketPayload.@NotNull Type<T> type, @NotNull StreamCodec<RegistryFriendlyByteBuf, T> codec, @NotNull BiConsumer<T, Player> consumer) {
        PayloadTypeRegistry.playS2C().register(type, codec);
        if (FabricLoader.getInstance().getEnvironmentType().equals(EnvType.CLIENT)) {
            ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> consumer.accept(payload, context.player()));
        }
    }

    @Override
    public <T extends CustomPacketPayload> void registerServer(CustomPacketPayload.@NotNull Type<T> type, @NotNull StreamCodec<RegistryFriendlyByteBuf, T> codec, @NotNull BiConsumer<T, Player> consumer) {
        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> consumer.accept(payload, context.player()));
    }
}
