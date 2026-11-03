package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * MSM4 (Hamburger) prefilter of the LIVE point shadow atlas (F2b, moment math
 * swapped EVSM-&gt;MSM same day). Flat static like {@link SpotShadowEvsm}, but
 * the storage stays PER-TIER: one GL_TEXTURE_2D_ARRAY per
 * {@link PointDepthAtlas} tier (three GLSL sampler sets, irl_pointEvsm/1/2),
 * face-major within the tier (layer = localBlock*6 + face), RGBA32F,
 * base = face/2, Gaussian-blurred base + re-blurred mip chain like
 * {@link SpotShadowEvsm}. Sampled through a CUBE_MAP_ARRAY texture view with
 * one trilinear textureLod + the Hamburger 4MSM solver for wide point
 * penumbras.
 *
 * Face seams: blur taps that fall off the face are remapped through direction
 * space onto the angularly-adjacent texel of the neighbouring face (faceDir +
 * its dominant-axis inverse), keeping the moment field continuous across cube
 * edges at every mip — plain face-local clamping showed blur bands along the
 * ±45° seams once Softness pushed the footprint onto coarse mips. The sampling
 * side keeps only its half-texel UV clamp.
 *
 * Moment CONTRACT (mirror of the GLSL point branch): lz = (zdist - near)/(far
 * - near) where zdist is the linearized DOMINANT-AXIS distance decoded from
 * the stored perspective depth (near 0.05, far = the block's light radius,
 * passed per block at markDirty); stored = MSM4 moments (lz, lz^2, -lz^3,
 * lz^4) for the Hamburger solver — EVSM warps could not darken crossing
 * penumbras of two blockers (bimodal distribution), MSM4 handles them. The
 * THIRD moment is stored NEGATED purely as the validity marker: readers gate
 * on mm.z &lt; 0 (garbage/pyramid/cookie textures read back &gt;= 0) and flip the
 * sign before solving. Class keeps its EVSM name to avoid mixin churn in both
 * host mods (they reference this class by name for the irl_pointEvsm bind).
 *
 * The ingestion pass (CONVERT_SRC) reads the flat depth atlas via texelFetch
 * (blockOrigin uniform + compile-time FACE_OFF, the mirror of
 * PointDepthAtlas.FACE_COL/FACE_ROW); BLUR_SRC keeps its own faceDir seam
 * remap — that one operates on THIS class's MSM storage, not the raw depth
 * source, and must stay.
 */
public final class PointShadowEvsm
{
    /** The cube-view sampling below needs seamless cubemap filtering enabled
     *  once per GL context — this class owns the only enable site in the
     *  core + host mods (Iris and vanilla never set it). Without it the view
     *  filters per-face CLAMP_TO_EDGE and wide penumbras band along ±45°
     *  seams at coarse mips — exactly what the view was introduced to fix. */
    private static final int GL_TEXTURE_CUBE_MAP_SEAMLESS = 0x884F;

    private static final int[] texId = new int[3];   // MSM storage per tier: 2D-array, base = face/2, layers = blocks*6, levels = log2(faceSize)
    private static final int[] viewId = new int[3];  // CUBE_MAP_ARRAY texture view over texId[t]: hardware-seamless trilinear sampling (what the pack binds)
    private static final int[] levels = new int[3];
    /** Blocks each tier's array physically backs (layers = blocks*6). Grown
     *  in {@link ShadowVramBudget}-approved chunks by {@link #flushDirty};
     *  0 until the tier's first dirty block. */
    private static final int[] capBlocks = new int[3];
    /** ONE shared 6-layer ping-pong temp for the separable blur, sized for the
     *  LARGEST tier's lod-0 region (tileSize/2)^2 — every tier's region at
     *  every lod is smaller, so it always fits (the SpotShadowEvsm pattern). */
    private static int tempId = 0;
    /** One bit per GLOBAL block (1L << block); long because the atlas layout
     *  allows up to 64 blocks (guarded in PointDepthAtlas). */
    private static long dirtyMask = 0L;
    /** Light range per dirty block (linear depth metric); indexed by GLOBAL block. */
    private static final float[] blockFar = new float[PointDepthAtlas.blockCount()];

