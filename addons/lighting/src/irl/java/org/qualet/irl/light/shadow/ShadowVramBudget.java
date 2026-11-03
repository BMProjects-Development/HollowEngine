package org.qualet.irl.light.shadow;

import org.lwjgl.opengl.GL11;

import org.lwjgl.opengl.GL;

/**
 * VRAM budgeting for the shadow stack — two levers:
 *
 * 1. APPLY-TIME CLAMP ({@link #chainBudgetBytes}): each atlas chain (point /
 *    spot) gets half of (free VRAM - reserve) at preset-apply time;
 *    {@code setTileSize} walks its power-of-two ladder down until the chain's
 *    unavoidable footprint fits. Before this clamp an ULTRA preset on a
 *    32768-limit card allocated 9.6 GiB at world join (2x 24576^2 depth
 *    atlases + full 30-lamp filter arrays) and left a 12 GiB card in
 *    permanent residency thrash.
 *
 * 2. PER-FRAME GROWTH APPROVAL ({@link #updatePointCaps}): the point filter
 *    arrays (pyramid + MSM) are demand-sized — allocated per tier, in chunks
 *    of {@link #CHUNK} blocks, only up to the highest block the allocator
 *    actually handed out. Each frame this class pre-approves at most one
 *    chunk of growth per tier against live free VRAM; ShadowBaker's
 *    acquire paths never hand out a block past the approved cap, so a
 *    filter flush can always allocate the layers behind every published
 *    block BEFORE the SSBO flush makes it sampleable. Denied growth simply
 *    shrinks the effective pool — lamps past it take the existing
 *    spare/unshadowed paths (graceful, no foreign-layer sampling).
 *
 * The rank->tier mapping (POINT_TIER_END) never changes with the caps (the
 * GLSL tier decode is a frozen ABI); only how many blocks of a tier are
 * physically backed does. All queries run on the render thread.
 */
public final class ShadowVramBudget
{
    // GL_NVX_gpu_memory_info (NVIDIA only); value arrives in KiB.
    private static final int NVX_CURRENT_AVAILABLE = 0x9049;
    private static Boolean nvxMemoryInfo;

    /** Post-allocation free-VRAM floor the budget defends (terrain, Iris
     *  buffers, other apps). */
    private static final long RESERVE_BYTES =
        Long.getLong("irlite.shadowVramReserveMb", 2560L) << 20;

    /** Test/override hook: pretend this much VRAM is free instead of querying
     *  ({@code <= 0} = disabled). */
    private static final long FORCED_FREE_BYTES;

    /** Per-chain footprint cap when free VRAM is unqueryable (non-NVIDIA):
     *  restores the pre-atlas-merge intent of "ULTRA point lands on 2048". */
    static final long FALLBACK_CHAIN_BYTES = 3072L << 20;

    /** Growth chunk per point tier, in blocks (tier block counts {2,12,16}).
     *  Tier 0 allocates whole — it is 2 blocks. */
    private static final int[] CHUNK = { 2, 3, 4 };

