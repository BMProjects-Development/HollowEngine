package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(MultiPlayerGameMode.class)
public class MultiplayerGameModeMixin {
    @Inject(method = "performUseItemOn", at = @At("HEAD"), cancellable = true)
    private void hollowengine$onRightClickBlock(LocalPlayer player, InteractionHand hand, BlockHitResult result, CallbackInfoReturnable<InteractionResult> cir) {
        if (BootstrapRuntimeManager.bridge().onClientUseItemOn(player, hand, result)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "attack", at = @At("HEAD"))
    private void hollowengine$claimAttack(Player player, Entity target, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onClientTargetEntity(target, null);
    }

    @Inject(method = "interactAt", at = @At("HEAD"))
    private void hollowengine$claimInteraction(Player player, Entity target, EntityHitResult result, InteractionHand hand,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        BootstrapRuntimeManager.bridge().onClientTargetEntity(target, result);
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void hollowengine$onInteractEntity(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (BootstrapRuntimeManager.bridge().onClientInteractEntity(player, hand, target)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"), cancellable = true)
    private void hollowengine$onInteractItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (BootstrapRuntimeManager.bridge().onClientUseItem(player, hand, player.getItemInHand(hand))) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
