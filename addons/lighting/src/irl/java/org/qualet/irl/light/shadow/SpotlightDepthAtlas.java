package org.qualet.irl.light.shadow;

import org.lwjgl.opengl.GL11;

/**
 * 2D depth atlas of perspective shadow maps, one tile per spotlight — a
 * thin static facade over a {@link DepthTileAtlas} instance (grid 4x4,
 * square cells, quadtree layout {@link #TIER_CELLS}). All tile rects come
 * from the accessors below — {@link #tilePixelX}, {@link #tilePixelY},
 * {@link #tileSizePx} — nothing outside this class may do its own tile-rect
 * math.
 *
 * FROZEN MIRROR CONTRACT (the injected GLSL replicates this flat-index ->
 * rect formula verbatim in phase I4, like LightBuffer's std430 mirror
 * comments — any change here is an ABI break with every generated patch):
 * <pre>
 *   Cells are the GRID_X*GRID_Y physical grid cells in row-major flat order
 *   (cell c at cellX = c % GRID_X, cellY = c / GRID_X), assigned to tiers
 *   contiguously: the first TIER_CELLS[0] cells are tier 0 (1 full tile
 *   each, div 1), the next TIER_CELLS[1] cells are tier 1 (4 half-size
 *   sub-tiles each, 2x2 row-major inside the cell, div 2), the last
 *   TIER_CELLS[2] cells are tier 2 (16 quarter-size sub-tiles each, 4x4
 *   row-major, div 4).
 *
 *   The flat TILE index enumerates tier-0 tiles first, then tier-1
 *   sub-tiles cell by cell, then tier-2. With N0/N1/N2 = TIER_CELLS:
 *     tier 0: tile in [0, N0)                    -> cell = tile,
 *             sub = (0, 0), div = 1
 *     tier 1: tile in [N0, N0 + 4*N1)            -> j = tile - N0,
 *             cell = N0 + j/4, sub = (j%4 % 2, j%4 / 2), div = 2
 *     tier 2: tile in [N0 + 4*N1, N0+4*N1+16*N2) -> j = tile - N0 - 4*N1,
 *             cell = N0 + N1 + j/16, sub = (j%16 % 4, j%16 / 4), div = 4
 *
 *   rect origin = (cellX*tileSize + subX*(tileSize/div),
 *                  cellY*tileSize + subY*(tileSize/div)),
 *   rect extent = tileSize / div.
 * </pre>
 * Every tile origin is a multiple of its own extent (power-of-two
 * subdivision), so downstream mip math may shift origins right in lockstep
 * with sizes. The tile size is preset-mutable at runtime
 * ({@link #setTileSize}); see {@link DepthTileAtlas} for the two-layer
 * (live/static) storage and GL format details.
 */
public final class SpotlightDepthAtlas
{
    /** Cells per tier: {full 1x1, split 2x2, split 4x4}. I3 layout {8, 6, 2}:
     *  8 full tiles + 6*4 = 24 half tiles + 2*16 = 32 quarter tiles = 64 tiles
     *  in the same physical atlas. See the class javadoc for the frozen
     *  index->rect formula. */
    private static final int[] TIER_CELLS = { 8, 6, 2 };

    private static final DepthTileAtlas INSTANCE =
        new DepthTileAtlas("spot", 4, 4, 1, 1, TIER_CELLS, 1024);

    static
    {
        if (INSTANCE.tileCount() > 64)
        {
            throw new IllegalStateException("SpotlightDepthAtlas: tileCount " + INSTANCE.tileCount() + " > 64 (downstream dirty masks are long)");
        }
    }

    private SpotlightDepthAtlas()
    {}

    /** Extra right-shift of the spot EVSM working base below the depth atlas:
     *  1 = base atlas/2 (the historic contract), 2 = base atlas/4 (ULTRA
     *  relief — the filter chain re-filters every overlay tile per frame, and
     *  at 4096 tiles the full-base chain alone capped the frame rate).
     *  Preset-mutable like the tile size ({@link IRLShadowQuality#apply});
     *  the injected GLSL re-derives the ratio per fetch from textureSize, so
     *  a shift change needs no shader work — only the ratio-aware packs
     *  (piloted on CR) keep their EVSM branch at shift 2; older patches'
     *  strict size gate falls back to PCF, never to garbage. */
    private static int evsmShift = 1;

    /** Current tile resolution (tier-0 extent); preset-mutable via {@link #setTileSize}. */
    public static int getTileSize()
    {
        return INSTANCE.getTileSize();
    }

    /** The EVSM base shift (1 = atlas/2, 2 = atlas/4); see {@link #setEvsmShift}. */
    public static int evsmShift()
    {
        return evsmShift;
    }

    /** Switch the EVSM base shift; frees the EVSM textures on a change so the
     *  next flush re-allocates at the new base ({@link SpotShadowEvsm} derives
     *  every size from this facade). Independent of {@link #setTileSize} —
     *  the two may change together on a preset switch in any order. */
    public static void setEvsmShift(int shift)
    {
        int s = Math.max(1, Math.min(2, shift));
        if (s == evsmShift)
        {
            return;
        }
        SpotShadowEvsm.delete();
        evsmShift = s;
    }

