package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.Camera;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import java.util.Map;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
    @Shadow @Final private SoundEngineExecutor executor;

    @Inject(method = "updateSource", at = @At("RETURN"))
    private void hollowengine$frame(Camera camera, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onSoundFrame(instanceToChannel, executor);
    }
    @Inject(method = "tick", at = @At("TAIL"))
    private void hollowengine$tick(boolean paused, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onSoundEngineTick(paused);
    }

    @Inject(method = "pause", at = @At("HEAD"))
    private void hollowengine$pause(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onSoundEnginePaused(true);
    }

    @Inject(method = "resume", at = @At("HEAD"))
    private void hollowengine$resume(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onSoundEnginePaused(false);
    }

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void hollowengine$reset(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onSoundEngineReset();
    }

    @Inject(method = "stop(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/sounds/SoundSource;)V", at = @At("HEAD"))
    private void hollowengine$stop(ResourceLocation id, SoundSource category, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onSoundStop(id, category);
    }
}
