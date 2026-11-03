package org.qualet.irl.light.shadow;

/**
 * Packed TILE-LOCAL pixel rect for the partial-tile overlay path: 4 x 16-bit
 * fields, {@code x0<<48 | y0<<32 | x1<<16 | y1}, x1/y1 EXCLUSIVE (all
 * coordinates fit: tiles are at most 4096 px). {@link #FULL} (-1) is the
 * sentinel "whole tile region" — union with anything stays FULL, and every
 * consumer treats it as the pre-partial full rebuild. There is no EMPTY:
 * absent state is expressed by the callers (a missing map key / a clear
 * dirty-mask bit), never by a rect value.
 */
final class ShadowRect
{
    /** Whole-tile sentinel; also the safe fallback for any bailed estimate. */
    static final long FULL = -1L;

    private ShadowRect()
    {}

    static long pack(int x0, int y0, int x1, int y1)
    {
        return ((long) x0 << 48) | ((long) y0 << 32) | ((long) x1 << 16) | (long) y1;
    }

    static int x0(long r)
    {
        return (int) (r >>> 48);
    }

    static int y0(long r)
    {
        return (int) (r >>> 32) & 0xFFFF;
    }

    static int x1(long r)
    {
        return (int) (r >>> 16) & 0xFFFF;
    }

    static int y1(long r)
    {
        return (int) r & 0xFFFF;
    }

    /** Union of two rects; FULL absorbs. Both inputs must be valid rects. */
    static long union(long a, long b)
    {
        if (a == FULL || b == FULL)
        {
            return FULL;
        }
        return pack(Math.min(x0(a), x0(b)), Math.min(y0(a), y0(b)),
                    Math.max(x1(a), x1(b)), Math.max(y1(a), y1(b)));
    }

    /** True when {@code r} covers at least {@code shareNum/shareDen} of a
     *  {@code ts x ts} tile — partial dispatch stops paying off, use FULL. */
    static boolean coversMost(long r, int ts, int shareNum, int shareDen)
    {
        if (r == FULL)
        {
            return true;
        }
        long area = (long) (x1(r) - x0(r)) * (y1(r) - y0(r));
        return area * shareDen >= (long) ts * ts * shareNum;
    }
}
