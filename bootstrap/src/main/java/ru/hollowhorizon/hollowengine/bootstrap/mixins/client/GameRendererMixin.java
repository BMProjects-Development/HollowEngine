package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.joml.Matrix4f;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "getMainCamera", at = @At("HEAD"), cancellable = true)
    private void hollowengine$worldPreviewCamera(CallbackInfoReturnable<Camera> cir) {
        var preview = BootstrapRuntimeManager.bridge().getWorldPreviewCamera();
        if (preview != null) cir.setReturnValue(preview);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void onBeforeLevelFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onBeforeLevelFrame(Minecraft.getInstance(), deltaTracker);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", shift = At.Shift.AFTER))
    private void hollowengine$drawCameraGuides(DeltaTracker deltaTracker, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().drawWorldPreviewGuides();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (BootstrapRuntimeManager.bridge().onRenderItemInHand(camera, partialTick, projectionMatrix)) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tryTakeScreenshotIfNeeded()V"))
    private void onLevelFrameRendered(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onLevelFrameRendered(Minecraft.getInstance());
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void onGetFov(Camera camera, float partialTick, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        double fov = BootstrapRuntimeManager.bridge().onCameraFov(
                (GameRenderer) (Object) this,
                camera,
                cir.getReturnValue(),
                partialTick,
                changingFov
        );
        cir.setReturnValue(fov);
    }
}