    /** Per-frame approved handout cap per point tier, in blocks. Starts at
     *  full capacity so a bake before the first {@link #updatePointCaps}
     *  call behaves like the pre-budget code. */
    private static final int[] approvedBlocks = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE };

    static
    {
        long forcedMb = Long.getLong("irlite.shadowVramBudgetMb", -1L);
        FORCED_FREE_BYTES = forcedMb > 0 ? forcedMb << 20 : -1L;
    }

    private ShadowVramBudget()
    {}

    /** Free VRAM in bytes, or -1 when unqueryable (non-NVIDIA / no context). */
    static long freeVramBytes()
    {
        if (FORCED_FREE_BYTES > 0)
        {
            return FORCED_FREE_BYTES;
        }
        try
        {
            if (nvxMemoryInfo == null)
            {
                nvxMemoryInfo = GL.getCapabilities().GL_NVX_gpu_memory_info;
            }
            if (nvxMemoryInfo)
            {
                return (GL11.glGetInteger(NVX_CURRENT_AVAILABLE) & 0xffffffffL) << 10;
            }
        }
        catch (Throwable ignored)
        {
            // capabilities not ready — treat as unqueryable
        }
        return -1L;
    }

    /** Apply-time budget of ONE atlas chain (point or spot): half of
     *  (free + residentSameChain - reserve), or -1 when free VRAM is
     *  unqueryable (the caller falls back to {@link #FALLBACK_CHAIN_BYTES}).
     *  The chain's currently resident textures count as available — a preset
     *  flip frees them before re-allocating, so measuring plain free would
     *  over-clamp every mid-session switch by the outgoing chain's size. */
    static long chainBudgetBytes(long residentSameChainBytes)
    {
        long free = freeVramBytes();
        if (free < 0)
        {
            return -1L;
        }
        return Math.max(0L, (free + residentSameChainBytes - RESERVE_BYTES) / 2L);
    }

    /** log2 of a power-of-two face size = the filter mip-chain level count. */
    private static int faceLevels(int face)
    {
        return Integer.numberOfTrailingZeros(Integer.highestOneBit(face));
    }

    /** Pyramid + MSM bytes of ONE point block (6 faces, full mip chains) at
     *  tier {@code tier} with tier-0 face size {@code t0Face} — must mirror
     *  the glTexStorage3D calls in PointShadowPyramid/PointShadowEvsm. */
    static long pointTierBlockBytes(int tier, int t0Face)
    {
        int face = t0Face >> tier;
        int base = face / 2;
        int levels = faceLevels(face);
        return (ShadowAllocLog.mipChainBytes(base, levels, 8L)
              + ShadowAllocLog.mipChainBytes(base, levels, 16L)) * 6L;
    }

    /** Unavoidable point-chain footprint at tier-0 face size {@code t0Face}:
     *  both depth atlas layers (the atlas is monolithic and the player is a
     *  dynamic caster, so live + static both allocate at the first bake),
     *  tier 0's filter arrays (any shadowed point lamp ranks into tier 0),
     *  and the shared blur temp. Deeper tiers grow by demand on top. */
    public static long pointMinFootprintBytes(int t0Face)
    {
        long side = 6L * t0Face;
        long depth = 2L * side * side * 4L;
        long tier0 = pointTierBlockBytes(0, t0Face) * PointDepthAtlas.tierBlockCount(0);
        long half = t0Face / 2;
        long temp = half * half * 6L * 16L;
        return depth + tier0 + temp;
    }

    /** Full spot-chain footprint at tile size {@code tile} with EVSM shift
     *  {@code evsmShift} — spot filter textures are atlas-sized (not
     *  per-lamp), so full = minimal once any spotlight exists. Mirrors the
     *  allocations of DepthTileAtlas("spot")/SpotShadowPyramid/SpotShadowEvsm. */
    public static long spotFullFootprintBytes(int tile, int evsmShift)
    {
        long atlas = 4L * tile; // 4x4 grid of tile-size cells
        long depth = 2L * atlas * atlas * 4L;
        int pyrBase = (int) (atlas / 2L);
        long pyr = ShadowAllocLog.mipChainBytes(pyrBase, pyrBase, faceLevels(pyrBase) + 1, 8L);
        int evsmBase = (int) (atlas >> evsmShift);
        int evsmLevels = Math.max(1, faceLevels(tile) - evsmShift + 1);
        long evsm = ShadowAllocLog.mipChainBytes(evsmBase, evsmBase, evsmLevels, 16L);
        long scratchSide = Math.max(1, tile >> evsmShift);
        long scratch = 2L * scratchSide * scratchSide * 16L;
        return depth + pyr + evsm + scratch;
    }

    /** Recompute the per-tier handout caps for this frame (ShadowBaker
     *  prologue, render thread, before any acquire): current physical
     *  capacity plus at most one pre-approved growth chunk per tier, granted
     *  only while live free VRAM keeps the reserve intact. Blocks already
     *  handed out past the physical capacity (a first bake throttled after a
     *  prior grant) are a COMMITMENT — the flush will back them regardless —
     *  so they floor the cap and their cost leaves the headroom up front:
     *  the cap never regresses below a handed-out block. */
    public static void updatePointCaps()
    {
        // Full physical arrays cannot grow, and the policy never shrinks them.
        // Avoid a driver free-VRAM query when its answer cannot affect any cap.
        boolean fullyBacked = true;
        for (int t = 0; t < 3; t++)
        {
            int full = PointDepthAtlas.tierBlockCount(t);
            if (physicalPointBlocks(t) < full)
            {
                fullyBacked = false;
            }
            else
            {
                approvedBlocks[t] = full;
            }
        }
        if (fullyBacked)
        {
            return;
        }
        int t0Face = PointDepthAtlas.getTileSize();
        long free = freeVramBytes();
        long headroom = free < 0 ? Long.MAX_VALUE / 4 : free - RESERVE_BYTES;
        long half = t0Face / 2;
        long tempBytes = half * half * 6L * 16L;
        // the shared blur temp allocates with the FIRST backed tier, whichever it is
        boolean tempCharged = false;
        for (int t = 0; t < 3; t++)
        {
            tempCharged |= physicalPointBlocks(t) > 0;
        }
        for (int t = 0; t < 3; t++)
        {
            int full = PointDepthAtlas.tierBlockCount(t);
            int phys = physicalPointBlocks(t);
            if (phys >= full)
            {
                approvedBlocks[t] = full;
                continue;
            }
            long blockBytes = pointTierBlockBytes(t, t0Face);
            int floor = Math.min(full, Math.max(phys, ShadowBaker.ownedPointBlocks(t)));
            long committed = (long) (floor - phys) * blockBytes;
            if (floor > phys && !tempCharged)
            {
                committed += tempBytes;
                tempCharged = true;
            }
            headroom -= committed;
            int target = Math.min(full, Math.max(floor, phys + CHUNK[t]));
            long cost = (long) (target - floor) * blockBytes;
            if (target > phys && !tempCharged)
            {
                cost += tempBytes;
            }
            if (headroom >= cost)
            {
                approvedBlocks[t] = target;
                headroom -= cost;
                if (target > phys)
                {
                    tempCharged = true;
                }
            }
            else
            {
                approvedBlocks[t] = floor;
            }
        }
    }

    /** This frame's handout/allocation cap of point tier {@code t}, in
     *  blocks. The acquire paths must not hand out a block at or past it;
     *  the filter flushes allocate exactly up to it when demand asks. */
    public static int approvedPointBlocks(int t)
    {
        return Math.min(approvedBlocks[t], PointDepthAtlas.tierBlockCount(t));
    }

    /** Blocks of tier {@code t} physically backed by BOTH filter textures.
     *  A filter whose compute programs failed to build is INERT (its flush
     *  never allocates and the GLSL falls back to PCF for every block) — it
     *  must not constrain the pool, or a single compile failure would pin
     *  all point shadows to the first growth chunk; treat it as full. */
    private static int physicalPointBlocks(int t)
    {
        int full = PointDepthAtlas.tierBlockCount(t);
        int pyr = PointShadowPyramid.inert() ? full : PointShadowPyramid.allocatedBlocks(t);
        int ev = PointShadowEvsm.inert() ? full : PointShadowEvsm.allocatedBlocks(t);
        return Math.min(pyr, ev);
    }
}