    // Compute programs + uniform locations — the GLSL sources are
    // size-independent, every size flows through uniforms.
    private static int progConvert = 0, progBlur = 0, progMip = 0;
    private static int uCvSrc, uCvFar, uCvSlot, uCvDstSize, uCvBlockOrigin;
    private static int uBlSrc, uBlSrcLod, uBlHorizontal, uBlSrcLayerBase, uBlDstLayerBase, uBlSize;
    private static int uMpSrc, uMpSrcLod, uMpLayerBase, uMpDstSize;
    private static boolean programsAttempted = false;

    private static final String COMMON =
        "#version 430 core\n" +
        "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n" +
        "layout(rgba32f, binding = 0) uniform writeonly image2DArray dst;\n";

    // depth atlas 2x2 -> linearized MSM4 moments (z, z2, -z3, z4); the face rect
    // comes from blockOrigin + FACE_OFF (see PointShadowPyramid's CUBE_SRC twin)
    private static final String CONVERT_SRC = COMMON +
        "uniform sampler2D srcAtlas;\n" +
        "uniform float far;\n" +
        "uniform int slot;\n" +                      // LOCAL block index inside the tier (dst layer base / 6)
        "uniform ivec2 dstSize;\n" +
        "uniform ivec2 blockOrigin;\n" +             // pixel origin of the block (its col-0/row-0 face corner)
        "const ivec2 FACE_OFF[6] = ivec2[6](ivec2(0,0), ivec2(1,0), ivec2(2,0),\n" +
        "                                   ivec2(0,1), ivec2(1,1), ivec2(2,1));\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    int face = int(gl_GlobalInvocationID.z);\n" +
        "    int srcRes = dstSize.x * 2;\n" +
        "    ivec2 faceBase = blockOrigin + FACE_OFF[face] * srcRes;\n" +
        "    vec4 m = vec4(0.0);\n" +
        "    for (int j = 0; j < 2; j++)\n" +
        "    for (int i = 0; i < 2; i++)\n" +
        "    {\n" +
        "        float z01 = texelFetch(srcAtlas, faceBase + g * 2 + ivec2(i, j), 0).r;\n" +
        "        float ndcZ = z01 * 2.0 - 1.0;\n" +
        "        float zdist = (2.0 * far * 0.05) / max((far + 0.05) - ndcZ * (far - 0.05), 1e-6);\n" +
        "        float lz = clamp((zdist - 0.05) / (far - 0.05), 0.0, 1.0);\n" +
        "        float lz2 = lz * lz;\n" +
        "        m += vec4(lz, lz2, -(lz2 * lz), lz2 * lz2);\n" +
        "    }\n" +
        "    imageStore(dst, ivec3(g, slot * 6 + face), m * 0.25);\n" +
        "}\n";

