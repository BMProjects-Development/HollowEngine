package ru.hollowhorizon.hollowengine.bridge.mixins.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraInvoker {
    @Accessor("detached")
    void hollowengine$setDetached(boolean detached);

    @Invoker("setRotation")
    void hollowcore$rotate(float yaw, float pitch);

    @Invoker("setPosition")
    void hollowcore$setPosition(double x, double y, double z);

}
