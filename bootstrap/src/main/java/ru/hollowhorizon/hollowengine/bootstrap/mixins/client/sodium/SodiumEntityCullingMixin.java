package ru.hollowhorizon.hollowengine.bootstrap.mixins.client.sodium;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bridge.client.WorldPreviewTargets;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public class SodiumEntityCullingMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void hollowengine$usePreviewEntityFrustum(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        // EntityRenderer still checks the current camera's frustum and the entity's render distance.
        if (WorldPreviewTargets.active() != null) cir.setReturnValue(true);
    }
}