    // separable Gaussian 1-4-6-4-1, cube-aware taps (off-face reads remap to the neighbour face); z = face
    private static final String BLUR_SRC = COMMON +
        "uniform sampler2DArray srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform int horizontal;\n" +
        "uniform int srcLayerBase;\n" +
        "uniform int dstLayerBase;\n" +
        "uniform ivec2 size;\n" +
        "const float W[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);\n" +
        "vec3 faceDir(int face, vec2 st)\n" +
        "{\n" +
        "    if (face == 0) return vec3( 1.0, -st.y, -st.x);\n" +
        "    if (face == 1) return vec3(-1.0, -st.y,  st.x);\n" +
        "    if (face == 2) return vec3( st.x,  1.0,  st.y);\n" +
        "    if (face == 3) return vec3( st.x, -1.0, -st.y);\n" +
        "    if (face == 4) return vec3( st.x, -st.y,  1.0);\n" +
        "    return vec3(-st.x, -st.y, -1.0);\n" +
        "}\n" +
        "vec4 tap(int face, ivec2 p)\n" +
        "{\n" +
        "    if (p.x >= 0 && p.y >= 0 && p.x < size.x && p.y < size.y)\n" +
        "    {\n" +
        "        return texelFetch(srcTex, ivec3(p, srcLayerBase + face), srcLod);\n" +
        "    }\n" +
        "    vec2 st = ((vec2(p) + 0.5) / vec2(size)) * 2.0 - 1.0;\n" +
        "    vec3 d = faceDir(face, st);\n" +
        "    vec3 a = abs(d);\n" +
        "    int f2;\n" +
        "    vec2 st2;\n" +
        "    if (a.x >= a.y && a.x >= a.z)\n" +
        "    {\n" +
        "        f2 = d.x > 0.0 ? 0 : 1;\n" +
        "        st2 = vec2(d.x > 0.0 ? -d.z : d.z, -d.y) / a.x;\n" +
        "    }\n" +
        "    else if (a.y >= a.z)\n" +
        "    {\n" +
        "        f2 = d.y > 0.0 ? 2 : 3;\n" +
        "        st2 = vec2(d.x, d.y > 0.0 ? d.z : -d.z) / a.y;\n" +
        "    }\n" +
        "    else\n" +
        "    {\n" +
        "        f2 = d.z > 0.0 ? 4 : 5;\n" +
        "        st2 = vec2(d.z > 0.0 ? d.x : -d.x, -d.y) / a.z;\n" +
        "    }\n" +
        "    ivec2 q = clamp(ivec2((st2 * 0.5 + 0.5) * vec2(size)), ivec2(0), size - 1);\n" +
        "    return texelFetch(srcTex, ivec3(q, srcLayerBase + f2), srcLod);\n" +
        "}\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= size.x || g.y >= size.y) return;\n" +
        "    int z = int(gl_GlobalInvocationID.z);\n" +
        "    vec4 acc = vec4(0.0);\n" +
        "    for (int k = -2; k <= 2; k++)\n" +
        "    {\n" +
        "        ivec2 p = (horizontal == 1) ? ivec2(g.x + k, g.y) : ivec2(g.x, g.y + k);\n" +
        "        acc += W[k + 2] * tap(z, p);\n" +
        "    }\n" +
        "    imageStore(dst, ivec3(g, dstLayerBase + z), acc);\n" +
        "}\n";

    // 2x2 average mip down, z = face
    private static final String MIP_SRC = COMMON +
        "uniform sampler2DArray srcTex;\n" +
        "uniform int srcLod;\n" +
        "uniform int layerBase;\n" +
        "uniform ivec2 dstSize;\n" +
        "void main()\n" +
        "{\n" +
        "    ivec2 g = ivec2(gl_GlobalInvocationID.xy);\n" +
        "    if (g.x >= dstSize.x || g.y >= dstSize.y) return;\n" +
        "    int layer = layerBase + int(gl_GlobalInvocationID.z);\n" +
        "    ivec2 s = g * 2;\n" +
        "    vec4 m = texelFetch(srcTex, ivec3(s,               layer), srcLod)\n" +
        "           + texelFetch(srcTex, ivec3(s + ivec2(1, 0), layer), srcLod)\n" +
        "           + texelFetch(srcTex, ivec3(s + ivec2(0, 1), layer), srcLod)\n" +
        "           + texelFetch(srcTex, ivec3(s + ivec2(1, 1), layer), srcLod);\n" +
        "    imageStore(dst, ivec3(g, layer), m * 0.25);\n" +
        "}\n";

    private PointShadowEvsm()
    {}

    /** The CUBE_MAP_ARRAY view — sampling crosses face edges seamlessly in hardware
     *  (the 2D-array fetch needed manual clamp margins that plateaued into visible
     *  ±45° bands at coarse mips). Compute passes keep writing the 2D-array storage. */
    public static int getGlTextureId(int tier)
    {
        return viewId[tier];
    }

    /** radius = the light's far plane, needed to linearize depth before the
     *  warp. {@code block} is the GLOBAL block index. */
    public static void markDirty(int block, float radius)
    {
        if (block >= 0 && block < PointDepthAtlas.blockCount())
        {
            dirtyMask |= 1L << block;
            blockFar[block] = Math.max(radius, 0.1f);
        }
    }

