package org.qualet.irl.light.shadow;

import java.nio.IntBuffer;

import org.lwjgl.system.MemoryStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes.DoubleLineConsumer;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.List;

/**
 * Bakes spotlight shadow depth maps. Phase 1: perspective depth tile per spot,
 * entity occluders only (vanilla render dispatcher), hard shadow. Ported from
 * the original IRLights ShadowRenderer (spot + entity path).
 *
 * Usage: beginSpot(...) -> renderEntity(...) * -> endPass().
 */
public final class ShadowRenderer
{
    private static final float NEAR = 0.05f;

    // Constant "up" axes for the spot lookAt — lookAt only reads them, so a
    // shared instance replaces the per-call allocation (T1.3).
    private static final Vector3f UP_Y = new Vector3f(0f, 1f, 0f);
    private static final Vector3f UP_Z = new Vector3f(0f, 0f, 1f);

    private static boolean inPass = false;
    /** True once {@link #savePassState} has snapshotted the original GL state
     *  this bake. {@link #beginBake} clears it so the next bake re-grabs the
     *  (possibly different) state, while passes within one bake share the
     *  single snapshot instead of re-running the glGet* sync points. */
    private static boolean passStateSaved = false;

    private static int savedFbo;
    private static final int[] savedViewport = new int[4];
    private static boolean savedScissorEnabled;
    private static final int[] savedScissorBox = new int[4];
    private static boolean savedCullEnabled;
    private static int savedCullFace;
    private static int savedFrontFace;
    private static Matrix4f savedProj;
    private static VertexSorting savedSorter;
    private static boolean savedMaskR, savedMaskG, savedMaskB, savedMaskA;

    private static final Matrix4f currentView = new Matrix4f();
    /** The current light's projection, mirrored from what applyMatrices set on
     *  RenderSystem. The block batch draws with this + currentView directly,
     *  NOT RenderSystem's live matrices, which a caster can leave corrupted. */
    private static final Matrix4f currentProj = new Matrix4f();

    // --- Per-pass bake anchor (light-relative bake) --------------------------
    // The depth pass bakes every caster AND the light view matrix around this
    // per-pass double anchor A = round(lightPos) instead of absolute world
    // coordinates, so the float matrix math stays sub-block-magnitude and its
    // error is translation-invariant far from the origin. A cancels exactly in
    // view*vertex (view = R·Translate(-(L-A)), vertex = worldPos-A, so
    // view*vertex = R·(worldPos-L)), leaving the stored perspective depth
    // bit-identical — no shader/atlas/format change. Every caster arm (block VBO,
    // cutout VBO, entity/model/replay Immediate) subtracts these BEFORE its float
    // cast; the eye = L-A is set from the DOUBLE light position in begin*().
    // round(lightPos) (not L itself) keeps the light's sub-block motion in the
    // per-frame eye and OUT of the per-light cached block/cutout VBO: it is
    // byte-identical to BlockShadowCache's block snap (round of the same float
    // getX), so the shared pass anchor always matches the anchor the cached VBO
    // was built with (a light drifting within its 1-block cell reuses the VBO and
    // is tracked exactly by the recomputed eye).
    private static double currentOriginX, currentOriginY, currentOriginZ;

    public static double currentOriginX() { return currentOriginX; }
    public static double currentOriginY() { return currentOriginY; }
    public static double currentOriginZ() { return currentOriginZ; }

    // --- Reused matrix scratch (T1.3) ----------------------------------------
    // RenderSystem.setProjectionMatrix copies its argument, and the block batch
    // draws with currentProj (a copy made in applyMatrices), so reusing these
    // projection objects across passes is safe — endPass restores the saved one.
    /** Spot projection; rebuilt only when its fov/far change (all of a light's
     *  passes, and consecutive equal lights, reuse it). */
    private static final Matrix4f spotProj = new Matrix4f();
    private static float spotProjFov = Float.NaN;
    private static float spotProjFar = Float.NaN;
    /** Point projection — identical for all 6 cube faces; rebuilt only when far
     *  (= radius) changes. */
    private static final Matrix4f pointProj = new Matrix4f();
    private static float pointProjFar = Float.NaN;
    /** Shared per-caster / per-cutout-block PoseStack (the render thread is
     *  single-threaded). {@link #resetScratch} drains + identity-resets it
     *  before each use, so a caster that throws mid-build can't leave it dirty. */
    private static final PoseStack scratch = new PoseStack();

    /** Reused Immediate-backed batch handed to {@link ShadowCasterSource#emitOccluder}
     *  (the render thread is single-threaded, so one instance is safe). */
    private static final ImmediateOccluderBatch casterBatch = new ImmediateOccluderBatch();

    private static long casterFailures;

    /** Monotonic failure stamp: a recovered partial draw must never be cached. */
    public static long casterFailures() { return casterFailures; }

