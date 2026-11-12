package ru.hollowhorizon.hollowengine.bridge.client;

import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;

public final class WorldPreviewTargets implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<WorldPreviewTargets> ACTIVE = new ThreadLocal<>();

    private final TextureTarget target;
    private final Chain outlines = new Chain("shaders/post/entity_outline.json");
    private final Chain transparency = new Chain("shaders/post/transparency.json");
    private @Nullable WorldPreviewTerrain terrain;
    private Vec3 sortPosition = Vec3.ZERO;

    public WorldPreviewTargets(int width, int height) {
        target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
    }

    public static @Nullable WorldPreviewTargets active() {
        return ACTIVE.get();
    }

    public void bind() {
        ACTIVE.set(this);
    }

    public void unbind() {
        ACTIVE.remove();
    }

    public TextureTarget target() {
        return target;
    }

    public @Nullable WorldPreviewTerrain terrain() {
        return terrain;
    }

    public void updateTerrain(ClientLevel level, SectionRenderDispatcher dispatcher, int radius) {
        if (terrain != null && terrain.uses(level, dispatcher, radius)) return;
        if (terrain != null) terrain.close();
        terrain = new WorldPreviewTerrain(level, dispatcher, radius);
    }

    public Vec3 sortPosition() {
        return sortPosition;
    }

    public void setSortPosition(double x, double y, double z) {
        sortPosition = new Vec3(x, y, z);
    }

    public void resize(int width, int height) {
        if (target.width == width && target.height == height) return;
        target.resize(width, height, Minecraft.ON_OSX);
        outlines.resize();
        transparency.resize();
    }

    public @Nullable PostChain outlines(@Nullable PostChain source) {
        return outlines.get(source);
    }

    public @Nullable PostChain transparency(@Nullable PostChain source) {
        return transparency.get(source);
    }

    @Override
    public void close() {
        if (terrain != null) terrain.close();
        outlines.close();
        transparency.close();
        target.destroyBuffers();
    }

    private final class Chain {
        private final ResourceLocation resource;
        private @Nullable PostChain source;
        private @Nullable PostChain chain;

        Chain(String resource) {
            this.resource = ResourceLocation.withDefaultNamespace(resource);
        }

        @Nullable
        PostChain get(@Nullable PostChain current) {
            if (source == current) return chain;
            close();
            source = current;
            if (current == null) return chain;

            var minecraft = Minecraft.getInstance();
            try {
                chain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(), target, resource);
                resize();
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("Could not load camera preview post chain {}", resource, e);
            }
            return chain;
        }

        void resize() {
            if (chain != null) chain.resize(target.width, target.height);
        }

        void close() {
            if (chain != null) chain.close();
            chain = null;
            source = null;
        }
    }
}
