package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

import java.util.List;

/**
 * Per-light cache of world-block shadow casters, keyed by LIGHT IDENTITY
 * (LightRegistry.id) — NOT by registry slot. IRLite reassigns registry slots
 * every frame in registration order, so a slot index is not a stable key the
 * way IRLEngine's tracker slot was; caching by slot would hand one light
 * another light's block list on a frame where the order shifted.
 *
 * Avoids re-walking each light's bbox (thousands of getBlockState calls) every
 * frame. Three invalidation triggers:
 *   1. Light moved into another 1-block cell / range crossed a whole block ->
 *      hash mismatch in getOrCompute, or host block changed (checked every
 *      frame; sub-block motion reuses the list within both snapped and host cells).
 *   2. A block in range changed -> invalidateChange(world, pos, old, new) from
 *      WorldBlockChangeMixin, via a section index (chunk-section key -> set of
 *      light ids overlapping it), gated on the swap actually altering a
 *      silhouette (invalidateAt(pos) is the ungated legacy entry point).
 *   3. Light no longer present -> retainOnly(liveIds) evicts it (and drains the
 *      whole cache when the feature is off, since liveIds is empty then).
 *
 * IRLEngine packed overlapping lamp slots into a 64-bit bitmask per section.
 * IRLite allows up to 256 lights with arbitrary long ids, so the bitmask is
 * replaced by a Long2ObjectMap<section -> LongSet of ids>.
 *
 * Cached lists are referentially stable across cache hits, which the
 * ShadowRenderer block-VBO cache relies on (same List instance -> no rebuild).
 *
 * Single-threaded: every entry point runs on the render thread (bake) or on a
 * client block update, which 1.20.1 dispatches to the render thread too — so
 * the (non-thread-safe) FastUtil maps are never touched concurrently.
 */
public final class BlockShadowCache
{
    /** Sentinel "needs recompute" hash. {@link #hash} never returns this. */
    private static final long EMPTY = 0L;

    private static final class CacheEntry
    {
        long hash;
        List<BlockShadowEntry> list;
        long[] sectionKeys;
        /** Snapped collection center + padded radius this list was collected
         *  with (the same cx/cy/cz/cr getOrCompute fed the collector). Lets
         *  invalidateAt reject an edit that lies outside the actual sphere, in a
         *  far corner of a section the coarse index merely touched. */
        float cx, cy, cz, cr;
        int hostX, hostY, hostZ;
        int minSx, minSy, minSz, maxSx, maxSy, maxSz;
    }

    private static final Long2ObjectOpenHashMap<CacheEntry> byId = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> sectionToLightIds = new Long2ObjectOpenHashMap<>();

    private BlockShadowCache()
    {
    }

    /** Worst-case distance between a light and its snapped collection center
     *  (half the 1-block cell diagonal, √3/2 ≈ 0.866), padded onto the radius
     *  so the quantized superset covers the true sphere anywhere in the cell. */
    private static final float SNAP_PAD = 0.87f;

