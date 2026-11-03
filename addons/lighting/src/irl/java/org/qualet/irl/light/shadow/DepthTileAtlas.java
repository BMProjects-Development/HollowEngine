package org.qualet.irl.light.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;

/**
 * Generic 2D depth atlas with a static quadtree tile layout — the grid,
 * tier split and cell aspect are constructor parameters, so one class can
 * back several independent atlases (spot tiles, point face-blocks, ...).
 *
 * The physical texture is a gridX x gridY grid of CELLS; a cell is
 * (tileSize * cellAspectX) x (tileSize * cellAspectY) pixels. A static
 * quadtree layout (tierCells = {full 1x1, split 2x2, split 4x4}) subdivides
 * some cells into smaller tiles. The flat TILE index enumerates tier-0
 * tiles first, then tier-1 sub-tiles cell by cell (2x2 row-major inside the
 * cell), then tier-2 (4x4 row-major). With N0/N1/N2 = tierCells:
 * <pre>
 *   tier 0: tile in [0, N0)                    -> cell = tile,
 *           sub = (0, 0), div = 1
 *   tier 1: tile in [N0, N0 + 4*N1)            -> j = tile - N0,
 *           cell = N0 + j/4, sub = (j%4 % 2, j%4 / 2), div = 2
 *   tier 2: tile in [N0 + 4*N1, N0+4*N1+16*N2) -> j = tile - N0 - 4*N1,
 *           cell = N0 + N1 + j/16, sub = (j%16 % 4, j%16 / 4), div = 4
 *
 *   cellW = tileSize * cellAspectX, cellH = tileSize * cellAspectY
 *   rect origin = (cellX*cellW + subX*(cellW/div),
 *                  cellY*cellH + subY*(cellH/div)),
 *   rect extent = (cellW/div) x (cellH/div); unitSizePx = tileSize/div.
 * </pre>
 * INVARIANT: every tile origin is a multiple of its own extent in BOTH
 * axes (power-of-two subdivision of a whole cell) — downstream mip filters
 * shift origins right in lockstep with sizes and depend on this
 * (SpotShadowPyramid / SpotShadowEvsm lockstep shifts).
 *
 * The per-tile tables cache cellX/cellY/subX/subY/div only (tileSize is
 * preset-mutable, so pixel coords are always computed on the fly).
 *
 * TWO layers: the LIVE atlas is what the shader samples; the STATIC atlas
 * holds a light's static-only content (model blocks + world blocks) so that
 * on frames with a dynamic caster the base can be restored with a GPU copy
 * ({@link #copyStaticToLive}) instead of re-rendering every static caster.
 * The static atlas is allocated lazily on the first overlay bake — a scene
 * with no dynamic casters near lamps never pays its VRAM.
 *
 * Format DEPTH_COMPONENT32F, NEAREST, manual compare in the shader (no
 * fixed-function compare). Lazy alloc: nothing until the first bake.
 *
 * The constructor is GL-free (tables only) — instances are safe to build in
 * static initializers off the GL thread. NOTE: this class deliberately does
 * NOT enforce a tile-count cap; consumers whose dirty masks are 64-bit longs
 * must guard {@code tileCount() <= 64} in their own static-init.
 */
final class DepthTileAtlas
{
    private final String debugName;
    private final int gridX;
    private final int gridY;
    private final int cellAspectX;
    private final int cellAspectY;
    private int tileSize;

    private final int tileCount;
    /** Tier t's tiles occupy the flat-index range
     *  [tierFirstTile[t], tierFirstTile[t+1]). */
    private final int[] tierFirstTile = new int[4];
    // Per-tile layout tables — deliberately tileSize-independent (the tile
    // resolution is preset-mutable at runtime; only the grid topology is static).
    private final int[] tileCellX;
    private final int[] tileCellY;
    private final int[] tileSubX;
    private final int[] tileSubY;
    private final int[] tileSizeDiv;

    private int glTextureId = 0;
    private int glFboId = 0;
    private boolean initialized = false;

    private int staticTextureId = 0;
    private int staticFboId = 0;
    private boolean staticInitialized = false;

