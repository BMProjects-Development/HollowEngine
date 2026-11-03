package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * EVSM4 prefilter of the LIVE spot depth atlas (F2a of the shadow filtering
 * refactor). RGBA32F, base level = atlas &gt;&gt; shift (one texel = the averaged
 * warped moments of a 2^shift x 2^shift depth quad; shift 1 normally, 2 at
 * ULTRA — {@link SpotlightDepthAtlas#evsmShift}), Gaussian-blurred base +
 * averaged mip chain down to one texel per tile. The inject samples it as
 * {@code irl_spotEvsm} with one trilinear textureLod (lod picked from the
 * PCSS penumbra estimate) and a Chebyshev bound — replacing the whole PCF
 * loop for wide penumbras with a deterministic, grain-free filter. The
 * ratio-aware GLSL derives the shift per fetch from textureSize (packs with
 * the older strict atlas/2 size gate fall back to PCF at ULTRA, never to
 * garbage).
 *
 * Moment warp CONTRACT (mirrored byte-for-byte by the GLSL Chebyshev branch):
 *   wp = exp(+42 * lz), wn = -exp(-8 * lz), where lz = (dist - near)/(far - near)
 *   is the LINEAR depth (near 0.05, far = the tile's light range, passed per
 *   tile at markDirty time). Linear metric per Lauritzen: warping the raw
 *   perspective z01 collapses to plain VSM at far receivers (bleeding) and
 *   makes the variance floor grow quadratically with distance. Channels =
 *   (E[wp], E[wp^2], E[wn], E[wn^2]).
 *
 * Same batched contract as SpotShadowPyramid: markDirty per baked tile,
 * flushDirty once after the spot loop. The raw depth atlas stays untouched —
 * the blocker search, the contact-PCF branch and the volumetrics keep reading
 * it; EVSM is additive. Blur/mip passes clamp their reads inside the tile
 * region, so ANY quadtree tile region (full, half or quarter cell — see the
 * SpotlightDepthAtlas layout contract) stays gutter-free; the GLSL side
 * clamps its UV half a coarse-mip texel inside the tile before the
 * trilinear fetch.
 */
public final class SpotShadowEvsm
{
    private static int texId = 0;      // EVSM atlas: base = atlas >> shift, levels = log2(tileSize) - shift + 1
    private static int tempId = 0;     // one tile region (tileSize >> shift)^2, blur H output / V input
    /** Raw (unblurred) reduce output, one tile region like {@link #tempId}.
     *  The chain always runs reduce->scratch, H: scratch->temp, V: temp->mip —
     *  the mip levels never hold raw moments, so a partial-rect pass leaves
     *  every out-of-rect mip texel byte-stable (an in-place convert would
     *  smear blur-of-blur along the rect edge instead). Full-tile frames pay
     *  nothing: it is the same three dispatches the in-place pipeline used. */
    private static int scratchId = 0;
    private static int levels = 0;
    private static int progConvert = 0, progBlur = 0, progMip = 0;
    private static int uCvSrc, uCvFar, uCvStep, uCvSrcOrigin, uCvDstOrigin, uCvDstSize;
    private static int uBlSrc, uBlSrcLod, uBlHorizontal, uBlSrcOrigin, uBlDstOrigin, uBlSize, uBlClampMax;
    private static int uMpSrc, uMpSrcLod, uMpSrcOrigin, uMpDstOrigin, uMpDstSize;
    private static boolean programsAttempted = false;
    /** One bit per flat atlas tile (1L << tile); long because the quadtree
     *  layout allows up to 64 tiles (guarded in SpotlightDepthAtlas). */
    private static long dirtyMask = 0L;
    /** Per-tile dirty sub-rect in TILE-LOCAL depth pixels ({@link ShadowRect};
     *  FULL = whole region). Snapshot + reset strictly together with
     *  {@link #dirtyMask} in {@link #flushDirty} (see SpotShadowPyramid). */
    private static final long[] dirtyRect = new long[SpotlightDepthAtlas.tileCount()];
    private static final long[] flushRect = new long[SpotlightDepthAtlas.tileCount()];
    private static final float[] tileFar = new float[SpotlightDepthAtlas.tileCount()]; // light range per dirty tile (linear depth metric)

    private static final String COMMON =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8) in;\n" +
        "layout(rgba32f, binding = 0) uniform writeonly image2D dst;\n";

    // depth NxN -> warped moments (averaging moments IS the correct box prefilter: they are linear
    // statistics); N = srcStep = 2^evsmShift (2 normally, 4 at ULTRA's atlas/4 base). Depth is
    // linearized per tile (near 0.05, far = light range) before the warp — see the class contract
    private static final String CONVERT_SRC = COMMON +
        "uniform sampler2D srcDepth;\n" +
        "uniform float far;\n" +
        "uniform int srcStep;\n" +
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    ivec2 s = srcOrigin + g * srcStep;\n" +
        "    vec4 m = vec4(0.0);\n" +
        "    for (int j = 0; j < srcStep; j++)\n" +
        "    for (int i = 0; i < srcStep; i++)\n" +
        "    {\n" +
        "        float z01 = texelFetch(srcDepth, s + ivec2(i, j), 0).r;\n" +
        "        float ndcZ = z01 * 2.0 - 1.0;\n" +
        "        float dist = (2.0 * far * 0.05) / max((far + 0.05) - ndcZ * (far - 0.05), 1e-6);\n" +
        "        float lz = clamp((dist - 0.05) / (far - 0.05), 0.0, 1.0);\n" +
        "        float wp = exp(42.0 * lz);\n" +
        "        float wn = -exp(-8.0 * lz);\n" +
        "        m += vec4(wp, wp * wp, wn, wn * wn);\n" +
        "    }\n" +
        "    imageStore(dst, dstOrigin + g, m / float(srcStep * srcStep));\n" +
        "}\n";

    // separable Gaussian 1-4-6-4-1; reads clamp to [0, clampMax] in SOURCE-local
    // coords (scratch/temp extents — which coincide with the tile region edge on
    // full-tile passes, so there is still no cross-tile bleed). The dispatch
    // rect ("size", the written extent) is DECOUPLED from the clamp bound: a
    // partial V pass writes a rect inset inside the scratch extents and must
    // read its 2-texel apron beyond the write rect instead of clamping at it.
    // Runs on EVERY mip level after its downsample so the filter width
    // actually doubles per lod.
    private static final String BLUR_SRC = COMMON +
        "uniform sampler2D srcTex;\n" +
        "uniform int srcLod;\n" +               // always 0 in the scratch pipeline (scratch/temp are single-level)
        "uniform int horizontal;\n" +           // 1: read scratch (raw), write temp; 0: read temp, write EVSM mip (absolute)
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 size;\n" +               // written rect extent at this lod
        "uniform ivec2 clampMax;\n" +           // inclusive source-local clamp bound (source extents - 1)
        "const float W[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= size.x || g.y >= size.y) return;\n" +
        "    vec4 acc = vec4(0.0);\n" +
        "    for (int k = -2; k <= 2; k++)\n" +
        "    {\n" +
        "        ivec2 o = (horizontal == 1) ? ivec2(g.x + k, g.y) : ivec2(g.x, g.y + k);\n" +
        "        acc += W[k + 2] * texelFetch(srcTex, clamp(srcOrigin + o, ivec2(0), clampMax), srcLod);\n" +
        "    }\n" +
        "    imageStore(dst, dstOrigin + g, acc);\n" +
        "}\n";

    // 2x2 average mip down (moments stay valid under any linear filter)
    private static final String MIP_SRC = COMMON +
        "uniform sampler2D srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform ivec2 srcOrigin;\n" +
        "uniform ivec2 dstOrigin;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    ivec2 s = srcOrigin + g * 2;\n" +
        "    vec4 m = texelFetch(srcTex, s,               srcLod)\n" +
        "           + texelFetch(srcTex, s + ivec2(1, 0), srcLod)\n" +
        "           + texelFetch(srcTex, s + ivec2(0, 1), srcLod)\n" +
        "           + texelFetch(srcTex, s + ivec2(1, 1), srcLod);\n" +
        "    imageStore(dst, dstOrigin + g, m * 0.25);\n" +
        "}\n";

    private SpotShadowEvsm()
    {}

    /** Lazy — 0 until the first flush; bound by name via the host mod's sampler mixin. */
    public static int getGlTextureId()
    {
        return texId;
    }

    static boolean readyForReuse() { return texId != 0 && progConvert != 0 && progBlur != 0 && progMip != 0; }

    /** range = the light's far plane, needed to linearize depth before the warp. */
    public static void markDirty(int tile, float range)
    {
        if (tile >= 0 && tile < SpotlightDepthAtlas.tileCount())
        {
            dirtyMask |= 1L << tile;
            dirtyRect[tile] = ShadowRect.FULL;
            tileFar[tile] = Math.max(range, 0.1f);
        }
    }

    /** Partial-tile variant: only the given TILE-LOCAL depth-pixel rect of the
     *  live depth changed. Unions with any rect already marked this frame;
     *  {@link #markDirty} forces FULL. */
    public static void markDirtyRect(int tile, long rect, float range)
    {
        if (tile < 0 || tile >= SpotlightDepthAtlas.tileCount())
        {
            return;
        }
        dirtyRect[tile] = (dirtyMask & (1L << tile)) != 0 ? ShadowRect.union(dirtyRect[tile], rect) : rect;
        dirtyMask |= 1L << tile;
        tileFar[tile] = Math.max(range, 0.1f);
    }

    /** Convert + blur + mip every dirty tile in one batch. Call once per bake,
     *  after the spot loop (the pyramid flush pattern). */
    public static void flushDirty()
    {
        if (dirtyMask == 0L)
        {
            return;
        }
        long mask = dirtyMask;
        dirtyMask = 0L;
        System.arraycopy(dirtyRect, 0, flushRect, 0, dirtyRect.length);
        int depthTex = SpotlightDepthAtlas.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensureResources();
        if (texId == 0 || progConvert == 0 || progBlur == 0 || progMip == 0)
        {
            return; // inert on failure — the GLSL textureSize gate falls back to the PCF path
        }

        ShadowBakeProbe probe = ShadowEngine.bakeProbe();
        if (probe != null)
        {
            probe.counter("evsm.sp", Long.bitCount(mask));
        }
        // Apron/deep-lod cost attribution (probe-gated emission; the adds are
        // noise). All in region-local texels, x3 = the three passes per lod:
        //   pxact  = dispatched footprint (reduce S + H S + V W),
        //   pxcore = 3*C per lod (zero-width aprons THIS lod, propagation kept),
        //   pxideal= 3*I per lod (C_L0 halved with no growth at all).
        // apron share = (act-core)/act, propagation share = (core-ideal)/act.
        long pxAct = 0, pxCore = 0, pxIdeal = 0;

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int prevImgName = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 0);
        int prevImgLevel = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, 0);
        int prevImgLayered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, 0);
        int prevImgLayer = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, 0);
        int prevImgAccess = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 0);
        int prevImgFormat = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, 0);

        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // All tile rects come from the SpotlightDepthAtlas accessors — the one
        // rect source of the quadtree layout. INVARIANT (power-of-two
        // subdivision): every tile origin is a multiple of its own tileSizePx,
        // so shifting origin and size right in lockstep is exact at every lod.
        // shift = the EVSM base's extra right-shift below the depth atlas
        // (1 = atlas/2, 2 = ULTRA's atlas/4); a shift change deletes the
        // textures (setEvsmShift), so the allocation always matches it.
        //
        // SCRATCH PIPELINE, per tile per lod (identical for FULL and rect):
        //   reduce (depth convert at lod 0 / mip downsample deeper) -> scratch
        //   H blur: scratch -> temp,  V blur: temp -> EVSM mip (write rect W).
        // Rects, all in region-local lod-L texels: W = affected outputs =
        // changed-content rect C grown by the Gaussian radius 2; S = W grown
        // by 2 more = the raw support H/V read (clamped to the region, where
        // the scratch edge coincides with the tile edge and the kernel clamp
        // reproduces the old cross-tile-bleed-free behavior byte for byte).
        // Next level's C = W halved (floor/ceil) — the recurrence keeps every
        // out-of-rect mip texel untouched while covering every affected one.
        int shift = SpotlightDepthAtlas.evsmShift();
        for (int tile = 0; tile < SpotlightDepthAtlas.tileCount(); tile++)
        {
            if ((mask & (1L << tile)) == 0L)
            {
                continue;
            }
            int pixX = SpotlightDepthAtlas.tilePixelX(tile);
            int pixY = SpotlightDepthAtlas.tilePixelY(tile);
            int ts = SpotlightDepthAtlas.tileSizePx(tile);
            if (ts >> shift == 0)
            {
                continue; // degenerate sub-tile smaller than the base step — nothing to filter
            }

            // Changed-content rect at lod 0, region-local texels (depth >> shift).
            long r = flushRect[tile];
            int cx0, cy0, cx1, cy1;
            int region0 = ts >> shift;
            if (r == ShadowRect.FULL)
            {
                cx0 = 0; cy0 = 0; cx1 = region0; cy1 = region0;
            }
            else
            {
                int step = 1 << shift;
                cx0 = Math.min(region0 - 1, ShadowRect.x0(r) / step);
                cy0 = Math.min(region0 - 1, ShadowRect.y0(r) / step);
                cx1 = Math.min(region0, (ShadowRect.x1(r) + step - 1) / step);
                cy1 = Math.min(region0, (ShadowRect.y1(r) + step - 1) / step);
            }
            int ix0 = cx0, iy0 = cy0, ix1 = cx1, iy1 = cy1; // ideal (growth-free) chain

            for (int lod = 0; lod < levels; lod++)
            {
                int region = ts >> (lod + shift);
                if (region == 0)
                {
                    break; // past this sub-tile's chain end (see the levels formula)
                }
                // W = outputs to rewrite (C + blur radius 2); S = raw support (W + 2).
                int wx0 = Math.max(0, cx0 - 2), wy0 = Math.max(0, cy0 - 2);
                int wx1 = Math.min(region, cx1 + 2), wy1 = Math.min(region, cy1 + 2);
                int sx0 = Math.max(0, wx0 - 2), sy0 = Math.max(0, wy0 - 2);
                int sx1 = Math.min(region, wx1 + 2), sy1 = Math.min(region, wy1 + 2);
                int sW = sx1 - sx0, sH = sy1 - sy0;
                int wW = wx1 - wx0, wH = wy1 - wy0;
                int regOrigX = pixX >> (lod + shift), regOrigY = pixY >> (lod + shift);

                pxAct += (long) sW * sH * 2L + (long) wW * wH;
                pxCore += (long) (cx1 - cx0) * (cy1 - cy0) * 3L;
                pxIdeal += (long) (ix1 - ix0) * (iy1 - iy0) * 3L;

                // reduce -> scratch (raw moments, scratch-local at (0,0))
                if (lod == 0)
                {
                    GlStateManager._glUseProgram(progConvert);
                    GL20.glUniform1i(uCvSrc, unit);
                    GL20.glUniform1f(uCvFar, tileFar[tile]);
                    GL20.glUniform1i(uCvStep, 1 << shift);
                    GlStateManager._bindTexture(depthTex);
                    GL42.glBindImageTexture(0, scratchId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
                    GL20.glUniform2i(uCvSrcOrigin, pixX + (sx0 << shift), pixY + (sy0 << shift));
                    GL20.glUniform2i(uCvDstOrigin, 0, 0);
                    GL20.glUniform2i(uCvDstSize, sW, sH);
                }
                else
                {
                    GlStateManager._glUseProgram(progMip);
                    GL20.glUniform1i(uMpSrc, unit);
                    GlStateManager._bindTexture(texId);
                    GL20.glUniform1i(uMpSrcLod, lod - 1);
                    GL42.glBindImageTexture(0, scratchId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
                    GL20.glUniform2i(uMpSrcOrigin, (regOrigX << 1) + (sx0 << 1), (regOrigY << 1) + (sy0 << 1));
                    GL20.glUniform2i(uMpDstOrigin, 0, 0);
                    GL20.glUniform2i(uMpDstSize, sW, sH);
                }
                GL43.glDispatchCompute((sW + 7) / 8, (sH + 7) / 8, 1);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

                // H: scratch -> temp over the whole S rect (edge H outputs whose
                // kernel clamps at an interior S edge are never consumed: V only
                // reads columns of W, inset >= 2 from S's x-edges by construction)
                GlStateManager._glUseProgram(progBlur);
                GL20.glUniform1i(uBlSrc, unit);
                GL20.glUniform1i(uBlSrcLod, 0);
                GL20.glUniform2i(uBlClampMax, sW - 1, sH - 1);
                GlStateManager._bindTexture(scratchId);
                GL42.glBindImageTexture(0, tempId, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
                GL20.glUniform1i(uBlHorizontal, 1);
                GL20.glUniform2i(uBlSrcOrigin, 0, 0);
                GL20.glUniform2i(uBlDstOrigin, 0, 0);
                GL20.glUniform2i(uBlSize, sW, sH);
                GL43.glDispatchCompute((sW + 7) / 8, (sH + 7) / 8, 1);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

                // V: temp -> EVSM mip, write rect W only (absolute dst origin)
                GlStateManager._bindTexture(tempId);
                GL42.glBindImageTexture(0, texId, lod, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
                GL20.glUniform1i(uBlHorizontal, 0);
                GL20.glUniform2i(uBlSrcOrigin, wx0 - sx0, wy0 - sy0);
                GL20.glUniform2i(uBlDstOrigin, regOrigX + wx0, regOrigY + wy0);
                GL20.glUniform2i(uBlSize, wW, wH);
                GL43.glDispatchCompute((wW + 7) / 8, (wH + 7) / 8, 1);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

                // Next level's changed content = this level's write rect, halved.
                cx0 = wx0 >> 1;
                cy0 = wy0 >> 1;
                cx1 = (wx1 + 1) >> 1;
                cy1 = (wy1 + 1) >> 1;
                ix0 = ix0 >> 1;
                iy0 = iy0 >> 1;
                ix1 = (ix1 + 1) >> 1;
                iy1 = (iy1 + 1) >> 1;
            }
        }
        if (probe != null)
        {
            probe.counter("evsm.pxact", (int) pxAct);
            probe.counter("evsm.pxcore", (int) pxCore);
            probe.counter("evsm.pxideal", (int) pxIdeal);
        }

        GlStateManager._bindTexture(prevTex);
        GlStateManager._glUseProgram(prevProgram);
        if (prevImgName == 0)
        {
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32F);
        }
        else
        {
            GL42.glBindImageTexture(0, prevImgName, prevImgLevel, prevImgLayered != 0, prevImgLayer, prevImgAccess, prevImgFormat);
        }
    }

    private static void ensureResources()
    {
        if (!programsAttempted)
        {
            programsAttempted = true;
            progConvert = buildProgram(CONVERT_SRC, "convert");
            progBlur = buildProgram(BLUR_SRC, "blur");
            progMip = buildProgram(MIP_SRC, "mip");
            if (progConvert != 0)
            {
                uCvSrc = GL20.glGetUniformLocation(progConvert, "srcDepth");
                uCvFar = GL20.glGetUniformLocation(progConvert, "far");
                uCvStep = GL20.glGetUniformLocation(progConvert, "srcStep");
                uCvSrcOrigin = GL20.glGetUniformLocation(progConvert, "srcOrigin");
                uCvDstOrigin = GL20.glGetUniformLocation(progConvert, "dstOrigin");
                uCvDstSize = GL20.glGetUniformLocation(progConvert, "dstSize");
            }
            if (progBlur != 0)
            {
                uBlSrc = GL20.glGetUniformLocation(progBlur, "srcTex");
                uBlSrcLod = GL20.glGetUniformLocation(progBlur, "srcLod");
                uBlHorizontal = GL20.glGetUniformLocation(progBlur, "horizontal");
                uBlSrcOrigin = GL20.glGetUniformLocation(progBlur, "srcOrigin");
                uBlDstOrigin = GL20.glGetUniformLocation(progBlur, "dstOrigin");
                uBlSize = GL20.glGetUniformLocation(progBlur, "size");
                uBlClampMax = GL20.glGetUniformLocation(progBlur, "clampMax");
            }
            if (progMip != 0)
            {
                uMpSrc = GL20.glGetUniformLocation(progMip, "srcTex");
                uMpSrcLod = GL20.glGetUniformLocation(progMip, "srcLod");
                uMpSrcOrigin = GL20.glGetUniformLocation(progMip, "srcOrigin");
                uMpDstOrigin = GL20.glGetUniformLocation(progMip, "dstOrigin");
                uMpDstSize = GL20.glGetUniformLocation(progMip, "dstSize");
            }
        }
        if (progConvert == 0 || progBlur == 0 || progMip == 0 || texId != 0)
        {
            return;
        }

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        // Base = atlas >> shift; the chain still ends at one texel per full
        // tile, so a deeper shift means one level fewer (min 1, defensive).
        int shift = SpotlightDepthAtlas.evsmShift();
        levels = Math.max(1,
            Integer.numberOfTrailingZeros(Integer.highestOneBit(SpotlightDepthAtlas.getTileSize())) - shift + 1);

        texId = GlStateManager._genTexture();
        GlStateManager._bindTexture(texId);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_RGBA32F,
            SpotlightDepthAtlas.getAtlasWidth() >> shift, SpotlightDepthAtlas.getAtlasHeight() >> shift);
        ShadowAllocLog.log("spot-evsm " + (SpotlightDepthAtlas.getAtlasWidth() >> shift) + "x"
                + (SpotlightDepthAtlas.getAtlasHeight() >> shift) + " rgba32f",
            ShadowAllocLog.mipChainBytes(SpotlightDepthAtlas.getAtlasWidth() >> shift,
                SpotlightDepthAtlas.getAtlasHeight() >> shift, levels, 16L));
        // trilinear: the shader picks a fractional lod from the penumbra width
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);

        // temp (H output / V input) and scratch (raw reduce output), both sized
        // for the LARGEST tile's lod-0 region (tileSize >> shift)^2; every
        // quadtree sub-tile region — and every partial S rect — at every lod
        // is smaller, so they always fit.
        int side = Math.max(1, SpotlightDepthAtlas.getTileSize() >> shift);
        tempId = allocScratch(side);
        scratchId = allocScratch(side);

        GlStateManager._bindTexture(prevTex);
    }

    private static int allocScratch(int side)
    {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA32F, side, side);
        ShadowAllocLog.log("spot-evsm scratch " + side + "^2 rgba32f", (long) side * side * 16L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        return id;
    }

    private static int buildProgram(String src, String tag)
    {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] SpotShadowEvsm " + tag + " compute compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] SpotShadowEvsm " + tag + " compute link failed: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /** Bytes of the currently resident EVSM chain (moments + temp + scratch)
     *  — feeds the preset-flip budget's "about to be freed" accounting. */
    static long allocatedBytes()
    {
        long total = 0;
        int shift = SpotlightDepthAtlas.evsmShift();
        if (texId != 0)
        {
            total += ShadowAllocLog.mipChainBytes(SpotlightDepthAtlas.getAtlasWidth() >> shift,
                SpotlightDepthAtlas.getAtlasHeight() >> shift, levels, 16L);
        }
        long side = Math.max(1, SpotlightDepthAtlas.getTileSize() >> shift);
        if (tempId != 0)
        {
            total += side * side * 16L;
        }
        if (scratchId != 0)
        {
            total += side * side * 16L;
        }
        return total;
    }

    /** Free the textures (called with SpotlightDepthAtlas.delete()). Deletion
     *  goes through GlStateManager — see SpotShadowPyramid.delete(). */
    public static void delete()
    {
        ShadowBaker.invalidateOverlays();
        if (texId != 0)
        {
            GlStateManager._deleteTexture(texId);
            texId = 0;
        }
        if (tempId != 0)
        {
            GlStateManager._deleteTexture(tempId);
            tempId = 0;
        }
        if (scratchId != 0)
        {
            GlStateManager._deleteTexture(scratchId);
            scratchId = 0;
        }
        levels = 0;
        dirtyMask = 0L;
    }
}
