package org.qualet.irl.light.shadow;

import org.joml.Matrix4f;

/**
 * Opaque per-pass batch handle a {@link ShadowCasterSource#emitOccluder} emits
 * geometry into. The source downcasts to its known backend (here
 * {@link ImmediateOccluderBatch} for the vanilla entity Immediate; a raw depth-VBO
 * accumulator on the 1.21.11 port) — the orchestration never inspects it.
 *
 * <p>The two hooks below are SHARED-owned (the wrapper around emitOccluder calls
 * them); a source never calls them. They exist so the shared layer can enforce
 * INVARIANT 4 (run isolation): on a throw mid-emit it terminates the broken
 * caster's partial run BEFORE the next emit, so partial vertices can never fuse
 * with the next caster's into a garbage quad. See the seam spec.
 */
public interface OccluderBatch
{
    /** Snapshot the start-of-this-caster write position (for a rewindable raw
     *  buffer). The Immediate backend has no rewindable cursor and returns 0 — it
     *  isolates by draining in {@link #terminateRun} instead. */
    long mark();

    /** Terminate the current caster's run on a throw: drain an Immediate batch
     *  (re-asserting the light {@code view}/{@code proj} first, INVARIANT 1) or
     *  rewind a raw buffer to the last {@link #mark}. */
    void terminateRun(Matrix4f view, Matrix4f proj);
}
