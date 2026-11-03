package org.qualet.irl.light.shadow;

import org.lwjgl.opengl.GL11;

/**
 * 2D depth atlas of point-light shadow faces — a thin static facade over a
 * {@link DepthTileAtlas} instance. The allocation unit is a BLOCK: all 6
 * cube faces of one light, laid out physically contiguous as 3 columns x 2
 * rows ({@link #FACE_COL}/{@link #FACE_ROW}), so a block is one rect and
 * static-base restore is a single GPU copy. A supercell (the DepthTileAtlas
 * cell) is 3T x 2T pixels and always belongs to blocks of one light each —
 * never shared between lights.
 *
 * FROZEN MIRROR CONTRACT — index (the injected GLSL replicates this
 * piecewise mapping verbatim, like LightBuffer's std430 mirror comments —
 * any change here is an ABI break with every generated patch): the GLOBAL
 * point block space is the concatenation of the tiers' block ranges in
 * importance-rank order. With {@link #TIER_SUPERCELLS} = {2, 3, 1} the
 * global space is 0..29 = tier 0 [0, 2), tier 1 [2, 14), tier 2 [14, 30).
 * {@code vlParams.w} carries the GLOBAL block (sentinel: w &lt; 0 = no
 * shadow; decode as {@code int(w + 0.5)}); the GLSL mirrors the same
 * two-threshold piecewise decode (IRL_PT_END0 = 2, IRL_PT_END1 = 14).
 *
 * FROZEN MIRROR CONTRACT — geometry (mirrored by the GLSL
 * irlite_pointAtlasUV; T = tile size, the tier-0 face extent):
 * <pre>
 *   Atlas is 6T x 6T: GRID_X x GRID_Y = 2 x 3 supercells of 3T x 2T,
 *   supercell c at scX = c % 2, scY = c / 2, assigned to tiers
 *   contiguously: TIER_SUPERCELLS[0] full blocks (div 1), then
 *   TIER_SUPERCELLS[1] cells split 2x2 (div 2, row-major), then
 *   TIER_SUPERCELLS[2] cells split 4x4 (div 4, row-major).
 *
 *   Block b of tier t: div = 1 << t, face extent f = T / div (1024/512/256
 *   at T = 1024). Block origin:
 *     X0 = scX*3T + subX*3f,  Y0 = scY*2T + subY*2f
 *   Face rect: origin (X0 + FACE_COL[face]*f, Y0 + FACE_ROW[face]*f),
 *   extent f. Face order = ShadowRenderer.beginPointFace's face switch:
 *   0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z.
 * </pre>
 * INVARIANTS the GLSL side silently depends on: face origins are multiples
 * of f in both axes (pixel-aligned origins keep the manual gather weights
 * exact, and downstream mip filters shift origins in lockstep with sizes);
 * a block's 3f x 2f rect is physically contiguous (one glCopyImageSubData
 * restores a whole static base).
 *
 * Resolution ladder is the pure T, T/2, T/4 — unlike the old cube-array
 * tiers' {@code max(64, F >> t)}, which is indistinguishable at every
 * preset >= 256 but WOULD diverge on a hypothetical preset below 256.
 *
 * VRAM: live depth = 4 bytes * (6T)^2 = 144*T^2 (~144 MiB at T = 1024);
 * the static overlay layer lazily doubles that on the first overlay bake.
 *
 * Class init is GL-free (tables only) — ShadowBaker's static arrays size
 * themselves from {@link #blockCount()} off the GL thread.
 */
public final class PointDepthAtlas
{
    /** Supercells per tier: {full 1x1, split 2x2, split 4x4} = blocks
     *  {2, 12, 16} = 30 lights with a shadow slot (the cube-array layout
     *  held 18). See the class javadoc for the frozen index->rect formula. */
    private static final int[] TIER_SUPERCELLS = { 2, 3, 1 };
    private static final int GRID_X = 2;
    private static final int GRID_Y = 3;

    /** Face position inside a block, 3 columns x 2 rows; indexed by the
     *  bake face id (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z — the
     *  ShadowRenderer.beginPointFace switch order). The GLSL mirror derives
     *  the same table as (face % 3, face / 3). */
    public static final int[] FACE_COL = { 0, 1, 2, 0, 1, 2 };
    public static final int[] FACE_ROW = { 0, 0, 0, 1, 1, 1 };

    private static final DepthTileAtlas INSTANCE =
        new DepthTileAtlas("point", GRID_X, GRID_Y, 3, 2, TIER_SUPERCELLS, 1024);

    static
    {
        if (INSTANCE.tileCount() > 64)
        {
            throw new IllegalStateException("PointDepthAtlas: blockCount " + INSTANCE.tileCount() + " > 64 (downstream dirty masks are long)");
        }
    }

    private PointDepthAtlas()
    {}

    /** Current tier-0 face extent T; preset-mutable via {@link #setTileSize}. */
    public static int getTileSize()
    {
        return INSTANCE.getTileSize();
    }

    public static int getAtlasWidth()
    {
        return INSTANCE.getAtlasWidth(); // 6T
    }

    public static int getAtlasHeight()
    {
        return INSTANCE.getAtlasHeight(); // 6T
    }

    /** Lazy — returns 0 until the first bake allocates (keeps VRAM free when
     *  no shadowed point light exists). Plain GL_TEXTURE_2D — sampled like
     *  the spot atlas, no cube-array rebind hacks. */
    public static int getGlTextureId()
    {
        return INSTANCE.getGlTextureId();
    }

