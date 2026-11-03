package org.qualet.irl.light;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.qualet.irl.light.shadow.ShadowBaker;

import java.util.function.BooleanSupplier;

/**
 * The per-frame light orchestration. {@link #frame} runs at renderWorld HEAD,
 * before Iris activates: collect this frame's lights and bake spotlight/point
 * shadow maps. The camera-relative SSBO upload is split off into
 * {@link #uploadIfPending}, which the mixin invokes just after this frame's
 * {@code Camera.update} so the SSBO origin is the CURRENT-frame eye (uploading at
 * HEAD would use the stale previous-frame camera and make lights jerk on movement).
 * Both IRLite and IRL-redactor drive this identically; the per-mod
 * {@code GameRendererLightMixin} is a thin delegate that supplies the two
 * mod-specific seams (the shaders-off check and the light collect source), the
 * optional dormant hook, and the post-Camera.update {@link #uploadIfPending} call.
 *
 * <h2>Shaders-off gate</h2>
 * With no shaderpack active nothing consumes the SSBO or the shadow maps, so the
 * whole collect/bake/upload pipeline would be wasted work (the shadow bake being
 * the expensive part). The gate drops the render-path registrations that still
 * arrive each frame, releases the GPU-side state ONCE on the transition into the
 * dormant state, and stays dormant until a shaderpack returns. The gate is passed
 * as a {@link BooleanSupplier} rather than called here so the Iris dependency
 * stays out of the parts of the engine that do not need it.
 */
public final class FramePipeline
{
    /** Supplies this frame's lights (world blocks, live actors, replays, …). The
     *  signature matches both mods' collect entry points verbatim. */
    @FunctionalInterface
    public interface LightSource
    {
        void collect(ClientLevel world, Vec3 cameraPos, float tickDelta);
    }

    /** True while the light pipeline is parked because shaders are off; the
     *  GPU-side release (SSBO zero + shadow textures/caches) runs once, on
     *  the transition into that state. */
    private static boolean dormant;

    /** Set by {@link #frame} once this frame's lights are collected + baked, cleared
     *  by {@link #uploadIfPending} once the deferred SSBO upload runs. It bridges the
     *  two renderWorld injection points (HEAD collect/bake -> post-Camera.update upload)
     *  and lets a skipped upload (renderWorld cancelled between them) be recovered on
     *  the next frame instead of leaking a stale, half-collected registry. */
    private static boolean flushPending;

    private FramePipeline()
    {}

