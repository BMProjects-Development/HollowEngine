package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(PlayerList.class)
public class PlayerListMixin {
    @Inject(method = "respawn", at = @At("RETURN"))
    private void hollowengine$firePlayerRespawnEvent(ServerPlayer original, boolean returnFromEnd, Entity.RemovalReason removalReason, CallbackInfoReturnable<ServerPlayer> cir) {
        BootstrapRuntimeManager.bridge().onPlayerRespawn(original, returnFromEnd);
    }

    @Inject(method = "sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("RETURN"))
    private void hollowengine$firePermissionsChangedEvent(ServerPlayer player, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onPlayerPermissionsChanged(player);
    }
}
