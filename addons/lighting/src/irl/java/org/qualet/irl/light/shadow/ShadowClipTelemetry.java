package org.qualet.irl.light.shadow;

import java.util.Locale;

/**
 * Clip-coverage telemetry (-Dirlite.clipTelemetry=true): answers "is a caster
 * baked COMPLETELY, and if not, which gate cuts it?" for the oversized-model
 * symptom (a scaled form's shadow clips at an edge or vanishes). Every cull
 * decision consumes only the caster's bounding sphere, which sources build from
 * the form HITBOX — a value BBS never derives from geometry — so a scaled-up
 * visual routinely outgrows it and the gates start cutting real silhouettes.
 *
 * <p>Rather than log every reject (most rejects are legitimately-distant
 * casters), each gate is re-tested with a PROBE sphere {@code orad * MUL + ADD}
 * (-Dirlite.clipProbeMul / -Dirlite.clipProbeAdd, default x2+2 blocks): a
 * decision that FLIPS under the probe marks a caster whose true geometry —
 * if it exceeds the declared sphere by up to the probe factor — is being
 * range-culled ({@code range-miss}), cone-culled ({@code cone-miss}), dropped
 * from point cube faces it visually spills into ({@code face-miss}, the razor
 * cut along a cube-face seam), or scissored by the spot overlay's partial-tile
 * rect ({@code rect-tight}). Two probe-free signals complete the set:
 * {@code far-cross} (the DECLARED sphere already crosses the bake far plane =
 * light range — the far side of the caster is clipped regardless of the
 * sphere's honesty) and {@code pool-drop} (bounded pool evictions).
 *
 * <p>The caster source may additionally report scale factors the emitted
 * sphere does NOT track via {@link #noteUntrackedScale} (IRLite: form
 * transform + overlay fold and the bbmodel config scale) — the second window
 * line then names the factor that shrank the sphere.
 *
 * <p>House cadence: counters accumulate per 1-second window (the PROFILE
 * idiom) and flush as one {@code [irlite] shadow-clip:} line, printed only
 * when at least one signal fired. All hooks sit behind {@link #ENABLED}
 * (static final — dead-code-eliminated in production runs).
 */
public final class ShadowClipTelemetry
{
    public static final boolean ENABLED = Boolean.getBoolean("irlite.clipTelemetry");

    /** Probe sphere = orad * PROBE_MUL + PROBE_ADD (blocks). */
    static final float PROBE_MUL = floatProp("irlite.clipProbeMul", 2.0f);
    static final float PROBE_ADD = floatProp("irlite.clipProbeAdd", 2.0f);

    /** Untracked-scale factors within 5% of 1 are noise, not a signal. */
    private static final float SCALE_REPORT_MIN = 1.05f;

    private static final long WINDOW_NS = 1_000_000_000L;

    private static long windowStart;
    private static int frames;

    /** Light id + kind of the scan in progress, set by {@link #beginScan} at the
     *  two scanInRange call sites so per-caster notes can attribute the lamp. */
    private static long curLight;
    private static boolean curSpot;

    private static int rangeMiss;
    private static int rangeType;
    private static float rangeX, rangeY, rangeZ, rangeVal;
    private static long rangeLight;
    private static boolean rangeSpot;

    private static int coneMiss;
    private static int coneType;
    private static float coneX, coneY, coneZ;
    private static long coneLight;

    private static int faceMiss;
    private static int faceType;
    private static float faceX, faceY, faceZ;
    private static int faceMask;
    private static long faceLight;

    private static int farCross;
    private static int farType;
    private static float farX, farY, farZ, farVal;
    private static long farLight;
    private static boolean farSpot;

    private static int rectTight;
    private static int rectPx;
    private static long rectLight;

    private static int poolDrops;

    private static float scaleWorst;
    private static int scaleType;
    private static double scaleX, scaleY, scaleZ;
    private static float scaleForm, scaleCfg, scaleRad;

    private ShadowClipTelemetry()
    {}

    private static float floatProp(String key, float def)
    {
        try
        {
            String v = System.getProperty(key);
            float f = v == null ? def : Float.parseFloat(v);
            return f >= 0f ? f : def;
        }
        catch (Throwable t)
        {
            return def;
        }
    }

    static void beginScan(long lightId, boolean spot)
    {
        curLight = lightId;
        curSpot = spot;
    }

    /** Range gate flipped under the probe: dist exceeded reach by {@code missBlocks}. */
    static void noteRangeMiss(int type, float x, float y, float z, float missBlocks)
    {
        rangeMiss++;
        if (rangeMiss == 1 || missBlocks > rangeVal)
        {
            rangeType = type;
            rangeX = x; rangeY = y; rangeZ = z;
            rangeVal = missBlocks;
            rangeLight = curLight;
            rangeSpot = curSpot;
        }
    }

    /** Spot cone gate flipped under the probe. */
    static void noteConeMiss(int type, float x, float y, float z)
    {
        coneMiss++;
        coneType = type;
        coneX = x; coneY = y; coneZ = z;
        coneLight = curLight;
    }

    /** Point face mask gained faces under the probe; {@code missingMask} = the
     *  faces the declared sphere is NOT drawn into but the probe reaches. */
    static void noteFaceMiss(int type, float x, float y, float z, int missingMask)
    {
        faceMiss++;
        faceType = type;
        faceX = x; faceY = y; faceZ = z;
        faceMask = missingMask;
        faceLight = curLight;
    }

