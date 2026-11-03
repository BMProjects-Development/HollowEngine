package org.qualet.irl.light;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageResize;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Shared GL half of a {@code GL_TEXTURE_2D_ARRAY} of grayscale gobo/cookie masks —
 * one layer per loaded image — bound into every Iris program as {@code irl_cookieArray}
 * (see the per-mod {@code ProgramSamplersBuilderMixin} + {@code SamplerBindingCubeArrayMixin}).
 *
 * <p>The spot shader projects a fragment into the light's frustum and multiplies
 * the light by the sampled luminance (white = pass, black = block) — a projected
 * mask, NOT a shadow: no depth, no bake, one texture tap.</p>
 *
 * <p>This base owns only the crash-prone, source-agnostic parts: the array texture
 * (allocated lazily at {@link #RES} square, single channel R8, {@code CLAMP_TO_BORDER}
 * black), the guarded per-layer upload, and the pure STB decode/resample. Where the
 * bytes come from and how layers are cached/evicted is the subclass's business —
 * it decides the array depth via the constructor and drives {@link #uploadLayer}.</p>
 */
public abstract class CookieArrayBase
{
    /** Per-layer square resolution; loaded images are resampled to this. */
    public static final int RES = 512;

    /** Array depth: distinct cookie layers the texture is allocated with. */
    private final int layerCount;

    private int glTextureId = 0;
    private boolean initialized = false;

    protected CookieArrayBase(int layerCount)
    {
        this.layerCount = layerCount;
    }

    /** Lazy — 0 until the first cookie is uploaded (no VRAM if unused). */
    protected final int textureId()
    {
        return glTextureId;
    }

    /** Decode + resample a raw image byte array to a RES*RES single-channel buffer.
     *  Returns null on decode failure; the caller owns/frees the returned buffer and,
     *  on null, may read {@link STBImage#stbi_failure_reason()} for the log message
     *  (still valid until the next STB call). Force 1 channel (grayscale). */
    public static ByteBuffer decode(byte[] raw)
    {
        ByteBuffer rawBuf = MemoryUtil.memAlloc(raw.length);
        rawBuf.put(raw).flip();

        ByteBuffer img = null;
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            img = STBImage.stbi_load_from_memory(rawBuf, w, h, c, 1);   // force 1 channel (grayscale)
            if (img == null)
            {
                return null;
            }

            ByteBuffer resized = MemoryUtil.memAlloc(RES * RES);
            STBImageResize.stbir_resize_uint8(img, w.get(0), h.get(0), 0, resized, RES, RES, 0, 1);
            flipHorizontal(resized);   // see flipHorizontal: cancels the left-handed X of the spot projection so the gobo isn't mirrored
            return resized;
        }
        finally
        {
            if (img != null)
            {
                STBImage.stbi_image_free(img);
            }
            MemoryUtil.memFree(rawBuf);
        }
    }

    /** Mirror a RES*RES single-channel buffer left-to-right, in place.
     *
     *  <p>The spot cookie projection ({@code irlite_cookie} in {@code irlite_lights.glsl})
     *  builds a left-handed frustum basis ({@code s = cross(ld, up)}, {@code u = cross(s, ld)}
     *  give {@code s x u = -ld}), so the projected X is mirrored and a raw gobo would land
     *  flipped versus its source image. We correct it once on the stored texture — every pack
     *  and both mods sample the same {@code irl_cookieArray} — instead of negating the
     *  projected X in each pack's GLSL. Absolute get/put only, so the buffer's position/limit
     *  stay put for {@link #uploadLayer}.</p> */
    private static void flipHorizontal(ByteBuffer buf)
    {
        for (int y = 0; y < RES; y++)
        {
            int row = y * RES;
            for (int xl = 0, xr = RES - 1; xl < xr; xl++, xr--)
            {
                int l = row + xl;
                int r = row + xr;
                byte t = buf.get(l);
                buf.put(l, buf.get(r));
                buf.put(r, t);
            }
        }
    }

    /** Upload one RES*RES grayscale buffer into the given layer, allocating the
     *  array on first use. Saves and restores the GL state it touches.
     *
     *  <p>We run inside Minecraft / Sodium, which routinely leave a
     *  {@code GL_PIXEL_UNPACK_BUFFER} (PBO) bound and the pixel-store unpack params
     *  non-default. With a PBO bound, our client {@link ByteBuffer} pointer is
     *  reinterpreted by the driver as an offset INTO that PBO and it reads out of
     *  bounds — a native {@code EXCEPTION_ACCESS_VIOLATION} crash; a stale
     *  {@code UNPACK_ROW_LENGTH}/{@code SKIP_*} skews the image instead. So force a
     *  clean, tightly-packed client-memory upload (no PBO, alignment 1, no row/skip),
     *  then restore the prior state.</p> */
    protected final void uploadLayer(ByteBuffer pixels, int layer)
    {
        int prevTex = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        int prevPbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int prevRowLen = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int prevSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int prevSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int prevImgHeight = GL11.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT);
        int prevSkipImages = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES);

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);

        if (!initialized)
        {
            init();
        }
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);

        GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, RES, RES, 1,
            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prevTex);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, prevPbo);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRowLen);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevSkipRows);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, prevImgHeight);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, prevSkipImages);
    }

    private void init()
    {
        int prev = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D_ARRAY);
        glTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, glTextureId);

        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_R8, RES, RES, layerCount, 0,
            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            FloatBuffer border = stack.floats(0f, 0f, 0f, 0f);   // outside the image = black = blocked
            GL11.glTexParameterfv(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_BORDER_COLOR, border);
        }

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, prev);
        initialized = true;
    }

    /** Free the GL texture, so a next {@link #uploadLayer} re-allocates the array.
     *  Subclasses that keep a resolve cache should clear it around this. */
    protected final void deleteTexture()
    {
        if (initialized)
        {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
            initialized = false;
        }
    }
}