    /** FBO of the requested layer (false = live, true = static), allocating
     *  it on first use. */
    public static int getFboId(boolean staticLayer)
    {
        return INSTANCE.getFboId(staticLayer);
    }

    /** Number of addressable blocks = shadowed point lights (30 with
     *  {2,3,1}). */
    public static int blockCount()
    {
        return INSTANCE.tileCount();
    }

    /** First GLOBAL block of tier {@code t} — the tier ranges are
     *  contiguous: [0, 2), [2, 14), [14, 30) with {2,3,1}. */
    public static int tierStartBlock(int t)
    {
        return INSTANCE.tierStartTile(t);
    }

    /** Number of blocks in tier {@code t} (2 / 12 / 16 with {2,3,1}). */
    public static int tierBlockCount(int t)
    {
        return INSTANCE.tierTileCount(t);
    }

    /** Tier (0|1|2) of a GLOBAL block — a piecewise range lookup over the
     *  contiguous tier ranges (see the class mirror contract). */
    public static int blockTier(int block)
    {
        return INSTANCE.tileTier(block);
    }

    /** Face extent of tier {@code t}: T &gt;&gt; t (1024/512/256 at T=1024).
     *  Filter storage (pyramid/EVSM) sizes its per-tier arrays from this. */
    public static int tierFaceSizePx(int t)
    {
        return INSTANCE.getTileSize() >> t;
    }

    /** Pixel X of a block's rect origin — the (col 0, row 0) face corner;
     *  feeds the ingestion computes' blockOrigin uniform. */
    public static int blockPixelX(int block)
    {
        return INSTANCE.tilePixelX(block);
    }

    /** Pixel Y of a block's rect origin (see {@link #blockPixelX}). */
    public static int blockPixelY(int block)
    {
        return INSTANCE.tilePixelY(block);
    }

    /** Face extent f of a block (square): T / (1|2|4) by the block's tier. */
    public static int faceSizePx(int block)
    {
        return INSTANCE.unitSizePx(block);
    }

    /** Pixel X of one face's rect origin inside a block. */
    public static int facePixelX(int block, int face)
    {
        return INSTANCE.tilePixelX(block) + FACE_COL[face] * INSTANCE.unitSizePx(block);
    }

    /** Pixel Y of one face's rect origin inside a block. */
    public static int facePixelY(int block, int face)
    {
        return INSTANCE.tilePixelY(block) + FACE_ROW[face] * INSTANCE.unitSizePx(block);
    }

    /** GPU-copy a whole block (all 6 faces, one contiguous 3f x 2f rect) from
     *  the static atlas into the live atlas — restores a light's static base
     *  before its dynamic casters are drawn on top. */
    public static void copyStaticToLive(int block)
    {
        INSTANCE.copyStaticToLive(block);
    }

    /** GPU-copy one face's rect from the static atlas into the live atlas —
     *  the per-face overlay restore path. */
    public static void copyStaticFaceToLive(int block, int face)
    {
        int f = INSTANCE.unitSizePx(block);
        INSTANCE.copyStaticToLiveRect(facePixelX(block, face), facePixelY(block, face), f, f);
    }

    public static void delete()
    {
        PointShadowPyramid.delete(); // pyramid base size tracks the atlas size
        PointShadowEvsm.delete();    // MSM base size tracks the atlas size too
        INSTANCE.delete();
    }

    /** Switch the tier-0 face extent; frees + re-inits both atlases on next
     *  access. The filter cascade in {@link #delete()} is mandatory —
     *  pyramid/EVSM level counts and temp storage derive from the tile size.
     *  The requested size is clamped down (power-of-two steps) twice: so the
     *  6T atlas fits GL_MAX_TEXTURE_SIZE (a 16384-limit card lands ULTRA on
     *  2048; a 32768-limit card passes untouched), and so the chain's
     *  unavoidable VRAM footprint (both depth layers + tier-0 filters + blur
     *  temp — see {@link ShadowVramBudget#pointMinFootprintBytes}) fits the
     *  chain's budget — without this, ULTRA on a 32768-limit 12 GiB card
     *  allocated 9.6 GiB at world join and thrashed residency for the whole
     *  session. */
    public static void setTileSize(int newSize)
    {
        int maxTex = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        int size = newSize;
        while (size > 256 && size * (GRID_X * 3) > maxTex)
        {
            size >>= 1;
        }
        if (size != newSize)
        {
            System.err.println("[irl-core] PointDepthAtlas: face size " + newSize
                + " needs a " + (newSize * GRID_X * 3) + "px atlas > GL_MAX_TEXTURE_SIZE "
                + maxTex + "; clamped to " + size);
        }
        long resident = INSTANCE.allocatedBytes()
            + PointShadowPyramid.allocatedBytes() + PointShadowEvsm.allocatedBytes();
        long budget = ShadowVramBudget.chainBudgetBytes(resident);
        long cap = budget >= 0 ? budget : ShadowVramBudget.FALLBACK_CHAIN_BYTES;
        int preBudget = size;
        while (size > 256 && ShadowVramBudget.pointMinFootprintBytes(size) > cap)
        {
            size >>= 1;
        }
        if (size != preBudget)
        {
            System.out.println("[irl-core] PointDepthAtlas: face size " + preBudget
                + " needs " + (ShadowVramBudget.pointMinFootprintBytes(preBudget) >> 20)
                + " MiB > chain budget " + (cap >> 20) + " MiB"
                + (budget < 0 ? " (free VRAM unqueryable, fallback cap)" : "")
                + "; clamped to " + size);
        }
        if (size == INSTANCE.getTileSize())
        {
            return;
        }
        delete();
        INSTANCE.setTileSize(size);
    }
}
