package ru.hollowhorizon.hollowengine.fabric.mixins.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bridge.client.CameraTransforms;
import ru.hollowhorizon.hollowengine.bridge.mixins.client.CameraInvoker;

@Mixin(Camera.class)
public class CameraMixin {
    @Inject(method = "setup", at = @At("RETURN"))
    private void onSetup(BlockGetter blockGetter, Entity entity, boolean detached, boolean inverseView, float partialTick, CallbackInfo ci) {
        var camera = (Camera) (Object) this;
        var bridge = BootstrapRuntimeManager.bridge();
        var override = bridge.getCameraOverride(partialTick);

        if (override.active()) {
            ((CameraInvoker) camera).hollowcore$setPosition(override.x(), override.y(), override.z());
        }

        var renderer = Minecraft.getInstance().gameRenderer;
        var setup = bridge.onCameraSetup(renderer, camera, override.active() ? override.yaw() : camera.getYRot(), override.active() ? override.pitch() : camera.getXRot(), override.active() ? override.roll() : 0.0F, partialTick);
        CameraTransforms.setRotation(camera, setup.yaw(), setup.pitch(), setup.roll());
    }
}
