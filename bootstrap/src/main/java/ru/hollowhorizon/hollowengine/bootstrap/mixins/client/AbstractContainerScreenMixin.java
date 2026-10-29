package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

/** A container draws the tooltip of its slot inside {@code render}, so what goes under tooltips is drawn before it. */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {
    @Inject(method = "renderTooltip", at = @At("HEAD"))
    private void hollowengine$beforeTooltip(GuiGraphics guiGraphics, int x, int y, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onContainerTooltipPre((Screen) (Object) this, guiGraphics);
    }
}
