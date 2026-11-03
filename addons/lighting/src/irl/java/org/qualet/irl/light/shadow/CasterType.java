package org.qualet.irl.light.shadow;

/**
 * Neutral occluder draw-arm tags, shared between the orchestration and every
 * {@link ShadowCasterSource}. Relocated off {@link ShadowRenderer} (the GL class)
 * so a caster source need not import the GL layer to name a tag (seam contract
 * CHANGE 8).
 *
 * <p>The tag selects ONLY which {@link ShadowCasterSource#emitOccluder} draw arm
 * runs; it is INDEPENDENT of the static/dynamic split, which a source declares
 * separately via the {@code isStatic} flag on {@link OccluderSink} (CHANGE 2).
 */
public final class CasterType
{
    public static final int ENTITY = 0;
    public static final int MODEL_BLOCK = 1;
    public static final int REPLAY = 2;

    private CasterType()
    {}
}