    private ShadowRenderer()
    {}

    /** Call once at the start of a bake, before any begin*()/endPass(). Arms a
     *  fresh snapshot of the MC/Iris GL state on the first pass of this bake
     *  (see {@link #savePassState}), so the per-pass glGet* stalls collapse to
     *  one per bake. */
    public static void beginBake()
    {
        passStateSaved = false;
    }

    /**
     * Begin a spot depth pass into the live ({@code toStatic} false) or static
     * ({@code toStatic} true) atlas tile. {@code clear} false keeps the tile's
     * current depth — used for the dynamic-caster overlay drawn on top of a
     * static base just restored by {@link SpotlightDepthAtlas#copyStaticToLive}.
     */
    public static void beginSpot(int tile,
                                 double lpx, double lpy, double lpz,
                                 float ldx, float ldy, float ldz,
                                 float range, float outerDeg,
                                 boolean toStatic, boolean clear)
    {
        savePassState();

        // Light-relative bake anchor for this pass (see currentOrigin* docs):
        // A = round((float) lightPos) == BlockShadowCache's block snap, so the
        // shared pass anchor stays in lockstep with the cached block/cutout VBO.
        // eye = L - A keeps the full-precision sub-block light motion in the view
        // while the caster arms subtract A from their world coords.
        currentOriginX = Math.round((float) lpx);
        currentOriginY = Math.round((float) lpy);
        currentOriginZ = Math.round((float) lpz);
        float ex = (float) (lpx - currentOriginX);
        float ey = (float) (lpy - currentOriginY);
        float ez = (float) (lpz - currentOriginZ);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, SpotlightDepthAtlas.getFboId(toStatic));
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        int ts = SpotlightDepthAtlas.tileSizePx(tile);
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float fovDeg = Math.max(outerDeg, 1.0f);
        float far = Math.max(range, NEAR + 0.1f);
        if (fovDeg != spotProjFov || far != spotProjFar)
        {
            spotProj.identity().perspective((float) Math.toRadians(fovDeg), 1.0f, NEAR, far);
            spotProjFov = fovDeg;
            spotProjFar = far;
        }

        Vector3f up = pickStableUp(ldy);
        currentView.identity().lookAt(
            ex, ey, ez,
            ex + ldx, ey + ldy, ez + ldz,
            up.x, up.y, up.z
        );

