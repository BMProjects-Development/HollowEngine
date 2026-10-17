package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @WrapOperation(
        method = "shouldRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z",
            ordinal = 0
        )
    )
    private <T extends Entity> boolean extendCullingBounds(
        Frustum frustum,
        AABB vanillaBounds,
        Operation<Boolean> original,
        @Local(argsOnly = true) T entity
    ) {
        if (BootstrapRuntimeManager.bridge().isEntityFrustumCullingDisabled(entity)) return true;

        var bounds = BootstrapRuntimeManager.bridge().extendEntityCullingBounds(entity, vanillaBounds);
        return original.call(frustum, bounds);
    }

    @WrapOperation(method = "renderNameTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityAttachments;getNullable(Lnet/minecraft/world/entity/EntityAttachment;IF)Lnet/minecraft/world/phys/Vec3;"))
    private <T extends Entity> Vec3 nameplateOverColliders(EntityAttachments attachments, EntityAttachment attachment, int index, float yRot,
                                                           Operation<Vec3> original, @Local(argsOnly = true) T entity) {
        return BootstrapRuntimeManager.bridge().entityNameplateAttachment(entity, original.call(attachments, attachment, index, yRot));
    }

    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void onShouldRenderName(T entity, Component displayName, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick, CallbackInfo ci) {
        var showNameplate = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick)) != null;
        if (!BootstrapRuntimeManager.bridge().onRenderEntityNameplate(entity, showNameplate)) {
            ci.cancel();
        }
    }
}
