package ru.hollowhorizon.hollowengine.bootstrap.mixins.client.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class RenderSectionManagerMixin {
    @Inject(method = "isOutOfGraph", at = @At("HEAD"), cancellable = true, require = 0)
    private void hollowengine$traverseLoadedSectionsForPreview(CallbackInfoReturnable<Boolean> cir) {
        if (BootstrapRuntimeManager.bridge().isRenderingWorldPreview()) cir.setReturnValue(true);
    }
}
