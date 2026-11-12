package ru.hollowhorizon.hollowengine.bootstrap.mixins.client.iris;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bridge.client.IrisPreviewScope;

import java.util.Optional;

@Mixin(value = PipelineManager.class, remap = false)
public class PipelineManagerMixin {
    @Inject(method = {"preparePipeline", "getPipelineNullable"}, at = @At("HEAD"), cancellable = true)
    private void hollowengine$usePreviewPipeline(CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        var preview = IrisPreviewScope.current();
        if (preview != null) cir.setReturnValue(preview);
    }

    @Inject(method = "getPipeline", at = @At("HEAD"), cancellable = true)
    private void hollowengine$getPreviewPipeline(CallbackInfoReturnable<Optional<WorldRenderingPipeline>> cir) {
        var preview = IrisPreviewScope.current();
        if (preview != null) cir.setReturnValue(Optional.of(preview));
    }

    @Inject(method = "destroyPipeline", at = @At("TAIL"))
    private void hollowengine$invalidatePrograms(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onIrisPipelineDestroyed();
    }
}