    DepthTileAtlas(String debugName, int gridX, int gridY,
                   int cellAspectX, int cellAspectY,
                   int[] tierCells, int initialTileSize)
    {
        this.debugName = debugName;
        this.gridX = gridX;
        this.gridY = gridY;
        this.cellAspectX = cellAspectX;
        this.cellAspectY = cellAspectY;
        this.tileSize = initialTileSize;

        if (tierCells.length != 3)
        {
            throw new IllegalStateException("DepthTileAtlas(" + debugName + "): tierCells must have exactly 3 tiers, got " + tierCells.length);
        }
        int cells = tierCells[0] + tierCells[1] + tierCells[2];
        if (cells != gridX * gridY)
        {
            throw new IllegalStateException("DepthTileAtlas(" + debugName + "): tierCells sum " + cells + " != grid cells " + (gridX * gridY));
        }
        int count = tierCells[0] + tierCells[1] * 4 + tierCells[2] * 16;
        this.tileCount = count;
        this.tileCellX = new int[count];
        this.tileCellY = new int[count];
        this.tileSubX = new int[count];
        this.tileSubY = new int[count];
        this.tileSizeDiv = new int[count];

        int tile = 0;
        int cell = 0;
        for (int tier = 0; tier < 3; tier++)
        {
            this.tierFirstTile[tier] = tile;
            int div = 1 << tier; // 1 | 2 | 4
            for (int c = 0; c < tierCells[tier]; c++, cell++)
            {
                int cx = cell % gridX;
                int cy = cell / gridX;
                for (int sy = 0; sy < div; sy++)
                {
                    for (int sx = 0; sx < div; sx++, tile++)
                    {
                        this.tileCellX[tile] = cx;
                        this.tileCellY[tile] = cy;
                        this.tileSubX[tile] = sx;
                        this.tileSubY[tile] = sy;
                        this.tileSizeDiv[tile] = div;
                    }
                }
            }
        }
        this.tierFirstTile[3] = tile;
    }

    public int getTileSize()
    {
        return this.tileSize;
    }

    public int getAtlasWidth()
    {
        return this.tileSize * this.cellAspectX * this.gridX;
    }

    public int getAtlasHeight()
    {
        return this.tileSize * this.cellAspectY * this.gridY;
    }

    /** Lazy — returns 0 until the first bake allocates (keeps VRAM free when no light exists). */
    public int getGlTextureId()
    {
        return this.glTextureId;
    }

    /** FBO of the requested layer (false = live, true = static), allocating it
     *  on first use. */
    public int getFboId(boolean staticLayer)
    {
        if (staticLayer)
        {
            if (!this.staticInitialized)
            {
                this.initStatic();
            }
            return this.staticFboId;
        }
        if (!this.initialized)
        {
            this.init();
        }
        return this.glFboId;
    }

    /** Number of addressable tiles in the quadtree layout. */
    public int tileCount()
    {
        return this.tileCount;
    }

    /** First flat tile index of tier {@code t} — the tier ranges are contiguous. */
    public int tierStartTile(int t)
    {
        return this.tierFirstTile[t];
    }

    /** Number of tiles in tier {@code t}. */
    public int tierTileCount(int t)
    {
        return this.tierFirstTile[t + 1] - this.tierFirstTile[t];
    }

    /** Tier (0|1|2) of a flat tile index — a piecewise range lookup over the
     *  contiguous tier ranges (see the class layout formula). */
    public int tileTier(int tile)
    {
        if (tile < this.tierFirstTile[1])
        {
            return 0;
        }
        return tile < this.tierFirstTile[2] ? 1 : 2;
    }

    /** Pixel X of a tile's rect origin (see the class layout formula). */
    public int tilePixelX(int tile)
    {
        int cellW = this.tileSize * this.cellAspectX;
        return this.tileCellX[tile] * cellW + this.tileSubX[tile] * (cellW / this.tileSizeDiv[tile]);
    }

    /** Pixel Y of a tile's rect origin (see the class layout formula). */
    public int tilePixelY(int tile)
    {
        int cellH = this.tileSize * this.cellAspectY;
        return this.tileCellY[tile] * cellH + this.tileSubY[tile] * (cellH / this.tileSizeDiv[tile]);
    }

    /** Square unit extent of one tile: tileSize / (1|2|4). The tile's full
     *  rect is (unit * cellAspectX) x (unit * cellAspectY) pixels. */
    public int unitSizePx(int tile)
    {
        return this.tileSize / this.tileSizeDiv[tile];
    }

