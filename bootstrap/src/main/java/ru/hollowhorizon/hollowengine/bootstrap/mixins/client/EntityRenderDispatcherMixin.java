package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

import java.util.Map;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Shadow public Map<EntityType<?>, EntityRenderer<?>> renderers;
    @Shadow private Map<String, EntityRenderer<? extends Player>> playerRenderers;

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void onResourceManagerReload(ResourceManager resourceManager, CallbackInfo ci, @Local EntityRendererProvider.Context context) {
        BootstrapRuntimeManager.bridge().onAddEntityRendererLayers(renderers, playerRenderers, context);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;FFFF)V")
    )
    private void hollowengine$renderHitbox(PoseStack poseStack, VertexConsumer lines, Entity entity, float partialTick,
                                           float red, float green, float blue, Operation<Void> original) {
        if (BootstrapRuntimeManager.bridge().renderColliderHitbox(entity, partialTick, poseStack, lines)) return;
        original.call(poseStack, lines, entity, partialTick, red, green, blue);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    )
    private <T extends Entity> void onRenderEntity(EntityRenderer<T> instance, T entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, Operation<Void> original) {
        if (BootstrapRuntimeManager.bridge().onRenderEntityPre(entity, entityYaw, partialTick, poseStack, buffer, packedLight)) return;
        original.call(instance, entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        BootstrapRuntimeManager.bridge().onRenderEntityPost(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }
}