    /** Declared sphere crosses the bake far plane (= light range) by {@code overhang}. */
    static void noteFarCross(int type, float x, float y, float z, float overhang)
    {
        farCross++;
        if (farCross == 1 || overhang > farVal)
        {
            farType = type;
            farX = x; farY = y; farZ = z;
            farVal = overhang;
            farLight = curLight;
            farSpot = curSpot;
        }
    }

    /** Spot overlay dyn rect would grow by {@code escapePx} tile pixels under the probe. */
    static void noteRectTight(long lightId, int escapePx)
    {
        rectTight++;
        if (rectTight == 1 || escapePx > rectPx)
        {
            rectPx = escapePx;
            rectLight = lightId;
        }
    }

    /** Bounded caster pool evicted/rejected an occluder this frame. */
    static void notePoolDrop()
    {
        poolDrops++;
    }

    /**
     * Source-side note: scale factors the emitted sphere does NOT track.
     * {@code formScale} = the effective form-transform scale fold (form +
     * overlay), {@code cfgScale} = the bbmodel config.json scale; both 1 when
     * absent. The window keeps the caster with the largest combined factor.
     */
    public static void noteUntrackedScale(int type, double x, double y, double z,
                                          float sphereRadius, float formScale, float cfgScale)
    {
        float combined = formScale * cfgScale;
        if (!(combined > scaleWorst))
        {
            return;
        }
        scaleWorst = combined;
        scaleType = type;
        scaleX = x; scaleY = y; scaleZ = z;
        scaleForm = formScale;
        scaleCfg = cfgScale;
        scaleRad = sphereRadius;
    }

    /** Once per bake() frame: window arithmetic + flush (PROFILE idiom). */
    static void recordFrame()
    {
        frames++;
        long now = System.nanoTime();
        if (windowStart == 0L)
        {
            windowStart = now;
            return;
        }
        if (now - windowStart < WINDOW_NS)
        {
            return;
        }

        boolean gates = rangeMiss > 0 || coneMiss > 0 || faceMiss > 0
            || rectTight > 0 || farCross > 0 || poolDrops > 0;
        if (gates)
        {
            System.out.println(String.format(Locale.ROOT,
                "[irlite] shadow-clip: range-miss %s | cone-miss %s | face-miss %s | rect-tight %s | far-cross %s | pool-drop %d | probe x%.1f+%.1f | %d frames",
                rangeMiss == 0 ? "0" : String.format(Locale.ROOT, "%d (worst %s @%.1f,%.1f,%.1f +%.2f blk, %s)",
                    rangeMiss, typeName(rangeType), rangeX, rangeY, rangeZ, rangeVal, lightLabel(rangeSpot, rangeLight)),
                coneMiss == 0 ? "0" : String.format(Locale.ROOT, "%d (last %s @%.1f,%.1f,%.1f, %s)",
                    coneMiss, typeName(coneType), coneX, coneY, coneZ, lightLabel(true, coneLight)),
                faceMiss == 0 ? "0" : String.format(Locale.ROOT, "%d (last %s @%.1f,%.1f,%.1f faces %s, %s)",
                    faceMiss, typeName(faceType), faceX, faceY, faceZ, faceNames(faceMask), lightLabel(false, faceLight)),
                rectTight == 0 ? "0" : String.format(Locale.ROOT, "%d (max +%d px, %s)",
                    rectTight, rectPx, lightLabel(true, rectLight)),
                farCross == 0 ? "0" : String.format(Locale.ROOT, "%d (worst %s @%.1f,%.1f,%.1f +%.2f blk, %s)",
                    farCross, typeName(farType), farX, farY, farZ, farVal, lightLabel(farSpot, farLight)),
                poolDrops, PROBE_MUL, PROBE_ADD, frames));
        }
        if (scaleWorst >= SCALE_REPORT_MIN)
        {
            System.out.println(String.format(Locale.ROOT,
                "[irlite] shadow-clip(src): untracked scale worst %s @%.1f,%.1f,%.1f form x%.2f cfg x%.2f (sphere r=%.2f)%s",
                typeName(scaleType), scaleX, scaleY, scaleZ, scaleForm, scaleCfg, scaleRad,
                gates ? "" : " | no gate flips this window"));
        }

        windowStart = now;
        frames = 0;
        rangeMiss = 0;
        rangeVal = 0f;
        coneMiss = 0;
        faceMiss = 0;
        farCross = 0;
        farVal = 0f;
        rectTight = 0;
        rectPx = 0;
        poolDrops = 0;
        scaleWorst = 0f;
    }

    /** Sample attribution: cone/rect misses are spot-only, face misses point-only;
     *  range/far carry the kind captured by {@link #beginScan}. */
    private static String lightLabel(boolean spot, long id)
    {
        return (spot ? "spot#" : "point#") + id;
    }

    private static String typeName(int type)
    {
        switch (type)
        {
            case CasterType.ENTITY: return "ENTITY";
            case CasterType.MODEL_BLOCK: return "MODEL_BLOCK";
            case CasterType.REPLAY: return "REPLAY";
            default: return "TYPE" + type;
        }
    }

    /** Face bit order matches {@code ShadowRenderer.beginPointFace}: +X -X +Y -Y +Z -Z. */
    private static String faceNames(int mask)
    {
        if (mask == 0)
        {
            return "-";
        }
        StringBuilder sb = new StringBuilder(12);
        String[] names = {"+X", "-X", "+Y", "-Y", "+Z", "-Z"};
        for (int face = 0; face < 6; face++)
        {
            if ((mask & (1 << face)) != 0)
            {
                if (sb.length() > 0)
                {
                    sb.append(',');
                }
                sb.append(names[face]);
            }
        }
        return sb.toString();
    }
}