    public static int getAtlasWidth()
    {
        return INSTANCE.getAtlasWidth();
    }

    public static int getAtlasHeight()
    {
        return INSTANCE.getAtlasHeight();
    }

    /** Lazy — returns 0 until the first bake allocates (keeps VRAM free when no spot exists). */
    public static int getGlTextureId()
    {
        return INSTANCE.getGlTextureId();
    }

    /** FBO of the requested layer (false = live, true = static), allocating it
     *  on first use. */
    public static int getFboId(boolean staticLayer)
    {
        return INSTANCE.getFboId(staticLayer);
    }

    /** Number of addressable tiles in the quadtree layout (64 with the I3
     *  layout {8,6,2}). */
    public static int tileCount()
    {
        return INSTANCE.tileCount();
    }

    /** First flat tile index of tier {@code t} — the tier ranges are
     *  contiguous: [0, 8), [8, 32), [32, 64) with the I3 layout {8,6,2}. */
    public static int tierStartTile(int t)
    {
        return INSTANCE.tierStartTile(t);
    }

    /** Number of tiles in tier {@code t} (8 / 24 / 32 with {8,6,2}). */
    public static int tierTileCount(int t)
    {
        return INSTANCE.tierTileCount(t);
    }

    /** Pixel X of a tile's rect origin (see the class mirror contract). */
    public static int tilePixelX(int tile)
    {
        return INSTANCE.tilePixelX(tile);
    }

    /** Pixel Y of a tile's rect origin (see the class mirror contract). */
    public static int tilePixelY(int tile)
    {
        return INSTANCE.tilePixelY(tile);
    }

    /** Pixel extent (width == height) of one tile: tileSize / (1|2|4). */
    public static int tileSizePx(int tile)
    {
        return INSTANCE.unitSizePx(tile);
    }

    /** Tier (0|1|2) of a flat tile index — a piecewise range lookup over the
     *  contiguous tier ranges (see the class mirror contract). */
    public static int tileTier(int tile)
    {
        return INSTANCE.tileTier(tile);
    }

    /** GPU-copy one tile's depth from the static atlas into the live atlas —
     *  restores a light's static base before its dynamic casters are drawn on
     *  top, without re-rendering any static geometry. */
    public static void copyStaticToLive(int tile)
    {
        INSTANCE.copyStaticToLive(tile);
    }

    /** Rect variant of {@link #copyStaticToLive}: restore only a TILE-LOCAL
     *  depth-pixel sub-rect of the static base (partial-tile overlay — the
     *  region the dynamic casters touched this frame or last). The rect must
     *  lie inside the tile; the caller clamps. */
    public static void copyStaticToLiveRect(int tile, int localX, int localY, int w, int h)
    {
        INSTANCE.copyStaticToLiveRect(INSTANCE.tilePixelX(tile) + localX, INSTANCE.tilePixelY(tile) + localY, w, h);
    }

    public static void delete()
    {
        SpotShadowPyramid.delete(); // pyramid base size tracks the atlas size
        SpotShadowEvsm.delete();    // EVSM base size tracks the atlas size too
        INSTANCE.delete();
    }

    /** Switch tile resolution; frees + re-inits both atlases on next access.
     *  The filter cascade in {@link #delete()} is mandatory — pyramid/EVSM
     *  level counts and temp storage derive from the tile size.
     *  The requested size is clamped down (power-of-two steps) so the 4T
     *  atlas fits GL_MAX_TEXTURE_SIZE, and so the chain's full VRAM
     *  footprint (spot filter textures are atlas-sized, so full = minimal
     *  once any spotlight exists) fits the chain's budget — see
     *  {@link ShadowVramBudget#spotFullFootprintBytes}. NOTE: the estimate
     *  reads {@link #evsmShift()}, so {@link IRLShadowQuality#apply} must
     *  set the shift BEFORE the tile size. */
    public static void setTileSize(int newSize)
    {
        int maxTex = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        int size = newSize;
        while (size > 256 && size * 4 > maxTex)
        {
            size >>= 1;
        }
        if (size != newSize)
        {
            System.err.println("[irl-core] SpotlightDepthAtlas: tile size " + newSize
                + " needs a " + (newSize * 4) + "px atlas > GL_MAX_TEXTURE_SIZE "
                + maxTex + "; clamped to " + size);
        }
        long resident = INSTANCE.allocatedBytes()
            + SpotShadowPyramid.allocatedBytes() + SpotShadowEvsm.allocatedBytes();
        long budget = ShadowVramBudget.chainBudgetBytes(resident);
        long cap = budget >= 0 ? budget : ShadowVramBudget.FALLBACK_CHAIN_BYTES;
        int preBudget = size;
        while (size > 256 && ShadowVramBudget.spotFullFootprintBytes(size, evsmShift) > cap)
        {
            size >>= 1;
        }
        if (size != preBudget)
        {
            System.out.println("[irl-core] SpotlightDepthAtlas: tile size " + preBudget
                + " needs " + (ShadowVramBudget.spotFullFootprintBytes(preBudget, evsmShift) >> 20)
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
