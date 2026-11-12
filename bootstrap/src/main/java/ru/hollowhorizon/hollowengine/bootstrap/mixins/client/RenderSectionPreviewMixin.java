package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bridge.client.PreviewRenderSection;
import ru.hollowhorizon.hollowengine.bridge.client.WorldPreviewTerrain;

import java.util.Collection;
import java.util.Set;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionPreviewMixin implements PreviewRenderSection {
    @Shadow
    @Final
    private Set<BlockEntity> globalBlockEntities;
    @Unique
    private @Nullable WorldPreviewTerrain hollowengine$terrain;

    @Shadow
    public abstract AABB getBoundingBox();

    @Override
    public void hollowengine$setTerrain(WorldPreviewTerrain terrain) {
        hollowengine$terrain = terrain;
    }

    @Override
    public void hollowengine$collectGlobalEntities(Set<BlockEntity> target) {
        synchronized (globalBlockEntities) {
            target.addAll(globalBlockEntities);
        }
    }

    @Inject(method = "getDistToPlayerSqr", at = @At("HEAD"), cancellable = true)
    private void hollowengine$distanceToPreview(CallbackInfoReturnable<Double> cir) {
        var terrain = hollowengine$terrain;
        if (terrain != null) cir.setReturnValue(terrain.cameraPosition().distanceToSqr(getBoundingBox().getCenter()));
    }

    @ModifyExpressionValue(method = "createVertexSorting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getCameraPosition()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 hollowengine$previewSortingCamera(Vec3 playerPosition) {
        var terrain = hollowengine$terrain;
        return terrain == null ? playerPosition : terrain.cameraPosition();
    }

    @WrapOperation(method = "updateGlobalBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;updateGlobalBlockEntities(Ljava/util/Collection;Ljava/util/Collection;)V"))
    private void hollowengine$keepPreviewBlockEntitiesLocal(LevelRenderer renderer, Collection<BlockEntity> removed, Collection<BlockEntity> added, Operation<Void> original) {
        if (hollowengine$terrain == null) original.call(renderer, removed, added);
    }

    @WrapOperation(method = "setCompiled", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;addRecentlyCompiledSection(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)V"))
    private void hollowengine$keepPreviewSectionsOutOfPlayerGraph(LevelRenderer renderer, SectionRenderDispatcher.RenderSection section, Operation<Void> original) {
        if (hollowengine$terrain == null) original.call(renderer, section);
    }
}