    /** Return this light's block list, recollecting only when its collection
     *  sphere changed (hash) or a block in range was invalidated. The clamped
     *  collection radius must be passed (the caller clamps), so the section
     *  index matches the collected volume.
     *
     *  The sphere is QUANTIZED before hashing/collecting: the center snaps to
     *  the nearest block corner and the radius rounds up to a whole block,
     *  padded by the worst-case snap distance. A continuously-moving light
     *  (entity/replay-mounted, or a transform-animated model block) used to
     *  change the raw-float hash EVERY frame and re-walk ~(2r)^3 block states
     *  + rebuild its shadow VBO each time; now it re-collects only when it
     *  crosses into another snapped or host cell (and the cached list instance
     *  stays stable in between, which the VBO cache keys on). Blocks the padding
     *  pulls in past the light's range are clipped by the bake far plane. */
    public static List<BlockShadowEntry> getOrCompute(long id, ClientLevel world,
                                                      float lx, float ly, float lz, float radius)
    {
        float cx = Math.round(lx);
        float cy = Math.round(ly);
        float cz = Math.round(lz);
        float cr = (float) Math.ceil(radius) + SNAP_PAD;
        // The cell the light is actually inside (true position, NOT the snapped
        // collection center): the collector skips only this block when it emits,
        // so a light placed on / inside an emitter isn't trapped in its own map.
        int hostX = (int) Math.floor(lx);
        int hostY = (int) Math.floor(ly);
        int hostZ = (int) Math.floor(lz);

        long h = hash(cx, cy, cz, cr);
        CacheEntry e = byId.get(id);
        // round(position) can stay unchanged across a floor(position) boundary.
        if (e != null && e.hash == h && e.list != null
            && e.hostX == hostX && e.hostY == hostY && e.hostZ == hostZ)
        {
            return e.list;
        }

        List<BlockShadowEntry> fresh = BlockShadowCollector.collectForLight(world, cx, cy, cz, cr, hostX, hostY, hostZ);
        if (e == null)
        {
            e = new CacheEntry();
            byId.put(id, e);
        }
        e.list = fresh;
        e.hash = h;
        e.cx = cx;
        e.cy = cy;
        e.cz = cz;
        e.cr = cr;
        e.hostX = hostX;
        e.hostY = hostY;
        e.hostZ = hostZ;
        rebuildSectionIndex(id, e, cx, cy, cz, cr);
        return fresh;
    }

    /**
     * Mark every cached light whose collection sphere covers this position as
     * needing a recompute. Returns true if at least one light was invalidated,
     * so the caller can gate the (global) shadow rebake on a relevant change —
     * a block edit far from every lamp costs nothing.
     */
    public static boolean invalidateAt(BlockPos pos)
    {
        LongOpenHashSet ids = idsCovering(pos);
        if (ids == null)
        {
            return false;
        }
        return invalidateSphere(ids, pos);
    }

    /**
     * Change-aware variant for callers that know both sides of the edit: skips
     * the invalidation entirely when the swap provably can't alter any baked
     * silhouette ({@link BlockShadowCollector#sameSilhouette}) — grass->dirt,
     * fluid level ticks, a furnace lighting up. The silhouette test runs only
     * after the section index confirms a lamp is actually nearby, so the
     * common far-away edit still costs one hash lookup.
     */
    public static boolean invalidateChange(BlockGetter world, BlockPos pos,
                                           BlockState oldState, BlockState newState)
    {
        LongOpenHashSet ids = idsCovering(pos);
        if (ids == null)
        {
            return false;
        }
        if (BlockShadowCollector.sameSilhouette(world, pos, oldState, newState))
        {
            return false;
        }
        return invalidateSphere(ids, pos);
    }

    /** Lights whose section index touches this position, or null if none. */
    private static LongOpenHashSet idsCovering(BlockPos pos)
    {
        long key = sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        LongOpenHashSet ids = sectionToLightIds.get(key);
        return (ids == null || ids.isEmpty()) ? null : ids;
    }

    private static boolean invalidateSphere(LongOpenHashSet ids, BlockPos pos)
    {
        // Block CENTER, matching BlockShadowCollector's per-block keep test
        // (it keeps blocks whose center is within the snapped radius).
        float bx = pos.getX() + 0.5f;
        float by = pos.getY() + 0.5f;
        float bz = pos.getZ() + 0.5f;
        boolean any = false;
        for (LongIterator it = ids.iterator(); it.hasNext(); )
        {
            CacheEntry e = byId.get(it.nextLong());
            if (e == null)
            {
                continue;
            }
            // The section index is a coarse 16^3 pre-filter: an edit in a far
            // corner of a touched section can still lie outside this lamp's
            // collection sphere, where it casts no shadow into the lamp's map.
            // Reject those with the exact center+radius test the collector used,
            // so such an edit doesn't needlessly re-walk the lamp's bbox.
            float dx = bx - e.cx, dy = by - e.cy, dz = bz - e.cz;
            if (dx * dx + dy * dy + dz * dz > e.cr * e.cr)
            {
                continue;
            }
            e.hash = EMPTY;
            any = true;
        }
        return any;
    }

