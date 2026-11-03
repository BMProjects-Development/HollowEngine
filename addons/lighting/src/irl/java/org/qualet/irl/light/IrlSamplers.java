package org.qualet.irl.light;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;

import org.qualet.irl.light.shadow.PointDepthAtlas;
import org.qualet.irl.light.shadow.PointShadowEvsm;
import org.qualet.irl.light.shadow.PointShadowPyramid;
import org.qualet.irl.light.shadow.SpotShadowEvsm;
import org.qualet.irl.light.shadow.SpotShadowPyramid;
import org.qualet.irl.light.shadow.SpotlightDepthAtlas;

/**
 * Central registry of the IRLite shadow textures that get bound into every Iris
 * program as dynamic samplers. Each entry pairs a sampler uniform name (the
 * rendering contract with the injected GLSL) with a live GL id supplier and the
 * real GL target of that texture.
 *
 * <p>Iris's TextureType enum has neither CUBE_MAP_ARRAY nor 2D_ARRAY, so every
 * sampler is registered as a plain 2D one; the array-backed textures carry their
 * true target here and are rebound to it on the same unit at bind time (see the
 * per-mod SamplerBinding mixin / {@code IrlSamplersBind.tryRebind}).</p>
 *
 * <p>Insertion order is preserved and is part of the contract: the names and GL
 * targets must stay byte-for-byte identical to what the shaders expect. The six
 * core-owned textures register themselves here; the per-mod cookie/gobo array is
 * registered from each mod's client init (its owner class lives per-mod).</p>
 *
 * <p>Registry only — no Iris or Minecraft types. The Iris-facing glue lives in
 * {@code org.qualet.irl.light.iris.IrlSamplersBind}.</p>
 */
public final class IrlSamplers
{
    /** Receives each registered sampler in insertion order. */
    @FunctionalInterface
    public interface SamplerConsumer
    {
        void accept(String name, IntSupplier glId, int glTarget);
    }

    private static final class Entry
    {
        final IntSupplier glId;
        final int glTarget;

        Entry(IntSupplier glId, int glTarget)
        {
            this.glId = glId;
            this.glTarget = glTarget;
        }
    }

    private static final Map<String, Entry> SAMPLERS = new LinkedHashMap<>();
    // Rebuilt only on registration. The per-texture Iris bind hook can scan
    // array-backed suppliers without a map iterator, lambda or temporary holder.
    private static volatile Entry[] arraySamplers = new Entry[0];

    static
    {
        // Core-owned shadow textures. Order + names + targets mirror the GLSL contract.
        register("irl_spotShadowAtlas", SpotlightDepthAtlas::getGlTextureId, GL11.GL_TEXTURE_2D);
        // Point depth is a flat 2D atlas like the spot one (atlas-merge series);
        // no cube-array rebind — plain 2D, sampled with the manual face-select UV.
        register("irl_pointShadowAtlas", PointDepthAtlas::getGlTextureId, GL11.GL_TEXTURE_2D);
        // F1a: min/max mip pyramid of the spot atlas (plain 2D, no target rebind needed).
        register("irl_spotShadowPyramid", SpotShadowPyramid::getGlTextureId, GL11.GL_TEXTURE_2D);
        // F1b: face-major point pyramid — a 2D array, rebound to GL_TEXTURE_2D_ARRAY.
        // The filters keep PER-TIER storage (layer = LOCAL block * 6 + face).
        register("irl_pointShadowPyramid", () -> PointShadowPyramid.getGlTextureId(0), GL30.GL_TEXTURE_2D_ARRAY);
        // F2a: EVSM prefilter of the spot atlas (plain 2D + mips, no target rebind needed).
        register("irl_spotEvsm", SpotShadowEvsm::getGlTextureId, GL11.GL_TEXTURE_2D);
        // F2b: point MSM sampled through a CUBE_MAP_ARRAY view (hardware-seamless face edges).
        register("irl_pointEvsm", () -> PointShadowEvsm.getGlTextureId(0), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        // I3 LOD tiers: the tier-1/2 point FILTER texture sets, appended AFTER
        // the six legacy entries — this order is the FROZEN contract for the
        // phase-I4 GLSL. Tier-0 names stay unsuffixed. Iris binds by NAME;
        // registration order only shifts texture-unit numbers, which no consumer
        // depends on (the per-mod cookie array still registers later, at mod
        // init). The tier-1/2 DEPTH entries (irl_pointShadowArray1/2) are GONE —
        // the atlas-merge series folded all point depth into the single
        // irl_pointShadowAtlas above.
        register("irl_pointShadowPyramid1", () -> PointShadowPyramid.getGlTextureId(1), GL30.GL_TEXTURE_2D_ARRAY);
        register("irl_pointEvsm1", () -> PointShadowEvsm.getGlTextureId(1), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        register("irl_pointShadowPyramid2", () -> PointShadowPyramid.getGlTextureId(2), GL30.GL_TEXTURE_2D_ARRAY);
        register("irl_pointEvsm2", () -> PointShadowEvsm.getGlTextureId(2), GL40.GL_TEXTURE_CUBE_MAP_ARRAY);
        // VL CP2: bundled 128x128 R8 spatial blue noise for the VL march-start
        // dither (VlGlobalsBuffer flags bit2). Plain 2D, no target rebind.
        register("irl_blueNoise", BlueNoiseTexture::getId, GL11.GL_TEXTURE_2D);
    }

    private IrlSamplers()
    {
    }

    /**
     * Registers a sampler under {@code name} bound to the texture id from
     * {@code glId}, sampled with the given GL target ({@link GL11#GL_TEXTURE_2D}
     * for plain 2D textures, {@link GL30#GL_TEXTURE_2D_ARRAY} or
     * {@link GL40#GL_TEXTURE_CUBE_MAP_ARRAY} for array-backed ones). Re-registering
     * an existing name replaces it in place, keeping its original ordering slot.
     */
    public static synchronized void register(String name, IntSupplier glId, int glTarget)
    {
        SAMPLERS.put(name, new Entry(glId, glTarget));
        arraySamplers = SAMPLERS.values().stream()
            .filter(entry -> entry.glTarget != GL11.GL_TEXTURE_2D)
            .toArray(Entry[]::new);
    }

    /** Visits every registered sampler in registration order. */
    public static synchronized void forEach(SamplerConsumer consumer)
    {
        for (Map.Entry<String, Entry> e : SAMPLERS.entrySet())
        {
            Entry entry = e.getValue();
            consumer.accept(e.getKey(), entry.glId, entry.glTarget);
        }
    }

    /**
     * GL target the named sampler is sampled with, or {@link GL11#GL_TEXTURE_2D}
     * if it isn't registered (the default 2D bind is then harmless).
     */
    public static synchronized int glTargetFor(String name)
    {
        Entry entry = SAMPLERS.get(name);
        return entry != null ? entry.glTarget : GL11.GL_TEXTURE_2D;
    }

    /** True target for a live array texture id, or 2D for zero/unknown ids.
     *  Suppliers stay live: atlas deletion/recreation can change ids between binds.
     *  Plain 2D suppliers are never evaluated by this lookup (some initialize GL
     *  textures lazily). Registration replacement preserves insertion precedence. */
    public static int glTargetForTexture(int textureId)
    {
        if (textureId != 0)
        {
            for (Entry entry : arraySamplers)
            {
                if (entry.glId.getAsInt() == textureId)
                {
                    return entry.glTarget;
                }
            }
        }
        return GL11.GL_TEXTURE_2D;
    }
}
