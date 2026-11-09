package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "init(Lnet/minecraft/client/Minecraft;II)V", at = @At("HEAD"))
    private void hollowengine$beforeInit(Minecraft minecraft, int width, int height, CallbackInfo ci) {
        var screen = (Screen) (Object) this;
        var redirected = BootstrapRuntimeManager.bridge().onScreenOpen(screen);
        if (screen != redirected) {
            Minecraft.getInstance().setScreen(redirected);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void hollowengine$onRemove(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onScreenClose((Screen) (Object) this);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void hollowengine$onRenderPre(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (BootstrapRuntimeManager.bridge().onScreenRenderPre((Screen) (Object) this, guiGraphics, mouseX, mouseY, partialTick)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void hollowengine$onFrameStart(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onScreenFrameStart((Screen) (Object) this);
    }

    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void hollowengine$onBackgroundRendered(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onScreenBackgroundRendered((Screen) (Object) this, guiGraphics);
    }

    @Inject(
            method = "renderWithTooltip",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void hollowengine$onRenderPost(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onScreenRenderPost((Screen) (Object) this, guiGraphics, mouseX, mouseY, partialTick);
    }

    @Inject(method = "renderWithTooltip", at = @At("TAIL"))
    private void hollowengine$onFrameEnd(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onScreenFrameEnd((Screen) (Object) this, guiGraphics);
    }
}