    /** GPU-copy one tile's full rect from the static atlas into the live
     *  atlas — restores a light's static base before its dynamic casters are
     *  drawn on top, without re-rendering any static geometry. */
    public void copyStaticToLive(int tile)
    {
        int unit = this.unitSizePx(tile);
        this.copyStaticToLiveRect(this.tilePixelX(tile), this.tilePixelY(tile),
            unit * this.cellAspectX, unit * this.cellAspectY);
    }

    /** GPU-copy an arbitrary pixel rect from the static atlas into the live
     *  atlas. Lazily allocates BOTH layers — a first-frame overlay copy must
     *  not hit textureId 0. */
    public void copyStaticToLiveRect(int x, int y, int w, int h)
    {
        if (!this.initialized)
        {
            this.init();
        }
        if (!this.staticInitialized)
        {
            this.initStatic();
        }
        GL43.glCopyImageSubData(
            this.staticTextureId, GL11.GL_TEXTURE_2D, 0, x, y, 0,
            this.glTextureId, GL11.GL_TEXTURE_2D, 0, x, y, 0,
            w, h, 1
        );
    }

    private void init()
    {
        int[] ids = this.createAtlas();
        this.glTextureId = ids[0];
        this.glFboId = ids[1];
        this.initialized = true;
        ShadowAllocLog.log(this.debugName + " live depth " + this.getAtlasWidth() + "x" + this.getAtlasHeight(),
            (long) this.getAtlasWidth() * this.getAtlasHeight() * 4L);
    }

    private void initStatic()
    {
        int[] ids = this.createAtlas();
        this.staticTextureId = ids[0];
        this.staticFboId = ids[1];
        this.staticInitialized = true;
        ShadowAllocLog.log(this.debugName + " static depth " + this.getAtlasWidth() + "x" + this.getAtlasHeight(),
            (long) this.getAtlasWidth() * this.getAtlasHeight() * 4L);
    }

    /** Allocate one depth atlas texture + FBO, cleared to far plane. Returns
     *  {textureId, fboId}; restores the GL texture/FBO bindings it touched. */
    private int[] createAtlas()
    {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        int textureId = GlStateManager._genTexture();
        GlStateManager._bindTexture(textureId);

        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
            this.getAtlasWidth(), this.getAtlasHeight(), 0,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null
        );

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);

        int fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, textureId, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            throw new IllegalStateException("DepthTileAtlas(" + this.debugName + ") FBO incomplete: 0x" + Integer.toHexString(status));
        }

        int[] prevViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        GL11.glViewport(0, 0, this.getAtlasWidth(), this.getAtlasHeight());
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GlStateManager._bindTexture(prevTex);

        return new int[] { textureId, fboId };
    }

    /** Bytes of the currently resident depth layers (0 when nothing is
     *  allocated) — feeds the preset-flip budget's "about to be freed"
     *  accounting in {@link ShadowVramBudget}. */
    public long allocatedBytes()
    {
        long layer = (long) this.getAtlasWidth() * this.getAtlasHeight() * 4L;
        long total = 0;
        if (this.glTextureId != 0)
        {
            total += layer;
        }
        if (this.staticTextureId != 0)
        {
            total += layer;
        }
        return total;
    }

    /** Frees this atlas's own textures/FBOs only — cascading dependent
     *  filter storage (pyramid/EVSM) is the owning facade's job. */
    public void delete()
    {
        // _deleteTexture (not raw glDeleteTextures): drops the id from GlStateManager's
        // per-unit binding cache — a raw delete leaves a stale entry that silently
        // skips a future bind when the driver reuses the name.
        if (this.initialized)
        {
            GlStateManager._deleteTexture(this.glTextureId);
            GL30.glDeleteFramebuffers(this.glFboId);
            this.glTextureId = 0;
            this.glFboId = 0;
            this.initialized = false;
        }
        if (this.staticInitialized)
        {
            GlStateManager._deleteTexture(this.staticTextureId);
            GL30.glDeleteFramebuffers(this.staticFboId);
            this.staticTextureId = 0;
            this.staticFboId = 0;
            this.staticInitialized = false;
        }
    }

    /** Switch tile resolution; frees this atlas's own storage and re-inits on
     *  next access. The owning facade must cascade-delete dependent filters
     *  (their level counts / temp storage derive from the tile size). */
    public void setTileSize(int newSize)
    {
        if (newSize == this.tileSize)
        {
            return;
        }
        this.tileSize = newSize;
        this.delete();
    }
}
