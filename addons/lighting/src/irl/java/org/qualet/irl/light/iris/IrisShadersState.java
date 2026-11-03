package org.qualet.irl.light.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/**
 * Answers "are Iris shaders definitely OFF this frame?" for the light pipeline
 * gate at renderWorld HEAD: with no shaderpack active nothing consumes the
 * light SSBO or the shadow maps, so collecting lights and baking shadows would
 * be pure waste (see GameRendererLightMixin).
 *
 * Deliberately conservative: a null pipeline (first frame of a session or a
 * shaderpack reload — Iris only builds it later in the frame, inside
 * renderLevel) reports NOT disabled, so the pipeline does one frame of
 * possibly-wasted work rather than ever leaving the SSBO unbound for a frame
 * that does render with shaders. Any Iris API failure permanently fails open
 * the same way.
 */
public final class IrisShadersState
{
    private static boolean broken;

    private IrisShadersState()
    {}

    /** True only when Iris positively reports the vanilla (no shaderpack) pipeline. */
    public static boolean shadersDisabled()
    {
        if (broken)
        {
            return false;
        }

        try
        {
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

            return pipeline instanceof VanillaRenderingPipeline;
        }
        catch (Throwable t)
        {
            broken = true;

            return false;
        }
    }
}