    /**
     * Run one frame of the light pipeline.
     *
     * @param tickDelta       render partial tick
     * @param shadersDisabled true iff Iris shaders are positively off this frame
     * @param source          collects this frame's lights into the registry
     * @param onDormant        run once on the transition into the dormant state,
     *                         after the GPU-side release (may be a no-op)
     */
    public static void frame(float tickDelta, BooleanSupplier shadersDisabled, LightSource source, Runnable onDormant)
    {
        // Shaders off -> nothing consumes the SSBO or the shadow maps, so the
        // whole collect/bake/upload pipeline would be wasted work (the shadow
        // bake being the expensive part). Drop the render-path registrations
        // that still arrive each frame, release the GPU-side state once, and
        // stay dormant until a shaderpack returns.
        if (shadersDisabled.getAsBoolean())
        {
            LightRegistry.clear();
            flushPending = false;

            if (!dormant)
            {
                dormant = true;
                LightBuffer.uploadEmpty();
                ClusterGridBuffer.uploadEmpty();
                ShadowBaker.onShadersDisabled();
                onDormant.run();
            }

            return;
        }

        // Self-consistency guard: if last frame's deferred upload never ran (renderWorld
        // was cancelled between the HEAD collect and the post-Camera.update upload), its
        // collected set was neither uploaded nor cleared. Drop it now so this frame starts
        // clean instead of double-accumulating stale (departed-actor) lights. That frame's
        // world render — and thus its render-path light registrations — was cancelled too,
        // so nothing legitimate is lost.
        if (flushPending)
        {
            LightRegistry.clear();
            flushPending = false;
        }

        dormant = false;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera != null ? camera.getPosition() : Vec3.ZERO;
        // Forward look vector for the shadow baker's behind-camera light cull.
        Vec3 cameraForward = camera != null ? Vec3.directionFromRotation(camera.getXRot(), camera.getYRot()) : null;

        source.collect(world, cameraPos, tickDelta);

        // Rank this frame's lights by camera-distance priority BEFORE the bake, so
        // the shadow baker hands out tiles / static-bake budget to the highest-
        // priority lights first and the deferred flush caps the SSBO upload to that
        // same order.
        LightRegistry.prioritize(cameraPos.x, cameraPos.y, cameraPos.z);

        // Bake spotlight shadow depth maps BEFORE the SSBO upload (sets each
        // spot's shadow tile index) and before Iris activates (vanilla entity
        // rendering writes into our depth FBO).
        if (world != null && camera != null)
        {
            mc.getEntityRenderDispatcher().prepare(world, camera, mc.getCameraEntity());
        }
        ShadowBaker.bake(world, cameraPos, cameraForward, tickDelta);

        // The camera-relative SSBO upload is DEFERRED to uploadIfPending(), invoked by
        // the per-mod mixin just after this frame's Camera.update. Collect + bake stay
        // here at renderWorld HEAD (before Iris activates), but the origin the SSBO is
        // made relative to must be THIS frame's eye — which vanilla only computes later,
        // in Camera.update. Uploading here would subtract the PREVIOUS frame's camera
        // (getCamera() is not yet updated at HEAD) while the shaderpack reconstructs
        // fragments against this frame's eye, so a moving camera would drag the light by
        // one frame of motion (a visible jerk under frametime spikes). Marking pending
        // hands the upload to the post-update injection.
        flushPending = true;
    }

    /**
     * Upload this frame's collected lights to the SSBO, made relative to the CURRENT
     * frame's camera. Called from the per-mod {@code GameRendererLightMixin} at the
     * point just after {@code Camera.update} inside renderWorld — after vanilla has
     * positioned this frame's eye but before the world (and Iris) render, so the SSBO
     * origin matches the very camera the shaderpack reconstructs fragment positions
     * from and a camera-relative light no longer lags or jerks by a frame of camera
     * motion. No-op unless {@link #frame} collected this frame.
     */
    public static void uploadIfPending()
    {
        if (!flushPending)
        {
            return;
        }
        flushPending = false;

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 p = camera != null ? camera.getPosition() : Vec3.ZERO;
        LightRegistry.flush(p.x, p.y, p.z);
    }

    /**
     * Rasterise this frame's light snapshot into the {@link ClusterGridBuffer} tile
     * masks, invoked by the addon's cluster mixin right after Iris captures the
     * gbuffer matrices. Those are the matrices fragments actually use — the modelview
     * INCLUDES bobbing, and the light snapshot is camera-relative to the (bob-free)
     * eye — so the masks must be built with the captured full modelview, not a
     * hand-rolled rotation.
     *
     * <p>Guards, in order:</p>
     * <ul>
     *   <li>clustering disabled -&gt; {@link ClusterGridBuffer#uploadEmpty()} (itself
     *       idempotent, so no per-frame re-upload) and return;</li>
     *   <li>no snapshot recorded THIS frame (the hook fired a second time, or flush
     *       never ran) -&gt; return, leaving the previously uploaded masks untouched
     *       rather than rebuilding a consumed snapshot against new matrices;</li>
     *   <li>otherwise rasterise + upload (buildAndUpload clears the fresh flag).</li>
     * </ul>
     */
    public static void onGbufferMatricesCaptured(Matrix4f modelView, Matrix4f projection)
    {
        if (!ClusterGridBuffer.isEnabled())
        {
            ClusterGridBuffer.uploadEmpty();
            return;
        }

        if (!ClusterGridBuffer.hasFreshSnapshot())
        {
            return;
        }

        ClusterGridBuffer.buildAndUpload(modelView, projection);
    }
}
