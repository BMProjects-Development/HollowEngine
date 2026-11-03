package org.qualet.irl.light;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Owns the GPU light SSBO and packs collected lights into it each frame.
 *
 * std430 contract (the patcher's GLSL must mirror this exactly):
 *
 *   struct Light {
 *       vec4 posRadius;       // xyz = position RELATIVE TO CAMERA (eye), w = radius
 *       vec4 colorIntensity;  // rgb = linear color, a = intensity
 *       vec4 dirType;         // xyz = direction (spot, normalized), w = type (0 point, 1 spot)
 *       vec4 cone;            // x = cos(outerAngle/2), y = cos(innerAngle/2), z = lightMask (0 all, 1 entities only, 2 blocks only), w = bulbSize (0 = use global)
 *       vec4 vlParams;        // x = anisotropy (HG g), y = vlDensity, z = beamStrength, w = shadowTile (-1 = none; spot: atlas tile, point: PointDepthAtlas block)
 *       vec4 cookie;          // x = gobo layer (-1 = none), y = rotation (rad), z = scale, w = flags (bit0 = invert) — spot-only projected mask
 *   };
 *
 *   layout(std430, binding = BINDING) buffer IrliteLights {
 *       uint irlite_lightCount;
 *       float irlite_vlIntensityRt;
 *       uint irlite_vlFlagsRt;      // bit0 = VL shadows enabled, bit1 = VL noise enabled
 *       uint _pad2;
 *       Light irlite_lights[];
 *   };
 */
public final class LightBuffer
{
    public static final int BINDING = 7;
    // Hard ceiling on simultaneous lights in the SSBO. Raised to 2048 to back the
    // editor's "Max sources" slider (auto block-lights). NOTE: the injected GLSL
    // loops over irlite_lightCount PER FRAGMENT, so the real-world limit is
    // performance, not this number — pushing the slider into the hundreds/thousands
    // gets very expensive. The shader array is unbounded (std430 []), so no pack
    // needs regenerating for a larger ceiling. SSBO size = 16 + 2048*80 ≈ 164 KB.
    public static final int MAX_LIGHTS = 2048;

    private static final int HEADER_BYTES = 16;     // uint count + float vlIntensity + uint vlFlags + 4 B pad (std430 vec4 align)
    private static final int LIGHT_BYTES = 96;      // 6 × vec4
    private static final int CAPACITY = HEADER_BYTES + MAX_LIGHTS * LIGHT_BYTES;

    private static int ssbo = 0;
    private static ByteBuffer scratch = null;
    private static boolean initialized = false;
    private static int count = 0;
    private static float vlGlobalIntensity = 1F;
    private static int vlFlags = 0x3;

    private LightBuffer()
    {}

    private static void init()
    {
        if (initialized)
        {
            return;
        }

        ssbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, CAPACITY, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        scratch = MemoryUtil.memAlloc(CAPACITY);

        initialized = true;
    }

    public static void begin()
    {
        if (!initialized)
        {
            init();
        }

        count = 0;
        LightProfilesBuffer.begin();
        scratch.clear();
        scratch.position(HEADER_BYTES);
    }

    public static void addPoint(float x, float y, float z, float r, float g, float b, float intensity, float radius, float lightMask, float anisotropy, float density, float beam, float shadowTile, float bulbSize)
    {
        if (count >= MAX_LIGHTS)
        {
            return;
        }

        scratch.putFloat(x).putFloat(y).putFloat(z).putFloat(radius);
        scratch.putFloat(r).putFloat(g).putFloat(b).putFloat(intensity);
        scratch.putFloat(0F).putFloat(0F).putFloat(0F).putFloat(0F);  // dir=0, type=point
        scratch.putFloat(1F).putFloat(1F).putFloat(lightMask).putFloat(bulbSize);  // cone: full, z=lightMask (0 all/1 entities/2 blocks), w=bulbSize
        scratch.putFloat(anisotropy).putFloat(density).putFloat(beam).putFloat(shadowTile);
        scratch.putFloat(-1F).putFloat(0F).putFloat(1F).putFloat(0F);  // cookie: none (gobo is spot-only)

        count++;
    }

    public static void addSpot(float x, float y, float z, float dx, float dy, float dz, float r, float g, float b, float intensity, float radius, float cosOuter, float cosInner, float lightMask, float anisotropy, float density, float beam, float shadowTile, float bulbSize, float cookieLayer, float cookieRot, float cookieScale, float cookieFlags)
    {
        if (count >= MAX_LIGHTS)
        {
            return;
        }

        scratch.putFloat(x).putFloat(y).putFloat(z).putFloat(radius);
        scratch.putFloat(r).putFloat(g).putFloat(b).putFloat(intensity);
        scratch.putFloat(dx).putFloat(dy).putFloat(dz).putFloat(1F);   // type=spot
        scratch.putFloat(cosOuter).putFloat(cosInner).putFloat(lightMask).putFloat(bulbSize);  // z=lightMask (0 all/1 entities/2 blocks), w=bulbSize
        scratch.putFloat(anisotropy).putFloat(density).putFloat(beam).putFloat(shadowTile);
        scratch.putFloat(cookieLayer).putFloat(cookieRot).putFloat(cookieScale).putFloat(cookieFlags);  // cookie: x=gobo layer(-1 none), y=rot, z=scale, w=flags

        count++;
    }

    /** Global VL intensity multiplier written into the SSBO header at offset 4
     *  on every upload. Clamped to &gt;= 1e-6 so a stored exact 0.0 can only mean
     *  the header was never written, letting the shader fall back to its
     *  compile-time default. */
    public static void setVlGlobalIntensity(float v)
    {
        vlGlobalIntensity = v >= 1e-6F ? v : 1e-6F;
    }

    /** Runtime VL feature flags written into the SSBO header at offset 8 on
     *  every upload: bit0 = VL shadows enabled, bit1 = VL noise enabled.
     *  Defaults to all-on so shaders behave normally before the first push. */
    public static void setVlFlags(int flags)
    {
        vlFlags = flags;
    }

    public static void upload()
    {
        if (!initialized)
        {
            return;
        }

        scratch.putInt(0, count);
        scratch.putFloat(4, vlGlobalIntensity);
        scratch.putInt(8, vlFlags);
        scratch.putInt(12, LightProfilesBuffer.MAGIC);

        int used = scratch.position();
        scratch.position(0);
        scratch.limit(used);

        // BindBufferBase also sets the generic target used by BufferSubData.
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);
        LightProfilesBuffer.prepare(count);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, scratch);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        scratch.clear();

        VlGlobalsBuffer.upload();
    }

    /** Zero the GPU-side light count so a pipeline (re)enabled later can't
     *  read a stale light set out of the SSBO. No-op if the buffer was never
     *  created — this path must not allocate anything. */
    public static void uploadEmpty()
    {
        if (!initialized)
        {
            return;
        }

        begin();
        upload();
    }

    public static void delete()
    {
        if (ssbo != 0)
        {
            GL15.glDeleteBuffers(ssbo);
            ssbo = 0;
        }

        if (scratch != null)
        {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }

        initialized = false;

        VlGlobalsBuffer.delete();
        LightProfilesBuffer.delete();
    }

    static void addProfile(LightProfile profile)
    {
        LightProfilesBuffer.add(profile);
    }
}
