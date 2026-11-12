package ru.hollowhorizon.hollowengine.bridge.mixins.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererInvoker {
    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> hollowengine$getVisibleSections();

    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher hollowengine$getSectionRenderDispatcher();

    @Invoker("needsUpdate")
    void hollowengine$needsUpdate();
}