    public static boolean isDirty(int block)
    {
        return (dirtyMask & (1L << block)) != 0L;
    }

    public static void clearBit(int block)
    {
        dirtyMask &= ~(1L << block);
    }

    public static void clearAll()
    {
        dirtyMask = 0L;
    }

    /** Convert + blur + mip every dirty block (6 faces each) in one batch. Call
     *  once per bake, after the point loop. */
    public static void flushDirty()
    {
        if (dirtyMask == 0L)
        {
            return;
        }
        long mask = dirtyMask;
        dirtyMask = 0L;
        int depthTex = PointDepthAtlas.getGlTextureId();
        if (depthTex == 0)
        {
            return;
        }
        ensurePrograms();
        if (progConvert == 0 || progBlur == 0 || progMip == 0)
        {
            return; // inert on failure — the GLSL size gate falls back to the PCF path
        }
        ensureCapacities(mask);

        ShadowBakeProbe probe = ShadowEngine.bakeProbe();
        if (probe != null)
        {
            probe.counter("evsm.pt", Long.bitCount(mask));
        }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int prevImgName = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, 0);
        int prevImgLevel = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, 0);
        int prevImgLayered = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, 0);
        int prevImgLayer = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, 0);
        int prevImgAccess = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, 0);
        int prevImgFormat = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, 0);

        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        // tier by tier (blocks are tier-contiguous); barrier balance equals the
        // three independent per-tier flushes of the cube-array layout
        for (int t = 0; t < 3; t++)
        {
            int start = PointDepthAtlas.tierStartBlock(t);
            // capBlocks-bounded: a block past the tier's physical capacity
            // (growth denied by the budget) has no layers to write
            int end = start + Math.min(PointDepthAtlas.tierBlockCount(t), capBlocks[t]);
            long tierBits = 0L;
            for (int b = start; b < end; b++)
            {
                tierBits |= mask & (1L << b);
            }
            if (tierBits == 0L)
            {
                continue;
            }
            int base = PointDepthAtlas.tierFaceSizePx(t) / 2;
            int groups = (base + 7) / 8;

            for (int b = start; b < end; b++)
            {
                if ((mask & (1L << b)) == 0L)
                {
                    continue;
                }
                int local = b - start;
                // convert: depth atlas -> linearized warped moments, lod 0, all 6 faces
                GlStateManager._glUseProgram(progConvert);
                GL20.glUniform1i(uCvSrc, unit);
                GL20.glUniform1f(uCvFar, blockFar[b]);
                GL20.glUniform1i(uCvSlot, local);
                GL20.glUniform2i(uCvDstSize, base, base);
                GL20.glUniform2i(uCvBlockOrigin, PointDepthAtlas.blockPixelX(b), PointDepthAtlas.blockPixelY(b));
                GlStateManager._bindTexture(depthTex);
                GL42.glBindImageTexture(0, texId[t], 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
                GL43.glDispatchCompute(groups, groups, 6);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

                blurBlockLevel(unit, t, local, 0, base);
            }

            // mip chain: downsample the tier's dirty blocks at level L, then re-blur (width doubling contract)
            for (int lod = 1; lod < levels[t]; lod++)
            {
                int dstW = base >> lod;
                GlStateManager._glUseProgram(progMip);
                GL20.glUniform1i(uMpSrc, unit);
                GL20.glUniform1i(uMpSrcLod, lod - 1);
                GL20.glUniform2i(uMpDstSize, dstW, dstW);
                GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId[t]);
                GL42.glBindImageTexture(0, texId[t], lod, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
                for (int b = start; b < end; b++)
                {
                    if ((mask & (1L << b)) == 0L)
                    {
                        continue;
                    }
                    GL20.glUniform1i(uMpLayerBase, (b - start) * 6);
                    GL43.glDispatchCompute((dstW + 7) / 8, (dstW + 7) / 8, 6);
                }
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
                if (dstW >= 2)
                {
                    for (int b = start; b < end; b++)
                    {
                        if ((mask & (1L << b)) == 0L)
                        {
                            continue;
                        }
                        blurBlockLevel(unit, t, b - start, lod, dstW);
                    }
                }
            }
        }

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevArr);
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

    /** Separable Gaussian on all 6 faces of one block (LOCAL index inside its
     *  tier) at MSM mip {@code lod} via the shared 6-layer temp array. */
    private static void blurBlockLevel(int unit, int tier, int local, int lod, int w)
    {
        int groups = (w + 7) / 8;
        GlStateManager._glUseProgram(progBlur);
        GL20.glUniform1i(uBlSrc, unit);
        GL20.glUniform2i(uBlSize, w, w);

        // H: MSM mip -> temp layers 0..5
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, texId[tier]);
        GL42.glBindImageTexture(0, tempId, 0, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        GL20.glUniform1i(uBlSrcLod, lod);
        GL20.glUniform1i(uBlHorizontal, 1);
        GL20.glUniform1i(uBlSrcLayerBase, local * 6);
        GL20.glUniform1i(uBlDstLayerBase, 0);
        GL43.glDispatchCompute(groups, groups, 6);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // V: temp -> MSM mip (barrier above also orders this store after the H fetches)
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tempId);
        GL42.glBindImageTexture(0, texId[tier], lod, true, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA32F);
        GL20.glUniform1i(uBlSrcLod, 0);
        GL20.glUniform1i(uBlHorizontal, 0);
        GL20.glUniform1i(uBlSrcLayerBase, 0);
        GL20.glUniform1i(uBlDstLayerBase, local * 6);
        GL43.glDispatchCompute(groups, groups, 6);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    /** Blocks of tier {@code t} the array physically backs (0 = none). */
    static int allocatedBlocks(int t)
    {
        return capBlocks[t];
    }

    static boolean readyForReuse(int tier, int local)
    {
        return capBlocks[tier] > local && texId[tier] != 0 && tempId != 0
            && progConvert != 0 && progBlur != 0 && progMip != 0;
    }

    /** True when the compute programs failed to build — this filter will
     *  never allocate or dispatch, so it must not constrain the budget. */
    static boolean inert()
    {
        return programsAttempted && (progConvert == 0 || progBlur == 0 || progMip == 0);
    }

    /** Bytes of the currently resident tier arrays + blur temp — feeds the
     *  preset-flip budget's "about to be freed" accounting. */
    static long allocatedBytes()
    {
        long total = 0;
        for (int t = 0; t < 3; t++)
        {
            if (capBlocks[t] > 0)
            {
                int base = PointDepthAtlas.tierFaceSizePx(t) / 2;
                total += ShadowAllocLog.mipChainBytes(base, levels[t], 16L) * capBlocks[t] * 6L;
            }
        }
        if (tempId != 0)
        {
            long half = PointDepthAtlas.getTileSize() / 2;
            total += half * half * 6L * 16L;
        }
        return total;
    }

    /** Per-tier cooldown (in flushes) after a failed glTexStorage3D — bounds
     *  the retry/log storm of a genuinely OOM'd card while still recovering
     *  once VRAM frees up. */
    private static final int[] growRetryDelay = new int[3];

    private static void ensurePrograms()
    {
        if (!programsAttempted)
        {
            programsAttempted = true;
            progConvert = buildProgram(CONVERT_SRC, "convert");
            progBlur = buildProgram(BLUR_SRC, "blur");
            progMip = buildProgram(MIP_SRC, "mip");
            if (progConvert != 0)
            {
                uCvSrc = GL20.glGetUniformLocation(progConvert, "srcAtlas");
                uCvFar = GL20.glGetUniformLocation(progConvert, "far");
                uCvSlot = GL20.glGetUniformLocation(progConvert, "slot");
                uCvDstSize = GL20.glGetUniformLocation(progConvert, "dstSize");
                uCvBlockOrigin = GL20.glGetUniformLocation(progConvert, "blockOrigin");
            }
            if (progBlur != 0)
            {
                uBlSrc = GL20.glGetUniformLocation(progBlur, "srcTex");
                uBlSrcLod = GL20.glGetUniformLocation(progBlur, "srcLod");
                uBlHorizontal = GL20.glGetUniformLocation(progBlur, "horizontal");
                uBlSrcLayerBase = GL20.glGetUniformLocation(progBlur, "srcLayerBase");
                uBlDstLayerBase = GL20.glGetUniformLocation(progBlur, "dstLayerBase");
                uBlSize = GL20.glGetUniformLocation(progBlur, "size");
            }
            if (progMip != 0)
            {
                uMpSrc = GL20.glGetUniformLocation(progMip, "srcTex");
                uMpSrcLod = GL20.glGetUniformLocation(progMip, "srcLod");
                uMpLayerBase = GL20.glGetUniformLocation(progMip, "layerBase");
                uMpDstSize = GL20.glGetUniformLocation(progMip, "dstSize");
            }
        }
    }

    /** Grow every tier the dirty mask touches past its current capacity, up
     *  to the budget-approved block count (demand-sized: a tier no lamp ever
     *  ranks into never allocates). The shared blur temp allocates with the
     *  first tier — sized (tileSize/2)^2, the largest any tier needs. */
    private static void ensureCapacities(long mask)
    {
        int prevArr = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        for (int t = 0; t < 3; t++)
        {
            int start = PointDepthAtlas.tierStartBlock(t);
            int needed = 0;
            for (int b = start + PointDepthAtlas.tierBlockCount(t) - 1; b >= start; b--)
            {
                if ((mask & (1L << b)) != 0L)
                {
                    needed = b - start + 1;
                    break;
                }
            }
            if (needed > capBlocks[t] || (needed > 0 && texId[t] == 0))
            {
                if (growRetryDelay[t] > 0)
                {
                    growRetryDelay[t]--; // OOM cooldown; writes stay capBlocks-bounded
                }
                else
                {
                    // approved >= needed by the acquire-cap invariant; max() keeps
                    // a stray block writable even if that invariant ever slips
                    ensureTier(t, Math.max(needed, ShadowVramBudget.approvedPointBlocks(t)));
                }
            }
        }
        if (tempId == 0 && (capBlocks[0] > 0 || capBlocks[1] > 0 || capBlocks[2] > 0))
        {
            tempId = GL11.glGenTextures();
            GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, tempId);
            GL43.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, 1, GL30.GL_RGBA32F,
                PointDepthAtlas.getTileSize() / 2, PointDepthAtlas.getTileSize() / 2, 6);
            ShadowAllocLog.log("point-evsm temp " + (PointDepthAtlas.getTileSize() / 2) + "^2 x6 rgba32f",
                (long) (PointDepthAtlas.getTileSize() / 2) * (PointDepthAtlas.getTileSize() / 2) * 6L * 16L);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        }
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevArr);
    }

    /** (Re)allocate tier {@code t} at {@code targetBlocks} blocks (layers =
     *  blocks*6, same level count), preserving the already-filtered layers of
     *  the old array via a per-level GPU copy, then rebuild the CUBE_MAP_ARRAY
     *  view (views require immutable storage — no in-place growth; the pack
     *  binds the view id via IntSupplier, so a swap is picked up at the next
     *  bind). Leaves GL_TEXTURE_2D_ARRAY bound to the new texture (the caller
     *  restores). */
    private static void ensureTier(int t, int targetBlocks)
    {
        targetBlocks = Math.min(targetBlocks, PointDepthAtlas.tierBlockCount(t));
        if (targetBlocks <= capBlocks[t] && texId[t] != 0)
        {
            return;
        }
        int face = PointDepthAtlas.tierFaceSizePx(t);
        int base = face / 2;
        int lv = Integer.numberOfTrailingZeros(Integer.highestOneBit(face));
        int newTex = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, newTex);
        GL43.glTexStorage3D(GL30.GL_TEXTURE_2D_ARRAY, lv, GL30.GL_RGBA32F, base, base, targetBlocks * 6);
        // GL_TEXTURE_IMMUTABLE_FORMAT flips to TRUE only on a successful
        // glTexStorage* — on OOM the name has no storage: keep the old valid
        // array (its filtered data AND its view) untouched, retry after a
        // cooldown. Proceeding blind would glTextureView a storageless name
        // and then delete the only good copy.
        if (GL11.glGetTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL42.GL_TEXTURE_IMMUTABLE_FORMAT) != GL11.GL_TRUE)
        {
            GL11.glDeleteTextures(newTex);
            growRetryDelay[t] = 600;
            System.err.println("[irl-core] PointShadowEvsm: t" + t + " glTexStorage3D "
                + base + "^2 x" + (targetBlocks * 6) + " failed (VRAM?); keeping "
                + capBlocks[t] + " blocks, retry in " + growRetryDelay[t] + " flushes");
            return;
        }
        ShadowAllocLog.log("point-evsm t" + t + " " + base + "^2 x" + (targetBlocks * 6)
                + "/" + (PointDepthAtlas.tierBlockCount(t) * 6) + " rgba32f",
            ShadowAllocLog.mipChainBytes(base, lv, 16L) * targetBlocks * 6L);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, lv - 1);
        if (texId[t] != 0 && capBlocks[t] > 0)
        {
            // order prior imageStore writes before the copy reads them
            GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);
            for (int lod = 0; lod < lv; lod++)
            {
                int w = Math.max(1, base >> lod);
                GL43.glCopyImageSubData(texId[t], GL30.GL_TEXTURE_2D_ARRAY, lod, 0, 0, 0,
                    newTex, GL30.GL_TEXTURE_2D_ARRAY, lod, 0, 0, 0, w, w, capBlocks[t] * 6);
            }
        }

        // cube view over the new storage (immutable glTexStorage3D, blocks*6
        // layers): the pack samples THESE ids — seamless cubemap filtering
        // handles face edges at every mip. The context-global seamless enable
        // lives HERE (see GL_TEXTURE_CUBE_MAP_SEAMLESS above).
        GL11.glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
        int prevCubeArr = GL11.glGetInteger(GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY);
        int newView = GL11.glGenTextures();
        GL43.glTextureView(newView, GL40.GL_TEXTURE_CUBE_MAP_ARRAY, newTex, GL30.GL_RGBA32F, 0, lv, 0, targetBlocks * 6);
        GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, newView);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, GL12.GL_TEXTURE_MAX_LEVEL, lv - 1);
        GL11.glBindTexture(GL40.GL_TEXTURE_CUBE_MAP_ARRAY, prevCubeArr);

        if (viewId[t] != 0)
        {
            GL11.glDeleteTextures(viewId[t]);
        }
        if (texId[t] != 0)
        {
            GL11.glDeleteTextures(texId[t]);
        }
        viewId[t] = newView;
        texId[t] = newTex;
        levels[t] = lv;
        capBlocks[t] = targetBlocks;
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, newTex);
    }

    private static int buildProgram(String src, String tag)
    {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] PointShadowEvsm " + tag + " compute compile failed: " + GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, shader);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
        {
            System.err.println("[irl-core] PointShadowEvsm " + tag + " compute link failed: " + GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    /** Free the textures (called with PointDepthAtlas.delete()); raw delete is
     *  safe — 2D-array/cube-array bindings are not tracked by GlStateManager. */
    public static void delete()
    {
        ShadowBaker.invalidateOverlays();
        for (int t = 0; t < 3; t++)
        {
            if (viewId[t] != 0)
            {
                GL11.glDeleteTextures(viewId[t]);
                viewId[t] = 0;
            }
            if (texId[t] != 0)
            {
                GL11.glDeleteTextures(texId[t]);
                texId[t] = 0;
            }
            levels[t] = 0;
            capBlocks[t] = 0;
            growRetryDelay[t] = 0;
        }
        if (tempId != 0)
        {
            GL11.glDeleteTextures(tempId);
            tempId = 0;
        }
        clearAll();
    }
}
