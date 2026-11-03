package org.qualet.irl.light.shadow;

import org.lwjgl.opengl.GL11;

import org.lwjgl.opengl.GL;

/**
 * One-line telemetry for every GPU texture allocation of the shadow stack:
 * {@code [irl-core] alloc: <tag> = <MB> MiB | vram free <X> MiB}. Allocations
 * are rare (lazy init + quality flips), so this logs unconditionally — the
 * lines double as a paper trail for VRAM-exhaustion investigations (a 12 GiB
 * card observed collapsing to ~0.5 GiB free within seconds of a world join;
 * these lines attribute or acquit the shadow stack in one run).
 */
public final class ShadowAllocLog
{
    // GL_NVX_gpu_memory_info (NVIDIA only); value arrives in KiB.
    private static final int NVX_CURRENT_AVAILABLE = 0x9049;
    private static Boolean nvxMemoryInfo;

    private ShadowAllocLog()
    {}

    /** Print one allocation line. Call right after the glTexStorage/glTexImage,
     *  on the render thread (context current — the NVX query needs it). */
    public static void log(String tag, long bytes)
    {
        String free = "";
        try
        {
            if (nvxMemoryInfo == null)
            {
                nvxMemoryInfo = GL.getCapabilities().GL_NVX_gpu_memory_info;
            }
            if (nvxMemoryInfo)
            {
                long freeKb = GL11.glGetInteger(NVX_CURRENT_AVAILABLE) & 0xffffffffL;
                free = " | vram free " + (freeKb >> 10) + " MiB";
            }
        }
        catch (Throwable ignored)
        {
            // capabilities not ready / non-GL thread — drop the free column only
        }
        System.out.println("[irl-core] alloc: " + tag + " = " + (bytes >> 20) + " MiB" + free);
    }

    /** Bytes of a square mip chain: sum over levels of (base>>l)^2 * bpp. */
    public static long mipChainBytes(int base, int levels, long bpp)
    {
        long total = 0;
        for (int l = 0; l < levels; l++)
        {
            long s = Math.max(1, base >> l);
            total += s * s * bpp;
        }
        return total;
    }

    /** Bytes of a rectangular mip chain: sum over levels of (w>>l)*(h>>l)*bpp. */
    public static long mipChainBytes(int w, int h, int levels, long bpp)
    {
        long total = 0;
        for (int l = 0; l < levels; l++)
        {
            total += Math.max(1L, w >> l) * Math.max(1L, h >> l) * bpp;
        }
        return total;
    }
}
