package ru.hollowhorizon.hollowengine.bridge.client;

import net.minecraft.client.Camera;
import ru.hollowhorizon.hollowengine.bridge.mixins.client.CameraInvoker;

public final class CameraTransforms {
    private CameraTransforms() {
    }

    public static void setRotation(Camera camera, float yaw, float pitch, float roll) {
        ((CameraInvoker) camera).hollowcore$rotate(yaw, pitch);
        if (roll == 0.0F) return;
        var rotation = camera.rotation().rotateZ((float) Math.toRadians(roll));
        camera.getLookVector().set(0.0F, 0.0F, -1.0F).rotate(rotation);
        camera.getUpVector().set(0.0F, 1.0F, 0.0F).rotate(rotation);
        camera.getLeftVector().set(-1.0F, 0.0F, 0.0F).rotate(rotation);
    }
}
