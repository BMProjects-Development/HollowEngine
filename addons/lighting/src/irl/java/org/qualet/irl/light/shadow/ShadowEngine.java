package org.qualet.irl.light.shadow;

/**
 * Holds the per-mod shadow dependencies for the running mod: the
 * {@link ShadowCasterSource} (WHAT/HOW to draw casters) and the {@link ShadowConfig}
 * (quality/budget knobs). Install once at client init
 * ({@code ShadowEngine.install(new MyCasterSource(), MyConfig.SHADOW)}), beside
 * {@link org.qualet.irl.patcher.Patcher#install}; {@link ShadowBaker} reads both
 * through {@link #source()} / {@link #config()}.
 *
 * <p>Mirrors the {@link org.qualet.irl.patcher.Patcher} holder pattern (a
 * {@code volatile} field + fail-fast getter). One holder carries BOTH deps so a mod
 * makes a single init call, symmetric to {@code Patcher.install}.
 *
 * <p>Frozen contract: {@code irl-core/docs/shadow-config-source-injection-spec.md}.
 */
public final class ShadowEngine
{
    private static volatile ShadowCasterSource source;
    private static volatile ShadowConfig config = ShadowConfig.DEFAULTS;
    /** Optional dev-profiler probe; null (the default) = disabled, every bake
     *  call site degrades to a null-check no-op. */
    private static volatile ShadowBakeProbe bakeProbe;

    private ShadowEngine()
    {}

    /**
     * Install at client init, BEFORE the first bake. {@code src} is required;
     * {@code cfg} may be null (falls back to {@link ShadowConfig#DEFAULTS}).
     */
    public static void install(ShadowCasterSource src, ShadowConfig cfg)
    {
        source = src;
        config = (cfg != null) ? cfg : ShadowConfig.DEFAULTS;
    }

    /**
     * The injected caster source. Fail-fast (like
     * {@link org.qualet.irl.patcher.Patcher#host()}) if a mod forgot to install one —
     * a missing source means no shadows can be cast, so it is a wiring bug, not a
     * silent no-op.
     */
    public static ShadowCasterSource source()
    {
        ShadowCasterSource s = source;
        if (s == null)
        {
            throw new IllegalStateException(
                "ShadowCasterSource not installed — call ShadowEngine.install(...) at client init");
        }
        return s;
    }

    /** Never null: the installed config, or {@link ShadowConfig#DEFAULTS}. */
    public static ShadowConfig config()
    {
        return config;
    }

    /** Install the optional bake profiler probe (dev-only; see
     *  {@link ShadowBakeProbe}). Unlike {@link #install} this is genuinely
     *  optional — no mod installs one in normal runs. */
    public static void installBakeProbe(ShadowBakeProbe probe)
    {
        bakeProbe = probe;
    }

    /** The installed bake probe, or null (the default: profiling disabled). */
    public static ShadowBakeProbe bakeProbe()
    {
        return bakeProbe;
    }
}