    /**
     * Drop cache entries (and their section bits) for ids not in {@code liveIds}.
     * An empty set drains the whole cache. Run once per bake after the light
     * loops; the matching block VBOs are freed by
     * {@link ShadowRenderer#retainBlockVbos}.
     */
    public static void retainOnly(LongSet liveIds)
    {
        if (byId.isEmpty())
        {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<CacheEntry>> it = byId.long2ObjectEntrySet().iterator();
        while (it.hasNext())
        {
            Long2ObjectMap.Entry<CacheEntry> me = it.next();
            if (!liveIds.contains(me.getLongKey()))
            {
                removeSectionKeys(me.getLongKey(), me.getValue().sectionKeys);
                it.remove();
            }
        }
    }

    private static long hash(float x, float y, float z, float r)
    {
        long h = 1469598103934665603L;
        h = (h ^ Float.floatToRawIntBits(x)) * 1099511628211L;
        h = (h ^ Float.floatToRawIntBits(y)) * 1099511628211L;
        h = (h ^ Float.floatToRawIntBits(z)) * 1099511628211L;
        h = (h ^ Float.floatToRawIntBits(r)) * 1099511628211L;
        return h == EMPTY ? 1L : h;
    }

    /** Pack a chunk-section coordinate (16^3 grid) into a single long; 21 bits
     *  per component covers far past the Overworld build limits. */
    private static long sectionKey(int sx, int sy, int sz)
    {
        return ((long) (sx & 0x1FFFFF))
            | (((long) (sy & 0x1FFFFF)) << 21)
            | (((long) (sz & 0x1FFFFF)) << 42);
    }

    /** Sections a sphere bbox touches — built from the sphere, NOT the collected
     *  block list, so a block later placed in a currently-empty section still
     *  invalidates this light. */
    private static long[] computeSections(int minSx, int minSy, int minSz,
                                          int maxSx, int maxSy, int maxSz)
    {
        int spanX = maxSx - minSx + 1;
        int spanY = maxSy - minSy + 1;
        int spanZ = maxSz - minSz + 1;
        long[] keys = new long[spanX * spanY * spanZ];
        int k = 0;
        for (int sx = minSx; sx <= maxSx; sx++)
        {
            for (int sy = minSy; sy <= maxSy; sy++)
            {
                for (int sz = minSz; sz <= maxSz; sz++)
                {
                    keys[k++] = sectionKey(sx, sy, sz);
                }
            }
        }
        return keys;
    }

    private static void rebuildSectionIndex(long id, CacheEntry e, float lx, float ly, float lz, float r)
    {
        int minSx = ((int) Math.floor(lx - r)) >> 4;
        int minSy = ((int) Math.floor(ly - r)) >> 4;
        int minSz = ((int) Math.floor(lz - r)) >> 4;
        int maxSx = ((int) Math.floor(lx + r)) >> 4;
        int maxSy = ((int) Math.floor(ly + r)) >> 4;
        int maxSz = ((int) Math.floor(lz + r)) >> 4;
        if (e.sectionKeys != null && e.minSx == minSx && e.minSy == minSy && e.minSz == minSz
            && e.maxSx == maxSx && e.maxSy == maxSy && e.maxSz == maxSz)
        {
            return;
        }

        if (e.sectionKeys != null)
        {
            removeSectionKeys(id, e.sectionKeys);
        }
        long[] newKeys = computeSections(minSx, minSy, minSz, maxSx, maxSy, maxSz);
        for (long key : newKeys)
        {
            LongOpenHashSet s = sectionToLightIds.get(key);
            if (s == null)
            {
                s = new LongOpenHashSet(4);
                sectionToLightIds.put(key, s);
            }
            s.add(id);
        }
        e.sectionKeys = newKeys;
        e.minSx = minSx;
        e.minSy = minSy;
        e.minSz = minSz;
        e.maxSx = maxSx;
        e.maxSy = maxSy;
        e.maxSz = maxSz;
    }

    private static void removeSectionKeys(long id, long[] keys)
    {
        if (keys == null)
        {
            return;
        }
        for (long key : keys)
        {
            LongOpenHashSet s = sectionToLightIds.get(key);
            if (s != null)
            {
                s.remove(id);
                if (s.isEmpty())
                {
                    sectionToLightIds.remove(key);
                }
            }
        }
    }
}
