package org.qualet.irl.light.shadow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The frozen shadow seam: the ONLY thing a shadow variant supplies. Everything
 * else (FBO/tile/scissor, the begin*()/emit/flush/endPass pass lifecycle, light
 * view+proj, the depth program, the static/dynamic 2-layer bake, the
 * {@link ShadowBakeState} world-suppression gate, the scratch PoseStack, all
 * perf opts, cull, the per-light dirty cache, the batch lifetime + flush site,
 * the per-caster matrix re-establish, and the per-caster exception isolation)
 * lives in the SHARED orchestration ({@link ShadowBaker}) + GL layer
 * ({@link ShadowRenderer}).
 *
 * <p>See {@code irl-core/docs/shadow-caster-seam-spec.md} for the full contract,
 * the 5 load-bearing invariants, and the per-variant implementation sketches
 * (redactor-main entity dispatcher, redactor-port raw-VBO box blob, IRLite BBS
 * Form/Film/Morph). Each mod's source-impl class lives in this same package.
 */
public interface ShadowCasterSource
{
    /**
     * WHAT casts. Called ONCE per bake by the shared orchestration, BEFORE any
     * {@code begin*()} pass and before both the spot and point loops; the SoA is
     * then stable for the whole bake. The impl walks its world/scene and calls one
     * {@code sink.emit*()} per in-range caster, setting the {@link CasterType} tag,
     * the {@code isStatic} flag (INVARIANT 2 — independent of the tag), the pinned
     * bounding sphere (INVARIANT 5 — prefer {@link OccluderSink#emitFromBox}), and
     * {@code staticHash} (INVARIANT 3 — a full-avalanche signature for a static
     * caster, else exactly 0L). Source-side robustness (reflection/accessor
     * try/catch) lives INSIDE this impl; a throwing collect for one caster degrades
     * to "that caster is absent", never aborts the bake.
     */
    void collect(ClientLevel world, Vec3 camPos, float tickDelta, OccluderSink sink);

    /** Optional complete silhouette contract, sampled once for each kept dynamic
     * caster after collect and before any draw. Unsupported poses, resource state,
     * or view-dependent rendering MUST return UNKNOWN. Legacy hosts remain dirty. */
    default CasterRevision revision(Object caster, int type, float tickDelta)
    {
        return CasterRevision.UNKNOWN;
    }

    /**
     * HOW to draw ONE shortlisted caster for the current pass. The shared layer has
     * ALREADY set up the frame (FBO/viewport/scissor, light view onto the ambient
     * {@code currentView}/{@code currentProj} + live RenderSystem, depth state, the
     * {@link ShadowBakeState#setBaking} gate, the reset scratch PoseStack) and
     * OPENED the batch. The impl ONLY emits geometry into {@code batch} (downcasting
     * it to the known backend); it MUST NOT touch FBO/tile/scissor, toggle setBaking,
     * flush/terminate the batch, or swallow its own exceptions.
     *
     * <p>This is an EMIT/APPEND op: one pass calls emitOccluder for every shortlisted
     * caster, then the SHARED layer flushes once on the success path. INVARIANT 1:
     * this call MAY dirty live RenderSystem matrices — the shared layer repairs them.
     * INVARIANT 4: this call MAY throw — the shared wrapper isolates and terminates
     * the run. The impl does neither itself.
     */
    void emitOccluder(Object caster, int type, float tickDelta, OccluderBatch batch);
}
