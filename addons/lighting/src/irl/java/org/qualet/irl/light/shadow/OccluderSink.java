package org.qualet.irl.light.shadow;

import net.minecraft.world.phys.AABB;

/**
 * Allocation-free writer for the orchestration's faceless bounded occluder
 * Struct-of-Arrays (occ/occType/oStatic/ox/oy/oz/orad/ostatichash). A
 * {@link ShadowCasterSource#collect} impl calls one {@code emit*} method per
 * in-range caster; the shared implementation appends the args into the parallel
 * arrays with primitive args only — NO heap allocation, NO boxing, NO return
 * value. Over-cap casters are reduced to the nearest bounded set by the sink,
 * so a source need not pre-count. Non-finite centers/radii and negative radii
 * are discarded as malformed input.
 *
 * <p>See {@code irl-core/docs/shadow-caster-seam-spec.md}. The blessed path is
 * {@link #emitFromBox}, which COMPUTES the cull-pinned bounding sphere from the
 * caster's box so a source cannot supply a foreign sphere (INVARIANT 5).
 */
public interface OccluderSink
{
    /**
     * Blessed path: append one BOX-shaped occluder, computing the cull-pinned
     * bounding sphere internally (INVARIANT 5):
     * <pre>
     *   center = (interpX, interpY + boxHeight*0.5, interpZ)   // cy raised to mid-height
     *   radius = 0.5 * sqrt(dx*dx + dy*dy + dz*dz) * scale + OVERLAP_MARGIN
     * </pre>
     * where dx/dy/dz are {@code box} edge lengths — half the box DIAGONAL (a true
     * circumscribing radius), times {@code scale} for transform-scaled casters,
     * plus OVERLAP_MARGIN as pure slack. For a ROTATED caster the source MUST pass
     * a {@code box} that already encloses the rotated geometry.
     *
     * @param caster     faceless caster handle, stored as occ[k]; never unwrapped by the orchestration
     * @param type       {@link CasterType} draw-arm tag
     * @param isStatic   TRUE iff baked into the never-rebaked static layer AND its
     *                   silhouette changes ONLY when {@code staticHash} changes (INVARIANT 2)
     * @param interpX    render-interpolated caster origin X (world)
     * @param interpY    render-interpolated caster origin Y (feet / box minY; raised internally)
     * @param interpZ    render-interpolated caster origin Z (world)
     * @param box        the caster's drawn AABB (already inflated / rotation-expanded by the source)
     * @param scale      post-transform uniform scale (1f for unscaled casters)
     * @param staticHash full-avalanche silhouette signature for an isStatic caster, else 0L (INVARIANT 3)
     */
    void emitFromBox(Object caster, int type, boolean isStatic,
                     double interpX, double interpY, double interpZ,
                     AABB box, float scale, long staticHash);

    /**
     * Raw escape hatch for a NON-box caster: append with a pre-computed sphere.
     * The sink cannot validate the floats, so the bounding-sphere convention
     * (INVARIANT 5) is here a SOURCE obligation — prefer {@link #emitFromBox}.
     *
     * @param cx     bounding-sphere center X (world)
     * @param cy     bounding-sphere center Y, RAISED to mid-height
     * @param cz     bounding-sphere center Z (world)
     * @param radius CIRCUMSCRIBING radius = half box DIAGONAL (post-scale/rotate) + OVERLAP_MARGIN
     */
    void emit(Object caster, int type, boolean isStatic,
              float cx, float cy, float cz, float radius, long staticHash);
}
