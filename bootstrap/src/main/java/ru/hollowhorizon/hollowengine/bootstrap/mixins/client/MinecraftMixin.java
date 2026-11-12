package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bridge.client.WorldPreviewTargets;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Shadow @Nullable public ClientLevel level;

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void hollowengine$previewTarget(CallbackInfoReturnable<RenderTarget> cir) {
        var preview = WorldPreviewTargets.active();
        if (preview != null) cir.setReturnValue(preview.target());
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(GameConfig gameConfig, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onClientCreated((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void onRunTickHead(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onClientTick((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void onRenderPre(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onClientRenderTickPre((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
    private void onRenderPost(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onClientRenderTickPost((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V",
            shift = At.Shift.AFTER
    ))
    private void beforeBlit(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onBeforeBlitScreen((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V", shift = At.Shift.AFTER))
    private void afterBlit(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onBlitScreen((Minecraft) (Object) this);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void onStopHead(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onClientStopping((Minecraft) (Object) this);
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onSetClientLevel(ClientLevel newLevel, ReceivingLevelScreen.Reason reason, CallbackInfo ci) {
        if (level != newLevel) hollowengine$releaseLevel();
    }

    @Inject(method = "clearClientLevel", at = @At("HEAD"))
    private void onClearClientLevel(Screen screen, CallbackInfo ci) {
        hollowengine$releaseLevel();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void onDisconnect(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        hollowengine$releaseLevel();
    }

    private void hollowengine$releaseLevel() {
        if (level != null) BootstrapRuntimeManager.bridge().onLevelClosed(level);
    }
}