        applyMatrices(spotProj);
    }

    /**
     * Narrow the current pass's scissor to a TILE-LOCAL depth-pixel rect
     * (partial-tile overlay draw: only the dynamic casters' region may be
     * written — the scissor is the HARD bound that keeps depth writes inside
     * the region the filters will re-run on, so an under-estimated caster
     * bbox degrades to visible silhouette clipping, never to stale
     * pyramid/EVSM content being sampled as fresh). Call between
     * {@link #beginSpot} (which set the full-tile scissor; the viewport — the
     * NDC mapping — stays full-tile) and the caster draws; {@link #endPass}
     * restores the caller's scissor as usual.
     */
    public static void restrictScissorSpot(int tile, int localX, int localY, int w, int h)
    {
        int px = SpotlightDepthAtlas.tilePixelX(tile);
        int py = SpotlightDepthAtlas.tilePixelY(tile);
        GL11.glScissor(px + localX, py + localY, w, h);
    }

    /** DIAGNOSTIC (-Dirlite.dynRectDebug=true): clear the CURRENTLY SCISSORED
     *  depth region to the near plane, making the partial-tile dyn rect show
     *  up in the world as a fully-shadowed block of the spot's light — a
     *  direct visual of where the rect actually lands relative to the caster
     *  it is supposed to cover. Call right after {@link #restrictScissorSpot}. */
    public static void debugFillScissoredDepth()
    {
        GL11.glClearDepth(0.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0);
    }

    /**
     * Begin a point-cube face depth pass into the live or static atlas (see
     * {@link #beginSpot} for the {@code toStatic}/{@code clear} semantics; the
     * static base of a whole block is restored by
     * {@link PointDepthAtlas#copyStaticToLive}). {@code block} is the GLOBAL
     * atlas block (the value published to vlParams.w); the face's pixel rect
     * inside the flat atlas comes from the PointDepthAtlas accessors. The
     * face switch below is the layout canon of
     * {@link PointDepthAtlas#FACE_COL}/{@link PointDepthAtlas#FACE_ROW}.
     */
    public static void beginPointFace(int block, int face,
                                      double lpx, double lpy, double lpz,
                                      float radius,
                                      boolean toStatic, boolean clear)
    {
        savePassState();

        // Light-relative bake anchor (see beginSpot / currentOrigin* docs).
        currentOriginX = Math.round((float) lpx);
        currentOriginY = Math.round((float) lpy);
        currentOriginZ = Math.round((float) lpz);
        float ex = (float) (lpx - currentOriginX);
        float ey = (float) (lpy - currentOriginY);
        float ez = (float) (lpz - currentOriginZ);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, PointDepthAtlas.getFboId(toStatic));
        int px = PointDepthAtlas.facePixelX(block, face);
        int py = PointDepthAtlas.facePixelY(block, face);
        int ts = PointDepthAtlas.faceSizePx(block);
        GL11.glViewport(px, py, ts, ts);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, ts, ts);
        if (clear)
        {
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }

        float far = Math.max(radius, NEAR + 0.1f);
        if (far != pointProjFar)
        {
            pointProj.identity().perspective((float) Math.toRadians(90.0), 1.0f, NEAR, far);
            pointProjFar = far;
        }

        float dx, dy, dz, ux, uy, uz;
        switch (face)
        {
            case 0:  dx =  1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // +X
            case 1:  dx = -1; dy =  0; dz =  0; ux = 0; uy = -1; uz =  0; break; // -X
            case 2:  dx =  0; dy =  1; dz =  0; ux = 0; uy =  0; uz =  1; break; // +Y
            case 3:  dx =  0; dy = -1; dz =  0; ux = 0; uy =  0; uz = -1; break; // -Y
            case 4:  dx =  0; dy =  0; dz =  1; ux = 0; uy = -1; uz =  0; break; // +Z
            default: dx =  0; dy =  0; dz = -1; ux = 0; uy = -1; uz =  0; break; // -Z
        }

        currentView.identity().lookAt(
            ex, ey, ez,
            ex + dx, ey + dy, ez + dz,
            ux, uy, uz
        );

        applyMatrices(pointProj);
    }

    // --- Batched caster pass (T2.2) ------------------------------------------
    // Casters used to flush one immediate.endBatch() EACH (the old renderCaster), so a
    // point light with N subjects paid up to 6N GPU flushes — once per caster per
    // cube face. Now a pass brackets its casters with
    //   beginCasterBatch() -> emitCaster()* -> endCasterBatch()
    // and flushes ONCE: the casters accumulate in the shared entity Immediate and
    // a single draw at the end submits them all, so a pass costs at most one flush
    // (one per face / per atlas tile) regardless of how many subjects it has.

    /** Open a batched caster pass. Pins the depth GL state once for the whole
     *  batch (each layer's RenderType.startDrawing sets the real state at flush
     *  time, but pin defensively, as the old per-caster path did) and enters
     *  baking mode so light-form renderers skip re-registration during the bake.
     *  Casters are emitted with {@link #emitCaster} and flushed by
     *  {@link #endCasterBatch}. No-op outside a begin*()/endPass() bracket. */
    public static void beginCasterBatch()
    {
        if (!inPass)
        {
            return;
        }
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        ShadowBakeState.setBaking(true);
    }

    /** Shared per-caster wrapper around {@link ShadowCasterSource#emitOccluder}.
     *  Owns the scratch reset and the per-caster exception + run isolation
     *  (INVARIANT 4): the source emits one caster's depth geometry into the shared
     *  Immediate (via {@link ImmediateOccluderBatch}); if it throws mid-build the
     *  batch is drained here — re-asserting the light matrices (INVARIANT 1) — so
     *  the broken caster's partial vertices can never fuse with the next caster's
     *  into a garbage quad. The source NEVER flushes or catches its own throw. */
    public static void emitCaster(ShadowCasterSource source, Object caster, int casterType, float tickDelta)
    {
        if (caster == null || !inPass)
        {
            return;
        }

        MultiBufferSource.BufferSource immediate = Minecraft.getInstance().renderBuffers().bufferSource();
        resetScratch();
        casterBatch.bind(immediate, scratch);
        casterBatch.mark();
        // INVARIANT 1 hardening (MAJOR-A): some caster forms self-draw SYNCHRONOUSLY
        // inside emitOccluder (BBS ModelForm via glDrawArrays; MobForm leaves the live
        // modelview at identity), reading the LIVE RenderSystem modelview AT EMIT TIME —
        // before the end-of-batch flush re-establishes it. A form emitted after a
        // modelview-corrupting caster would otherwise draw through a garbage transform
        // and cast a wrong/absent shadow (no GL error). Re-assert the clean light
        // view/proj before EVERY emit so each self-drawing form starts from the light
        // modelview. Harmless/idempotent for the buffered path (its draws ride the
        // scratch PoseStack until the single end-of-batch flush, which re-asserts the
        // same matrices). See irl-core/docs/shadow-caster-seam-spec.md INV-1 erratum.
        establishLightMatrices(currentView, currentProj);
        // MAJOR-A analog for NON-matrix GL state. beginCasterBatch pins the bake's
        // depth state ONCE per pass, but a self-drawing caster can mutate it mid-pass:
        // e.g. a BBS form item held by the local-player occluder renders through a BBS
        // FormRenderer that calls RenderSystem.disableDepthTest() and leaves blend on
        // (exactly as MorphRenderer does), so the caster emitted NEXT in the same pass
        // — notably a model block whose form self-draws via glDrawArrays — would bake
        // with depthTest OFF and land nothing in the depth map: its shadow silently
        // vanishes with no GL error (the same failure renderBlocksDepth dodges by
        // passing matrices explicitly). establishLightMatrices repairs the matrices but
        // not depthMask/depthTest/blend; re-pin them before EVERY emit so each caster
        // starts from the pinned bake state regardless of what the previous one left.
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        try
        {
            source.emitOccluder(caster, casterType, tickDelta, casterBatch);
        }
        catch (Throwable t)
        {
            casterFailures++;
            // The caster threw mid-build: terminate its run now (drain the batch,
            // re-asserting the light matrices) so its partial geometry ends here
            // instead of merging into the next caster's quads.
            casterBatch.terminateRun(currentView, currentProj);
        }
    }

    /** Close a batched caster pass: flush every buffered caster with one
     *  immediate.endBatch() and leave baking mode. */
    public static void endCasterBatch()
    {
        try
        {
            if (inPass)
            {
                flushCasterImmediate(Minecraft.getInstance().renderBuffers().bufferSource(), currentView, currentProj);
            }
        }
        finally
        {
            ShadowBakeState.setBaking(false);
        }
    }

    /** Drain the shared entity Immediate with a single draw, re-asserting the
     *  light's {@code view}/{@code proj} first (INVARIANT 1): the batched draw
     *  transforms its buffered world-space caster geometry by RenderSystem's LIVE
     *  modelview/projection, which a caster baked earlier in the batch can leave
     *  corrupted (a vanilla mob drawn through the EntityRenderer; the same
     *  corruption the block/cutout paths dodge by passing matrices explicitly to
     *  VertexBuffer.draw). The per-caster path got away without this because each
     *  caster flushed right after applyMatrices set the state; a single end-of-batch
     *  (or recovery) flush does not. Called by {@link #endCasterBatch} (success) and
     *  {@link ImmediateOccluderBatch#terminateRun} (per-caster recovery, INVARIANT 4). */
    static void flushCasterImmediate(MultiBufferSource.BufferSource immediate, Matrix4f view, Matrix4f proj)
    {
        establishLightMatrices(view, proj);
        try
        {
            immediate.endBatch();
        }
        catch (Throwable t)
        {
            casterFailures++;
            // swallow — a broken buffer must not abort the whole bake
        }
    }

    /** Re-assert the light's {@code view}/{@code proj} onto the live RenderSystem
     *  projection + modelview stack: reload the modelview-stack top to {@code view}
     *  (NO extra push — applyMatrices / endPass own the single push/pop) and restore
     *  the light projection. The shared matrix prologue used by
     *  {@link #flushCasterImmediate} (immediately before the batched draw, INVARIANT 1
     *  CLAUSE 1) and by {@link #emitCaster} (before each emitOccluder — the MAJOR-A
     *  hardening for forms that self-draw against the live modelview at emit time). */
    static void establishLightMatrices(Matrix4f view, Matrix4f proj)
    {
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        // 1.21: getModelViewStack() returns a JOML Matrix4fStack (loadIdentity ->
        // identity, multiplyPositionMatrix -> mul); applyModelViewMatrix still uploads it.
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.identity();
        mv.mul(view);
        RenderSystem.applyModelViewMatrix();
    }

    // --- Block-shadow batch draw (non-full-shape blocks within an active pass) ---

    private static boolean blockRenderErrorLogged = false;
    private static final RandomSource cutoutRandom = RandomSource.create();

    // --- Per-light block VBO cache (Stage B), keyed by LightRegistry.id ---
    // The block list from BlockShadowCache is referentially stable on a cache
    // hit (same List instance), so a reference compare detects a rebuild
    // perfectly. One VertexBuffer per lamp, built once and redrawn across all
    // 6 cube faces (point) / the single atlas tile (spot). Static lamps
    // re-upload nothing. Evicted by retainBlockVbos when the lamp disappears.
    private static final Long2ObjectOpenHashMap<VertexBuffer> blockVboById = new Long2ObjectOpenHashMap<>();
    // A matching list with a null VBO caches "no shaped entries" as well.
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> blockVboListById = new Long2ObjectOpenHashMap<>();

    // --- Per-light cutout block VBO cache (T2.3), keyed by LightRegistry.id ---
    // Cutout blocks (doors, leaves, glass panes, iron bars) were re-tessellated
    // through brm.renderBlock on every static bake — and once PER CUBE FACE for
    // point lights, so a point lamp paid 6x that CPU cost each bake. Capture them
    // once into a textured VBO per render layer (CUTOUT / CUTOUT_MIPPED), keyed
    // by id + the referentially-stable block-list instance, then redraw the VBO
    // under that layer's own cutout shader + block atlas (alpha-discard intact)
    // for every face / tile. Rebuilt only when the list instance changes; evicted
    // alongside the opaque VBOs in retainBlockVbos. NOTE: the captured atlas UVs
    // would go stale on a resource-pack reload that repacks the atlas without any
    // block change — rare, out of the perf scope, and recovered by any later edit.
    private static final class CutoutVbos
    {
        VertexBuffer cutout;        // blocks on RenderType.cutout()
        VertexBuffer cutoutMipped;  // blocks on RenderType.cutoutMipped()

        void close()
        {
            if (cutout != null) { try { cutout.close(); } catch (Throwable ignored) {} cutout = null; }
            if (cutoutMipped != null) { try { cutoutMipped.close(); } catch (Throwable ignored) {} cutoutMipped = null; }
        }
    }

    private static final Long2ObjectOpenHashMap<CutoutVbos> cutoutVboById = new Long2ObjectOpenHashMap<>();
    // A matching list with null VBOs caches "no cutout entries" as well.
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> cutoutVboListById = new Long2ObjectOpenHashMap<>();

    /**
     * Render a light's non-full-cube blocks into the currently-bound depth FBO,
     * between a begin*()/endPass() bracket and after the entity casters. Two
     * paths: cutout blocks bake from their textured BakedModel (alpha-test
     * shader drops transparent texels), opaque shapes draw as a cached
     * POSITION-only quad-box VBO decomposed from their VoxelShape AABBs.
     */
    public static void renderBlocksDepth(long id, List<BlockShadowEntry> blocks)
    {
        if (blocks == null || blocks.isEmpty() || !inPass)
        {
            return;
        }

        // Cutout blocks first (their own textured pass), then opaque AABBs.
        renderBlocksDepthCutout(id, blocks);

        VertexBuffer vb = blockVboById.get(id);
        boolean sameBlocks = blockVboListById.get(id) == blocks;
        if (!sameBlocks)
        {
            boolean anyShape = false;
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e != null && e.shape != null)
                {
                    anyShape = true;
                    break;
                }
            }
            if (!anyShape)
            {
                // Cache the absence too: point faces must not rescan a cutout-
                // only list six times. Keep a map entry so normal eviction owns it.
                releaseBlockVbo(id);
                blockVboById.put(id, null);
                blockVboListById.put(id, blocks);
                return;
            }
        }
        else if (vb == null)
        {
            return;
        }

        ShadowBakeState.setBaking(true);
        boolean disabledCull = false;
        try
        {
            // Culling OFF: both faces of every quad write depth and the depth test
            // keeps the nearest (light-facing) surface — the true occluder — so
            // blocks cast tight, correct shadows (matches the proven IRLEngine bake).
            RenderSystem.disableCull();
            disabledCull = true;
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShader(GameRenderer::getPositionShader);

            // Rebuild only when the list instance changed (BlockShadowCache
            // returns the same instance on a hit) — static lamps just redraw.
            if (!sameBlocks)
            {
                if (vb != null)
                {
                    try { vb.close(); } catch (Throwable ignored) {}
                }
                vb = buildBlockVbo(blocks);
                blockVboById.put(id, vb);
                blockVboListById.put(id, blocks);
            }

            if (vb != null)
            {
                vb.bind();
                // Draw with the LIGHT's own view/proj, NOT RenderSystem's live
                // matrices. A caster baked earlier in this pass — notably a model
                // block whose form is a vanilla mob, drawn through the vanilla
                // EntityRenderer — can leave RenderSystem's modelview changed; the
                // block batch would then transform wrong and land nothing in the
                // depth map, so the light's block shadows silently vanished (no GL
                // error). currentView/currentProj are set in applyMatrices.
                vb.drawWithShader(currentView, currentProj, RenderSystem.getShader());
                VertexBuffer.unbind();
            }
        }
        catch (Throwable t)
        {
            if (!blockRenderErrorLogged)
            {
                blockRenderErrorLogged = true;
                System.err.println("[irlite] renderBlocksDepth failed: " + t);
                t.printStackTrace();
            }
            // Drop the (possibly broken) VBO so the next frame retries clean.
            releaseBlockVbo(id);
        }
        finally
        {
            if (disabledCull)
            {
                restoreCullState();
            }
            ShadowBakeState.setBaking(false);
        }
    }

    /** Build a STATIC POSITION VBO from a block list's shaped entries. Only
     *  called when at least one entry has a shape, so the buffer is non-empty. */
    private static VertexBuffer buildBlockVbo(List<BlockShadowEntry> blocks)
    {
        // 1.21: begin() moved to Tesselator and returns the builder.
        BufferBuilder buf = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

        QuadBoxConsumer consumer = new QuadBoxConsumer(buf);
        // Light-relative bake: the consumer subtracts the pass anchor in double
        // before its float cast. The anchor A == BlockShadowCache's snap, so it is
        // stable for the life of this cached VBO (rebuilt only when the block list
        // instance — hence A — changes), keeping the VBO consistent with the eye.
        consumer.originX = currentOriginX;
        consumer.originY = currentOriginY;
        consumer.originZ = currentOriginZ;
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry entry = blocks.get(i);
            if (entry == null || entry.shape == null)
            {
                continue;
            }
            consumer.ox = entry.pos.getX();
            consumer.oy = entry.pos.getY();
            consumer.oz = entry.pos.getZ();
            entry.shape.forAllBoxes(consumer);
        }

        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(buf.buildOrThrow());
        VertexBuffer.unbind();
        return vb;
    }

    /** Free one lamp's cached block VBO. */
    public static void releaseBlockVbo(long id)
    {
        VertexBuffer vb = blockVboById.remove(id);
        if (vb != null)
        {
            try { vb.close(); } catch (Throwable ignored) {}
        }
        blockVboListById.remove(id);
    }

    /** Free block VBOs for lamps not in {@code liveIds} (gone, or feature off
     *  -> empty set drains all). Run once per bake after the light loops. */
    public static void retainBlockVbos(LongSet liveIds)
    {
        if (!blockVboById.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<VertexBuffer>> it = blockVboById.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                Long2ObjectMap.Entry<VertexBuffer> me = it.next();
                if (!liveIds.contains(me.getLongKey()))
                {
                    VertexBuffer vb = me.getValue();
                    if (vb != null)
                    {
                        try { vb.close(); } catch (Throwable ignored) {}
                    }
                    blockVboListById.remove(me.getLongKey());
                    it.remove();
                }
            }
        }

        // Same eviction for the cutout VBOs (a lamp with only cutout blocks has
        // no opaque VBO, so this can't be folded into the loop above).
        if (!cutoutVboById.isEmpty())
        {
            ObjectIterator<Long2ObjectMap.Entry<CutoutVbos>> it = cutoutVboById.long2ObjectEntrySet().iterator();
            while (it.hasNext())
            {
                Long2ObjectMap.Entry<CutoutVbos> me = it.next();
                if (!liveIds.contains(me.getLongKey()))
                {
                    CutoutVbos v = me.getValue();
                    if (v != null)
                    {
                        v.close();
                    }
                    cutoutVboListById.remove(me.getLongKey());
                    it.remove();
                }
            }
        }
    }

    // Cutout blocks bake from their textured BakedModel through vanilla's
    // alpha-test cutout shader so transparent texture pixels (door glass, iron
    // grates, ladder gaps, leaves) let light pass through. The tessellated
    // geometry is cached per light in a VBO per render layer (T2.3) and redrawn
    // each face / tile under that layer's cutout shader: startDrawing binds the
    // block atlas to Sampler0 and sets the cutout(_mipped) shader whose FS does
    // the alpha discard; colour writes land on no attachment in our depth-only
    // FBO, so only depth is recorded.
    private static void renderBlocksDepthCutout(long id, List<BlockShadowEntry> blocks)
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world == null)
        {
            return;
        }

        BlockRenderDispatcher brm = mc.getBlockRenderer();
        if (brm == null)
        {
            return;
        }

        CutoutVbos vbos = cutoutVboById.get(id);
        boolean sameBlocks = cutoutVboListById.get(id) == blocks;
        if (!sameBlocks)
        {
            boolean any = false;
            for (int i = 0, n = blocks.size(); i < n; i++)
            {
                BlockShadowEntry e = blocks.get(i);
                if (e != null && e.cutout)
                {
                    any = true;
                    break;
                }
            }
            if (!any)
            {
                releaseCutoutVbos(id);
                cutoutVboById.put(id, null);
                cutoutVboListById.put(id, blocks);
                return;
            }
        }
        else if (vbos == null)
        {
            return;
        }

        // Tessellate once per (id, list instance); reuse across all 6 cube faces
        // / the single atlas tile and across static bakes while the list is
        // stable (BlockShadowCache returns the same instance until a block in
        // range changes), so a static lamp re-tessellates nothing.
        if (!sameBlocks)
        {
            if (vbos != null)
            {
                vbos.close();
            }
            ShadowBakeState.setBaking(true);
            try
            {
                vbos = buildCutoutVbos(blocks, world, brm);
            }
            catch (Throwable t)
            {
                if (!blockRenderErrorLogged)
                {
                    blockRenderErrorLogged = true;
                    System.err.println("[irlite] buildCutoutVbos failed: " + t);
                    t.printStackTrace();
                }
                releaseCutoutVbos(id);
                return;
            }
            finally
            {
                ShadowBakeState.setBaking(false);
            }
            cutoutVboById.put(id, vbos);
            cutoutVboListById.put(id, blocks);
        }

        // Redraw the cached VBOs. vb.draw is handed the LIGHT's own view/proj
        // explicitly, so — unlike the old immediate.draw path — it never reads
        // RenderSystem's live modelview, which a vanilla-mob caster baked earlier
        // in this pass can leave corrupted (that was the original "a second cutout
        // layer makes the others' shadows vanish" bug; gone here by construction).
        try
        {
            drawCutoutVbo(RenderType.cutout(), vbos.cutout);
            drawCutoutVbo(RenderType.cutoutMipped(), vbos.cutoutMipped);
        }
        catch (Throwable t)
        {
            if (!blockRenderErrorLogged)
            {
                blockRenderErrorLogged = true;
                System.err.println("[irlite] drawCutoutVbo failed: " + t);
                t.printStackTrace();
            }
        }
    }

    /** Tessellate a light's cutout blocks into one STATIC textured VBO per
     *  cutout render layer (CUTOUT / CUTOUT_MIPPED). */
    private static CutoutVbos buildCutoutVbos(List<BlockShadowEntry> blocks, ClientLevel world, BlockRenderDispatcher brm)
    {
        CutoutVbos out = new CutoutVbos();
        out.cutout = buildCutoutLayerVbo(blocks, world, brm, RenderType.cutout());
        out.cutoutMipped = buildCutoutLayerVbo(blocks, world, brm, RenderType.cutoutMipped());
        return out;
    }

    /** Tessellate the cutout blocks that map to ONE render layer into a STATIC
     *  VBO in that layer's textured vertex format, exactly as the old immediate
     *  path did (cull on, per-block render seed). Returns null if no block maps
     *  to this layer or they all tessellate to nothing (e.g. fully neighbour-
     *  culled), so the redraw skips a missing layer. */
    private static VertexBuffer buildCutoutLayerVbo(List<BlockShadowEntry> blocks, ClientLevel world, BlockRenderDispatcher brm, RenderType layer)
    {
        // 1.21: begin() moved to Tesselator and returns the builder.
        BufferBuilder buf = Tesselator.getInstance()
            .begin(layer.mode(), layer.format());

        resetScratch();
        PoseStack stack = scratch;
        for (int i = 0, n = blocks.size(); i < n; i++)
        {
            BlockShadowEntry entry = blocks.get(i);
            if (entry == null || !entry.cutout)
            {
                continue;
            }
            BlockPos p = entry.pos;
            BlockState state = world.getBlockState(p);
            if (state.isAir())
            {
                continue;
            }

            RenderType bl;
            try
            {
                bl = ItemBlockRenderTypes.getChunkRenderType(state);
            }
            catch (Throwable t)
            {
                continue;
            }
            if (bl != layer)
            {
                continue;
            }

            stack.pushPose();
            // Light-relative bake: int block coord minus the double pass anchor is
            // a small double, cast to float exactly inside PoseStack.translate.
            stack.translate(p.getX() - currentOriginX, p.getY() - currentOriginY, p.getZ() - currentOriginZ);
            cutoutRandom.setSeed(state.getSeed(p));
            try
            {
                brm.renderBatched(state, p, world, stack, buf, true, cutoutRandom);
            }
            catch (Throwable t)
            {
                // skip a single broken block, keep tessellating the rest
            }
            stack.popPose();
        }

        MeshData built = buf.build();
        if (built == null)
        {
            return null; // nothing emitted for this layer
        }
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(built);
        VertexBuffer.unbind();
        return vb;
    }

    /** Draw one cached cutout layer VBO under that layer's render state.
     *  startDrawing/endDrawing replicate exactly what immediate.draw uses for
     *  the layer — cutout shader, block atlas on Sampler0, blend off, cull on —
     *  so transparent texels still discard; only the light's own view/proj reach
     *  the shader (corruption-proof). The depth-only FBO drops the colour writes. */
    private static void drawCutoutVbo(RenderType layer, VertexBuffer vb)
    {
        if (vb == null)
        {
            return;
        }
        layer.setupRenderState();
        try
        {
            vb.bind();
            vb.drawWithShader(currentView, currentProj, RenderSystem.getShader());
            VertexBuffer.unbind();
        }
        finally
        {
            layer.clearRenderState();
        }
    }

    /** Free one lamp's cached cutout VBOs. */
    private static void releaseCutoutVbos(long id)
    {
        CutoutVbos v = cutoutVboById.remove(id);
        if (v != null)
        {
            v.close();
        }
        cutoutVboListById.remove(id);
    }

    /**
     * Reusable forEachBox consumer that emits one block-aligned VoxelShape AABB
     * as 6 world-space quads. The block origin (ox, oy, oz) is mutated per
     * block; the shape's box coords are local (0..1). Winding is irrelevant —
     * culling is disabled, so both faces of each quad write depth.
     */
    private static final class QuadBoxConsumer implements DoubleLineConsumer
    {
        final BufferBuilder buf;
        int ox, oy, oz;
        /** Light-relative bake anchor (= ShadowRenderer.currentOrigin* when the
         *  VBO was built), subtracted in double before the float cast so the
         *  emitted quad coords stay sub-block-magnitude far from the origin. */
        double originX, originY, originZ;

        QuadBoxConsumer(BufferBuilder buf)
        {
            this.buf = buf;
        }

        @Override
        public void consume(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ)
        {
            float x1 = (float) (ox - originX + minX);
            float y1 = (float) (oy - originY + minY);
            float z1 = (float) (oz - originZ + minZ);
            float x2 = (float) (ox - originX + maxX);
            float y2 = (float) (oy - originY + maxY);
            float z2 = (float) (oz - originZ + maxZ);

            // -X  (1.21: VertexConsumer.next() removed — each vertex() starts a new vertex)
            buf.addVertex(x1, y1, z1);
            buf.addVertex(x1, y1, z2);
            buf.addVertex(x1, y2, z2);
            buf.addVertex(x1, y2, z1);
            // +X
            buf.addVertex(x2, y1, z2);
            buf.addVertex(x2, y1, z1);
            buf.addVertex(x2, y2, z1);
            buf.addVertex(x2, y2, z2);
            // -Y
            buf.addVertex(x1, y1, z2);
            buf.addVertex(x1, y1, z1);
            buf.addVertex(x2, y1, z1);
            buf.addVertex(x2, y1, z2);
            // +Y
            buf.addVertex(x1, y2, z1);
            buf.addVertex(x1, y2, z2);
            buf.addVertex(x2, y2, z2);
            buf.addVertex(x2, y2, z1);
            // -Z
            buf.addVertex(x2, y1, z1);
            buf.addVertex(x1, y1, z1);
            buf.addVertex(x1, y2, z1);
            buf.addVertex(x2, y2, z1);
            // +Z
            buf.addVertex(x1, y1, z2);
            buf.addVertex(x2, y1, z2);
            buf.addVertex(x2, y2, z2);
            buf.addVertex(x1, y2, z2);
        }
    }

    public static void endPass()
    {
        if (!inPass)
        {
            return;
        }

        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProj, savedSorter);

        GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);
        restoreCullState();

        if (savedScissorEnabled)
        {
            GL11.glScissor(savedScissorBox[0], savedScissorBox[1], savedScissorBox[2], savedScissorBox[3]);
        }
        else
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);

        inPass = false;
    }

    private static void savePassState()
    {
        // The saved state is the original MC/Iris GL state, which every endPass
        // restores — so it is invariant across all passes of one bake. Snapshot
        // it only on the first pass (the glGet* are CPU<->GPU sync points; up to
        // 64 spots + 18*6 point faces = ~172 passes would otherwise issue ~5
        // each). beginBake() re-arms it for the next bake.
        inPass = true;
        if (passStateSaved)
        {
            return;
        }

        savedFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
        savedScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (savedScissorEnabled)
        {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, savedScissorBox);
        }
        savedProj = RenderSystem.getProjectionMatrix();
        savedSorter = RenderSystem.getVertexSorting();
        // BBS model/BOBJ renderers may change culling while they are replayed as
        // shadow casters. Merely re-enabling culling is insufficient: a leaked
        // GL_FRONT selector (or flipped winding) makes the following world pass
        // show only the inside faces of every model. Preserve the complete state.
        savedCullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        savedCullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        savedFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);

        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer maskBuf = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, maskBuf);
            savedMaskR = maskBuf.get(0) != 0;
            savedMaskG = maskBuf.get(1) != 0;
            savedMaskB = maskBuf.get(2) != 0;
            savedMaskA = maskBuf.get(3) != 0;
        }

        passStateSaved = true;
    }

    private static void restoreCullState()
    {
        GL11.glCullFace(savedCullFace);
        GL11.glFrontFace(savedFrontFace);

        if (savedCullEnabled)
        {
            RenderSystem.enableCull();
        }
        else
        {
            RenderSystem.disableCull();
        }
    }

    private static void applyMatrices(Matrix4f proj)
    {
        GL11.glColorMask(true, true, true, true);

        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        currentProj.set(proj);

        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.identity();
        mvStack.mul(currentView);
        RenderSystem.applyModelViewMatrix();
    }

    private static Vector3f pickStableUp(float dy)
    {
        return Math.abs(dy) > 0.99f ? UP_Z : UP_Y;
    }

    /** Drain the shared {@link #scratch} back to its single root entry (popping
     *  anything a thrown caster left pushed — {@code isEmpty()} is true at size
     *  1) and reset that entry to identity, so each use starts clean. */
    private static void resetScratch()
    {
        while (!scratch.clear())
        {
            scratch.popPose();
        }
        scratch.setIdentity();
    }
}
