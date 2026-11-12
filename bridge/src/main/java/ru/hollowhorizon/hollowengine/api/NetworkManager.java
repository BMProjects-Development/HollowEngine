package ru.hollowhorizon.hollowengine.api;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

public interface NetworkManager {
    boolean canSendToServer(CustomPacketPayload.Type<?> type);

    <T extends CustomPacketPayload> void registerClient(@NotNull CustomPacketPayload.Type<T> type, @NotNull StreamCodec<RegistryFriendlyByteBuf, T> codec, @NotNull BiConsumer<T, Player> consumer);

    <T extends CustomPacketPayload> void registerServer(@NotNull CustomPacketPayload.Type<T> type, @NotNull StreamCodec<RegistryFriendlyByteBuf, T> codec, @NotNull BiConsumer<T, Player> consumer);
}
