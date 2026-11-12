package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bridge.client.WorldPreviewTargets;

import java.util.Set;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow
    @Nullable
    private ClientLevel level;
    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow
    @Nullable
    private PostChain entityEffect;
    @Shadow
    @Nullable
    private PostChain transparencyChain;
    @Shadow
    @Nullable
    private RenderTarget entityTarget;
    @Shadow
    @Nullable
    private RenderTarget translucentTarget;
    @Shadow
    @Nullable
    private RenderTarget itemEntityTarget;
    @Shadow
    @Nullable
    private RenderTarget particlesTarget;
    @Shadow
    @Nullable
    private RenderTarget weatherTarget;
    @Shadow
    @Nullable
    private RenderTarget cloudsTarget;
    @Shadow
    private double xTransparentOld;
    @Shadow
    private double yTransparentOld;
    @Shadow
    private double zTransparentOld;

    @WrapMethod(method = "renderLevel")
    private void hollowengine$usePreviewPostChains(DeltaTracker delta, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f view, Matrix4f projection, Operation<Void> original) {
        var preview = WorldPreviewTargets.active();
        if (preview == null) {
            original.call(delta, renderBlockOutline, camera, gameRenderer, lightTexture, view, projection);
            return;
        }

        var playerOutlines = entityEffect;
        var playerTransparency = transparencyChain;
        var playerSortX = xTransparentOld;
        var playerSortY = yTransparentOld;
        var playerSortZ = zTransparentOld;
        var previewOutlines = preview.outlines(playerOutlines);
        var previewTransparency = preview.transparency(playerTransparency);
        try {
            hollowengine$setPostChains(previewOutlines, previewTransparency);
            var sortPosition = preview.sortPosition();
            xTransparentOld = sortPosition.x;
            yTransparentOld = sortPosition.y;
            zTransparentOld = sortPosition.z;
            preview.target().bindWrite(true);
            original.call(delta, renderBlockOutline, camera, gameRenderer, lightTexture, view, projection);
        } finally {
            preview.setSortPosition(xTransparentOld, yTransparentOld, zTransparentOld);
            xTransparentOld = playerSortX;
            yTransparentOld = playerSortY;
            zTransparentOld = playerSortZ;
            hollowengine$setPostChains(playerOutlines, playerTransparency);
        }
    }

    @Unique
    private void hollowengine$setPostChains(@Nullable PostChain outlines, @Nullable PostChain transparency) {
        entityEffect = outlines;
        entityTarget = outlines == null ? null : outlines.getTempTarget("final");
        transparencyChain = transparency;
        translucentTarget = transparency == null ? null : transparency.getTempTarget("translucent");
        itemEntityTarget = transparency == null ? null : transparency.getTempTarget("itemEntity");
        particlesTarget = transparency == null ? null : transparency.getTempTarget("particles");
        weatherTarget = transparency == null ? null : transparency.getTempTarget("weather");
        cloudsTarget = transparency == null ? null : transparency.getTempTarget("clouds");
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;capturedFrustum:Lnet/minecraft/client/renderer/culling/Frustum;", opcode = Opcodes.GETFIELD))
    private Frustum hollowengine$ignorePlayerFrustumCapture(Frustum captured) {
        return BootstrapRuntimeManager.bridge().isRenderingWorldPreview() ? null : captured;
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;captureFrustum:Z", opcode = Opcodes.GETFIELD))
    private boolean hollowengine$capturePlayerFrustumOnly(boolean capture) {
        return capture && !BootstrapRuntimeManager.bridge().isRenderingWorldPreview();
    }

    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void hollowengine$preparePreviewSections(Camera camera, Frustum frustum, boolean capturedFrustum, boolean spectator, CallbackInfo ci) {
        var preview = WorldPreviewTargets.active();
        if (preview == null || preview.terrain() == null) return;
        preview.terrain().prepare(camera.getPosition(), frustum, visibleSections);
        ci.cancel();
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;globalBlockEntities:Ljava/util/Set;", opcode = Opcodes.GETFIELD))
    private Set<BlockEntity> hollowengine$previewGlobalBlockEntities(Set<BlockEntity> playerEntities) {
        var preview = WorldPreviewTargets.active();
        return preview == null || preview.terrain() == null ? playerEntities : preview.terrain().globalBlockEntities();
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void hollowengine$dirtyPreviewSections(int x, int y, int z, boolean immediate, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().markWorldPreviewSectionDirty(x, y, z, immediate);
    }

    @ModifyConstant(method = "renderSky", constant = @Constant(floatValue = 30.0F))
    private float hollowengine$changeSunSize(float original) {
        return level == null ? original : BootstrapRuntimeManager.bridge().getSkySunSize(level, original);
    }

    @ModifyConstant(method = "renderSky", constant = @Constant(floatValue = 20.0F))
    private float hollowengine$changeMoonSize(float original) {
        return level == null ? original : BootstrapRuntimeManager.bridge().getSkyMoonSize(level, original);
    }
}
