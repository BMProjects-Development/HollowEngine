package org.qualet.irl.light.shadow;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * The shadow-relevant slice of a mod's configuration, pulled by {@link ShadowBaker}
 * every bake. A strict SUBSET of each mod's config holder (redactor {@code LightConfig}
 * / addon {@code IrliteConfig}) — only the five getters the orchestration reads. Each
 * mod hands ONE instance to {@link ShadowEngine#install} at client init; the
 * orchestration never names the per-mod config class.
 *
 * <p>PULL, synchronous, side-effect-free: the addon side is backed by lazily-read BBS
 * {@code Value*} objects and the redactor side by plain mutable fields the ImGui editor
 * writes — neither has a push/listener source to invert. Getters are called inside
 * {@code bake()} and never cached across frames.
 *
 * <p>Frozen contract: {@code irl-core/docs/shadow-config-source-injection-spec.md}.
 */
public interface ShadowConfig
{
    /** Shadow resolution preset ordinal (0 LOW .. 3 ULTRA). A change frees +
     *  re-allocates the depth textures, so the orchestration forgets every cached
     *  map when it changes ({@link ShadowBaker} lastQuality). Default 1 (MEDIUM);
     *  clamped by {@link IRLShadowQuality#applyFromSetting}. */
    int shadowQuality();

    /** When true, depth maps are re-baked only when the scene changes; when false
     *  every light bakes into its live tile every frame. Default true. */
    boolean shadowCache();

    /** Max FULL STATIC bakes started per frame before the rest are deferred to a
     *  later frame (T2.4). {@code <= 0} disables throttling (bake everything every
     *  frame). First bakes / tile-reassign bakes are never deferred; dynamic overlays
     *  and static-&gt;live copies are never budgeted. Default 4. */
    int shadowBakeBudget();

    /** When true, world blocks cast shadows by their real shape. Default true. */
    boolean shadowBlocks();

    /** Block-shadow collection radius in blocks: world blocks farther than this from
     *  a light cast no shadow even when the light's range is larger. Bounds the
     *  per-light bbox walk. Default 24. */
    int shadowBlockRadius();

    /** Pose/oversize slack of the partial-tile spot overlay's dyn-rect AABB,
     *  applied on BOTH axes as a fraction of the caster's half-height:
     *  sources bound casters by their UNPOSED hitbox (entity box, form
     *  hitbox — the model-block sphere included), but BBS content routinely
     *  draws past it — an animated limb reaches roughly its own length
     *  (vanilla humanoid sideways arm tip ~1.19 blocks off-center vs hitbox
     *  half-diagonal ~0.42; 0.42 + 1.0*hv(0.9) = 1.32 covers it), and a
     *  stretched form model can exceed the hitbox in any direction. The
     *  scissor cut from the rect is a HARD bound — an under-estimate clips
     *  the silhouette visibly — so RAISE this until clipped shadow edges
     *  disappear. Deliberately UNCAPPED: large values must keep growing the
     *  box past the hitbox's cull sphere — the only cost is filter area, and
     *  an oversized box just degrades to the full-tile path via the
     *  coversMost gate. Pulled fresh every bake (live knob). OPTIONAL (a
     *  {@code default}): mods that predate it keep compiling and get 1.0
     *  (calibrated 2026-07-19: 0.9 still nicked a stretched model block). */
    default float shadowPoseReach()
    {
        return 1.0f;
    }

    /** Enables the partial-tile spot overlay (dyn-rect scissor + partial
     *  static-&gt;live copy + partial filter rebuild). The rect is a HARD bound
     *  built from caster HITBOXES plus the {@link #shadowPoseReach} slack, so
     *  content drawn far past its hitbox can clip at the rect edge, moving with
     *  the subject; switching this off restores the always-correct full-tile
     *  paths at the full per-overlay-frame copy + filter cost. Pulled fresh
     *  every bake (live knob). OPTIONAL (a {@code default}): mods that predate
     *  it keep compiling with the optimization on. */
    default boolean shadowPartialTile()
    {
        return true;
    }

    /** Master enable for the whole bake. When false, {@link ShadowBaker} skips the
     *  depth render, the filter flushes, the pyramid builds and the tile/VRAM
     *  allocation entirely, releasing what it held — so a mod's "shadows off"
     *  toggle saves the bake cost, not merely the shader-side sampling. The mod is
     *  expected to OR this with any other consumer of the maps (the addon also
     *  drives volumetric beam shadows, which sample the same atlas), so shadows
     *  stay baked while EITHER surface or volumetric shadows are on. OPTIONAL (a
     *  {@code default}): mods that predate it keep compiling and always bake. */
    default boolean shadowsEnabled()
    {
        return true;
    }

    /**
     * Canonical fallback ({@code 1 / true / 4 / true / 24}) so a mod that never
     * installs a config still bakes instead of NPEing. {@link ShadowEngine} uses this
     * until a mod calls {@link ShadowEngine#install}.
     */
    ShadowConfig DEFAULTS = new ShadowConfig()
    {
        public int shadowQuality()     { return 1; }
        public boolean shadowCache()   { return true; }
        public int shadowBakeBudget()  { return 4; }
        public boolean shadowBlocks()  { return true; }
        public int shadowBlockRadius() { return 24; }
    };

    /**
     * Named builder for a {@link ShadowConfig} shim backed by live suppliers — one per
     * getter, called fresh on every read (never cached). Replaces the identical
     * 5-getter anonymous-class shim each mod hand-wrote (addon {@code
     * IrliteShadowConfig}, redactor {@code LightConfig.SHADOW}). Named methods on
     * purpose: a positional {@code of(IntSupplier, BooleanSupplier, IntSupplier,
     * BooleanSupplier, IntSupplier)} factory would let two same-typed suppliers swap
     * silently with no compiler error.
     */
    static Builder builder()
    {
        return new Builder();
    }

    /** @see ShadowConfig#builder() */
    final class Builder
    {
        private IntSupplier shadowQuality;
        private BooleanSupplier shadowCache;
        private IntSupplier shadowBakeBudget;
        private BooleanSupplier shadowBlocks;
        private IntSupplier shadowBlockRadius;
        private DoubleSupplier shadowPoseReach;
        private BooleanSupplier shadowPartialTile;
        private BooleanSupplier shadowsEnabled;

        private Builder()
        {}

        public Builder shadowQuality(IntSupplier shadowQuality)
        {
            this.shadowQuality = shadowQuality;
            return this;
        }

        public Builder shadowCache(BooleanSupplier shadowCache)
        {
            this.shadowCache = shadowCache;
            return this;
        }

        public Builder shadowBakeBudget(IntSupplier shadowBakeBudget)
        {
            this.shadowBakeBudget = shadowBakeBudget;
            return this;
        }

        public Builder shadowBlocks(BooleanSupplier shadowBlocks)
        {
            this.shadowBlocks = shadowBlocks;
            return this;
        }

        public Builder shadowBlockRadius(IntSupplier shadowBlockRadius)
        {
            this.shadowBlockRadius = shadowBlockRadius;
            return this;
        }

        /** OPTIONAL (unlike the five originals): omitted = the interface
         *  default 1.0, so pre-existing shims keep building unchanged. */
        public Builder shadowPoseReach(DoubleSupplier shadowPoseReach)
        {
            this.shadowPoseReach = shadowPoseReach;
            return this;
        }

        /** OPTIONAL: omitted = the interface default (partial path on), so
         *  pre-existing shims keep building unchanged. */
        public Builder shadowPartialTile(BooleanSupplier shadowPartialTile)
        {
            this.shadowPartialTile = shadowPartialTile;
            return this;
        }

        /** OPTIONAL: omitted = the interface default (always enabled), so shims
         *  that predate the bake gate keep building and keep baking. */
        public Builder shadowsEnabled(BooleanSupplier shadowsEnabled)
        {
            this.shadowsEnabled = shadowsEnabled;
            return this;
        }

        public ShadowConfig build()
        {
            IntSupplier quality = requireNonNull(shadowQuality, "shadowQuality");
            BooleanSupplier cache = requireNonNull(shadowCache, "shadowCache");
            IntSupplier bakeBudget = requireNonNull(shadowBakeBudget, "shadowBakeBudget");
            BooleanSupplier blocks = requireNonNull(shadowBlocks, "shadowBlocks");
            IntSupplier blockRadius = requireNonNull(shadowBlockRadius, "shadowBlockRadius");
            DoubleSupplier poseReach = shadowPoseReach;    // optional, may be null
            BooleanSupplier partial = shadowPartialTile;   // optional, may be null
            BooleanSupplier enabled = shadowsEnabled;      // optional, may be null
            return new ShadowConfig()
            {
                public int shadowQuality()     { return quality.getAsInt(); }
                public boolean shadowCache()   { return cache.getAsBoolean(); }
                public int shadowBakeBudget()  { return bakeBudget.getAsInt(); }
                public boolean shadowBlocks()  { return blocks.getAsBoolean(); }
                public int shadowBlockRadius() { return blockRadius.getAsInt(); }
                public float shadowPoseReach() { return poseReach == null ? 1.0f : (float) poseReach.getAsDouble(); }
                public boolean shadowPartialTile() { return partial == null || partial.getAsBoolean(); }
                public boolean shadowsEnabled() { return enabled == null || enabled.getAsBoolean(); }
            };
        }

        private static <T> T requireNonNull(T value, String name)
        {
            if (value == null)
            {
                throw new IllegalStateException("ShadowConfig.builder(): missing " + name + "(...)");
            }
            return value;
        }
    }
}
