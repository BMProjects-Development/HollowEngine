package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimeBridge;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;
    @Shadow protected abstract void detectRateSpam();

    @WrapOperation(method = "handleInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private AABB hollowengine$interactReach(Entity target, Operation<AABB> original) {
        return BootstrapRuntimeManager.bridge().colliderReachBounds(player, target, original.call(target));
    }

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void hollowengine$onHandleChat(PlayerChatMessage message, CallbackInfo ci) {
        Component content = message.decoratedContent();
        RuntimeBridge.ChatResult result = BootstrapRuntimeManager.bridge().onServerChat(player, content);
        if (result.message() != content) {
            player.server.getPlayerList().getPlayers().forEach(target -> target.sendSystemMessage(result.message()));
            detectRateSpam();
            ci.cancel();
            return;
        }
        if (result.cancelled()) ci.cancel();
    }
}
