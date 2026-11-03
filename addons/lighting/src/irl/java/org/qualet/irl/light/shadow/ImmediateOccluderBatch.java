package org.qualet.irl.light.shadow;

import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * {@link OccluderBatch} backed by the shared entity {@link MultiBufferSource.BufferSource}
 * — the batch kind for variants whose caster draw emits into a vanilla Immediate
 * (redactor real-model entities; later IRLite BBS forms). A {@link ShadowCasterSource}
 * downcasts to read {@link #immediate()} and the shared scratch {@link #matrices()};
 * the shared layer owns {@link #mark()}/{@link #terminateRun} and the once-per-pass
 * success flush.
 *
 * <p>The Immediate has no rewindable write cursor, so run isolation (INVARIANT 4)
 * is by DRAINING (terminateRun → single draw, re-asserting the light matrices per
 * INVARIANT 1) rather than rewinding; {@link #mark()} is therefore unused (0).
 */
final class ImmediateOccluderBatch implements OccluderBatch
{
    private MultiBufferSource.BufferSource immediate;
    private PoseStack matrices;

    /** (Re)bind to the current pass's shared Immediate + scratch stack. Called by
     *  the shared wrapper before each {@code emitOccluder}. */
    void bind(MultiBufferSource.BufferSource immediate, PoseStack matrices)
    {
        this.immediate = immediate;
        this.matrices = matrices;
    }

    MultiBufferSource.BufferSource immediate()
    {
        return immediate;
    }

    PoseStack matrices()
    {
        return matrices;
    }

    @Override
    public long mark()
    {
        return 0L;
    }

    @Override
    public void terminateRun(Matrix4f view, Matrix4f proj)
    {
        ShadowRenderer.flushCasterImmediate(immediate, view, proj);
    }
}
