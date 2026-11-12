package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bridge.client.CameraChunkCache;

@Mixin(ClientPacketListener.class)
public abstract class ClientRespawnMixin {
    @Shadow private ClientLevel level;

    @Shadow public abstract void handleForgetLevelChunk(ClientboundForgetLevelChunkPacket packet);

    @Inject(method = "handleRespawn", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;player:Lnet/minecraft/client/player/LocalPlayer;",
            opcode = Opcodes.GETFIELD, ordinal = 0))
    private void hollowengine$clearPreviousTracking(ClientboundRespawnPacket packet, CallbackInfo ci) {
        if (!level.dimension().equals(packet.commonPlayerSpawnInfo().dimension())) return;
        var cache = (CameraChunkCache) level.getChunkSource();
        for (var pos : cache.hollowengine$tiledChunks()) {
            handleForgetLevelChunk(new ClientboundForgetLevelChunkPacket(pos));
        }
    }
}
