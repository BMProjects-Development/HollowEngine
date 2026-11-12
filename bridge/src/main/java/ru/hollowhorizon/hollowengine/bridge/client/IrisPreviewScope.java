package ru.hollowhorizon.hollowengine.bridge.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/** Iris's pipeline lookup resolves to the auxiliary view only on the thread drawing it. */
public final class IrisPreviewScope {
    private static final ThreadLocal<WorldRenderingPipeline> ACTIVE = new ThreadLocal<>();

    private IrisPreviewScope() {}

    public static WorldRenderingPipeline current() {
        return ACTIVE.get();
    }

    public static void bind(WorldRenderingPipeline pipeline) {
        ACTIVE.set(pipeline);
    }

    public static void clear() {
        ACTIVE.remove();
    }
}
