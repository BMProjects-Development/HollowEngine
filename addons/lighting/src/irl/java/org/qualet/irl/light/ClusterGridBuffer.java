package org.qualet.irl.light;

import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Owns the GPU "cluster grid" SSBO (Phase 3 light clustering) and rasterises,
 * once per frame, a coarse screen-tile -> packed-light bitmask so the injected
 * surface shader can skip lights whose screen-space bound doesn't cover the
 * fragment's tile instead of looping every light per fragment.
 *
 * <p>std430 contract, W2 dual-region (the CR-pilot GLSL mirrors this exactly):</p>
 *
 * <pre>
 *   layout(std430, binding = BINDING) readonly buffer IrliteClusterGrid {
 *       uvec4 irlite_clusterHeader;      // x=gridX, y=gridY, z=flags (1=active, 0=fall back to full loop),
 *                                        // w=words-per-tile of the wide region (0 = legacy-only writer)
 *       uvec2 irlite_clusterMasks[576];  // LEGACY region, row-major ty*gridX+tx, ORIGIN BOTTOM-LEFT
 *                                        // (matches gl_FragCoord); bit i of .x (i&lt;32) / .y (32&lt;=i&lt;64)
 *                                        // == packed light index i in binding 7. Still dual-written so
 *                                        // old-generation packs keep culling their first 64.
 *       uint  irlite_clusterWide[];      // W2 wide region, tile-major [tile * header.w + (i&gt;&gt;5)],
 *                                        // bit (i&amp;31) == packed light index i — EVERY packed light has a bit
 *   };
 * </pre>
 *
 * <p>W2: every packed light (the PACKED index in the light SSBO, after cap /
 * SHADOW_PENDING skips) gets a wide-region bit; new-generation shaders have no
 * unmasked tail. The legacy 64-bit region keeps the first {@link #MASK_LIGHTS}
 * mirrored for old-generation packs, which still run index &gt;= 64 unmasked.</p>
 *
 * <p>Lifecycle mirrors {@link LightBuffer}: lazy GL init, off-heap scratch,
 * glBufferSubData + glBindBufferBase(binding {@value #BINDING}), and an
 * uploadEmpty that writes flags=0 so a shader that keeps the buffer bound reads a
 * dormant grid and falls back to the full loop. The snapshot is recorded during
 * {@link LightRegistry#flush} (camera-relative light positions), and projected to
 * tiles LATER, in {@link #buildAndUpload}, with the captured gbuffer matrices —
 * the modelview a fragment actually uses (bobbing included), not a hand-rolled one.</p>
 */
public final class ClusterGridBuffer
{
    public static final int BINDING = 6;
    public static final int GRID_X = 32;
    public static final int GRID_Y = 18;
    /** Width of the LEGACY uvec2 mask region: packed lights past this index have
     *  no bits THERE. Old-generation patched packs read only this region, so it
     *  stays in the layout and keeps being dual-written (first 64 bits). */
    public static final int MASK_LIGHTS = 64;
    /** Maximum words per tile in the W2 region. Every packed light gets a bit;
     *  header .w carries the current frame's compact stride, at most this value
     *  (0 remains the legacy-only writer / full-loop fallback sentinel). */
    public static final int WIDE_WORDS = (LightBuffer.MAX_LIGHTS + 31) / 32;   // 64; ceil so WIDE_LIGHTS >= MAX_LIGHTS by construction
    public static final int WIDE_LIGHTS = WIDE_WORDS * 32;                     // 2048
    /** View-space near plane used by the mask projection (the pilot's NEAR). */
    public static final float NEAR = 0.05F;

    private static final int TILE_COUNT = GRID_X * GRID_Y;   // 576
    private static final int HEADER_BYTES = 16;              // uvec4
    private static final int MASK_BYTES = TILE_COUNT * 8;    // legacy uvec2 (2×uint) per tile
    private static final int WIDE_BYTES = TILE_COUNT * WIDE_WORDS * 4;  // wide uint words per tile
    private static final int CAPACITY = HEADER_BYTES + MASK_BYTES + WIDE_BYTES;

    // Camera-inside-sphere slack: length(view) <= r * this floods every tile. A hair
    // over 1 so a fragment sitting on the sphere surface can't slip between the
    // "inside" test and the projected screen bound.
    private static final float INSIDE_SLACK = 1.05F;
    // Extra blocks added to the inside test, making it authoritative BEFORE the
    // behind-near reject: the VL march starts AT the eye, so its pre-near ray segment
    // (points within well under a block of the eye at any FOV) can pick up a sphere
    // squashed entirely into the sub-near depth slab — a case the reject alone would
    // hand zero tiles and break the VL cull's bit-identity invariant. Any sphere whose
    // surface comes within this margin of the eye floods every tile instead.
    private static final float NEAR_FLOOD_MARGIN = 1.0F;
    // Tiles of slack added on every side of the projected bound, absorbing the coarse
    // grid's quantisation so a light never under-covers the tile a fragment lands in.
    private static final int TILE_SLACK = 1;

    private static int ssbo = 0;
    private static ByteBuffer scratch = null;
    /** Cached IntBuffer view over the wide region of {@link #scratch} (created
     *  once in init — a per-frame asIntBuffer() would allocate in the frame path). */
    private static IntBuffer wideView = null;
    private static boolean initialized = false;

    // --- Runtime toggle ------------------------------------------------------
    /** Always on: the grid produces an identical image and only ever makes the
     *  shader's per-pixel light loop cheaper, so there is no user-facing knob.
     *  {@code -Dirlite.noClustering=true} forces it off for an A/B measurement
     *  (read once at class init — restart to change). */
    private static boolean enabled = !Boolean.getBoolean("irlite.noClustering");

    // --- Per-frame snapshot (recorded during LightRegistry.flush) ------------
    // Camera-relative light position + radius for EVERY packed light (W2),
    // indexed by packed index (index i == mask bit i). Projected to tiles
    // later, in buildAndUpload, against the captured gbuffer matrices.
    private static final float[] snapRx = new float[WIDE_LIGHTS];
    private static final float[] snapRy = new float[WIDE_LIGHTS];
    private static final float[] snapRz = new float[WIDE_LIGHTS];
    private static final float[] snapRadius = new float[WIDE_LIGHTS];
    private static int snapCount = 0;
    // Set by markSnapshotFresh() at flush end, cleared by buildAndUpload: the
    // frame-consistency guard so a late hook that fired without a snapshot recorded
    // THIS frame never rasterises a stale snapshot against new matrices.
    private static boolean snapshotFresh = false;

    // --- Mask accumulator (CPU-side, reused; row-major ty*GRID_X+tx) ---------
    private static final int[] maskX = new int[TILE_COUNT];  // legacy bits 0..31
    private static final int[] maskY = new int[TILE_COUNT];  // legacy bits 32..63
    // W2 words, tile-major [t * activeWords + (bit >> 5)]. Capacity stays at
    // the maximum, but only the current frame's packed prefix is touched/uploaded.
    private static final int[] wide = new int[TILE_COUNT * WIDE_WORDS];
    private static int activeWords = 1;
    // Lights covering the entire screen share one OR per word here, applied to
    // each tile after projection instead of visiting all 576 tiles per light.
    private static final int[] floodWords = new int[WIDE_WORDS];

    // Reused transform scratch — buildAndUpload stays allocation-free per frame.
    private static final Vector4f v4 = new Vector4f();

    // Last GL upload state, so the disabled hook path doesn't re-write flags=0 every
    // frame: -1 never uploaded, 0 empty (flags=0), 1 active (flags=1).
    private static final int STATE_NEVER = -1;
    private static final int STATE_EMPTY = 0;
    private static final int STATE_ACTIVE = 1;
    private static int lastUploadState = STATE_NEVER;

    private ClusterGridBuffer()
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
        scratch.position(HEADER_BYTES + MASK_BYTES);
        wideView = scratch.asIntBuffer();
        scratch.clear();

        initialized = true;
    }

    /** Enable/disable clustering at runtime. Disabled: {@link #record} stores
     *  nothing, {@link #markSnapshotFresh} leaves no fresh snapshot, and the late
     *  hook writes an empty (flags=0) header so the shader falls back to the full
     *  per-fragment loop. No consumer calls this any more — clustering is always
     *  on and the A/B path is -Dirlite.noClustering; kept as API for a host that
     *  needs to gate the grid itself (e.g. an export/replay pipeline). */
    public static void setEnabled(boolean value)
    {
        enabled = value;
        if (!value)
        {
            begin();
        }
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    /** Reset the per-frame snapshot. Called at the start of
     *  {@link LightRegistry#flush}, next to {@link LightBuffer#begin()}. */
    public static void begin()
    {
        snapCount = 0;
        snapshotFresh = false;
    }

    /** Record one packed light for this frame's mask rasterisation. {@code bit} is
     *  the packed light-SSBO index (binding 7) — the SAME pre-increment cursor used
     *  for uploadedIds — and becomes mask bit {@code bit}. Positions are
     *  camera-relative (already computed by flush); projection to tiles happens
     *  later, in {@link #buildAndUpload}. No-op when disabled or when
     *  {@code bit >= WIDE_LIGHTS} (cannot happen while WIDE_LIGHTS ==
     *  {@link LightBuffer#MAX_LIGHTS} — kept as a guard). */
    public static void record(int bit, float rx, float ry, float rz, float radius)
    {
        if (!enabled || bit < 0 || bit >= WIDE_LIGHTS)
        {
            return;
        }

        snapRx[bit] = rx;
        snapRy[bit] = ry;
        snapRz[bit] = rz;
        snapRadius[bit] = radius;
        // Packed indices arrive contiguously from 0, so bit+1 is the running count.
        if (bit + 1 > snapCount)
        {
            snapCount = bit + 1;
        }
    }

    /** Mark this frame's snapshot ready for the late hook. Called at flush end even
     *  when zero lights were recorded — flags must still become 1 (an all-zero mask
     *  is correct: the shader early-outs on count==0 anyway). No-op when disabled,
     *  so the hook takes the {@link #uploadEmpty} path instead. */
    public static void markSnapshotFresh()
    {
        if (enabled)
        {
            snapshotFresh = true;
        }
    }

    public static boolean hasFreshSnapshot()
    {
        return snapshotFresh;
    }

    /** Rasterise this frame's snapshot into the tile masks and upload with flags=1.
     *  {@code modelView} is the captured gbuffer modelview — it INCLUDES bobbing and
     *  is exactly what fragments transform with — and {@code projection} the captured
     *  gbuffer projection (Iris does not jitter it). Clears the fresh flag so a second
     *  capture this frame can't re-rasterise a consumed snapshot. */
    public static void buildAndUpload(Matrix4f modelView, Matrix4f projection)
    {
        init();

        Arrays.fill(maskX, 0);
        Arrays.fill(maskY, 0);
        activeWords = Math.max(1, (snapCount + 31) / 32);
        Arrays.fill(wide, 0, TILE_COUNT * activeWords, 0);
        Arrays.fill(floodWords, 0, activeWords, 0);

        for (int b = 0; b < snapCount; b++)
        {
            rasterizeLight(b, modelView, projection);
        }
        applyFloodWords();

        writeAndUpload(1);
        lastUploadState = STATE_ACTIVE;
        snapshotFresh = false;
    }

    /** Project light {@code bit}'s bounding sphere to tiles and set its mask bit in
     *  every covered tile. Follows the fixed design contract's mask math exactly. */
    private static void rasterizeLight(int bit, Matrix4f modelView, Matrix4f projection)
    {
        float r = snapRadius[bit];

        // view = modelView * (rx, ry, rz, 1)
        v4.set(snapRx[bit], snapRy[bit], snapRz[bit], 1F);
        modelView.transform(v4);
        float vx = v4.x, vy = v4.y, vz = v4.z;

        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len <= r * INSIDE_SLACK + NEAR_FLOOD_MARGIN)
        {
            // Camera inside (or within NEAR_FLOOD_MARGIN of) the sphere: conservatively
            // cover every tile. Deliberately checked BEFORE the behind-near reject —
            // see NEAR_FLOOD_MARGIN — so an eye-hugging sphere can never return zero
            // tiles while the VL march's pre-near segment still crosses it.
            setAllTiles(bit);
            return;
        }

        float d = -vz;
        if (d + r < NEAR)
        {
            // Sphere entirely behind the near plane and clear of the eye — cannot
            // contain any rendered fragment nor any pre-near VL ray point.
            return;
        }

        if (d - r <= NEAR)
        {
            // Sphere straddles the near plane: conservatively cover every tile.
            setAllTiles(bit);
            return;
        }

        // d - r > NEAR here, so all 8 AABB corners are in front of the near plane:
        // every clip.w > 0 and the perspective divide below is safe.
        float minNdcX = Float.POSITIVE_INFINITY, maxNdcX = Float.NEGATIVE_INFINITY;
        float minNdcY = Float.POSITIVE_INFINITY, maxNdcY = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < 8; c++)
        {
            float cx = vx + ((c & 1) == 0 ? -r : r);
            float cy = vy + ((c & 2) == 0 ? -r : r);
            float cz = vz + ((c & 4) == 0 ? -r : r);
            v4.set(cx, cy, cz, 1F);
            projection.transform(v4);
            float inv = 1F / v4.w;
            float ndcX = v4.x * inv;
            float ndcY = v4.y * inv;
            if (ndcX < minNdcX) minNdcX = ndcX;
            if (ndcX > maxNdcX) maxNdcX = ndcX;
            if (ndcY < minNdcY) minNdcY = ndcY;
            if (ndcY > maxNdcY) maxNdcY = ndcY;
        }

        float minU = minNdcX * 0.5F + 0.5F;
        float maxU = maxNdcX * 0.5F + 0.5F;
        float minV = minNdcY * 0.5F + 0.5F;
        float maxV = maxNdcY * 0.5F + 0.5F;

        if (maxU < 0F || minU > 1F || maxV < 0F || minV > 1F)
        {
            // Projected bound fully offscreen.
            return;
        }

        int tx0 = (int) Math.floor(minU * GRID_X) - TILE_SLACK;
        int tx1 = (int) Math.floor(maxU * GRID_X) + TILE_SLACK;
        int ty0 = (int) Math.floor(minV * GRID_Y) - TILE_SLACK;
        int ty1 = (int) Math.floor(maxV * GRID_Y) + TILE_SLACK;

        if (tx0 < 0) tx0 = 0;
        if (ty0 < 0) ty0 = 0;
        if (tx1 > GRID_X - 1) tx1 = GRID_X - 1;
        if (ty1 > GRID_Y - 1) ty1 = GRID_Y - 1;

        int word = bit >> 5;
        int m = 1 << (bit & 31);
        for (int ty = ty0; ty <= ty1; ty++)
        {
            int row = ty * GRID_X;
            for (int tx = tx0; tx <= tx1; tx++)
            {
                setTileBit(row + tx, word, bit, m);
            }
        }
    }

    private static void setAllTiles(int bit)
    {
        floodWords[bit >> 5] |= 1 << (bit & 31);
    }

    private static void applyFloodWords()
    {
        for (int word = 0; word < activeWords; word++)
        {
            int mask = floodWords[word];
            if (mask == 0)
            {
                continue;
            }
            for (int t = 0; t < TILE_COUNT; t++)
            {
                wide[t * activeWords + word] |= mask;
                if (word == 0)
                {
                    maskX[t] |= mask;
                }
                else if (word == 1)
                {
                    maskY[t] |= mask;
                }
            }
        }
    }

    /** OR light {@code bit}'s mask bit into tile {@code t}: always into the W2
     *  wide region, mirrored into the legacy uvec2 region for the first
     *  {@value #MASK_LIGHTS} bits (old-generation packs read only that). */
    private static void setTileBit(int t, int word, int bit, int m)
    {
        wide[t * activeWords + word] |= m;
        if (bit < 32)
        {
            maskX[t] |= m;
        }
        else if (bit < MASK_LIGHTS)
        {
            maskY[t] |= m;
        }
    }

    /** Upload header + fixed legacy region + compact wide prefix. All W2 readers
     *  address tiles through header.w; legacy readers retain the same uvec2[576]
     *  at byte 16 and never read the wide region. No shader regeneration needed. */
    private static void writeAndUpload(int flags)
    {
        scratch.clear();
        scratch.putInt(GRID_X).putInt(GRID_Y).putInt(flags).putInt(activeWords);
        for (int t = 0; t < TILE_COUNT; t++)
        {
            scratch.putInt(maskX[t]).putInt(maskY[t]);
        }
        // W2 wide region, bulk-copied through the cached IntBuffer view (a putInt
        // loop over 36,864 words would cost real per-frame CPU; a per-frame
        // asIntBuffer() would allocate). The view is anchored at the wide region's
        // fixed offset and shares the scratch buffer's native byte order.
        int wideInts = TILE_COUNT * activeWords;
        wideView.clear();
        wideView.put(wide, 0, wideInts);
        scratch.position(scratch.position() + wideInts * Integer.BYTES);
        scratch.flip();

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, scratch);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        scratch.clear();
    }

    /** Bind binding {@value #BINDING} with a flags=0 header so the surface shader
     *  reads a dormant grid and falls back to the full per-fragment loop. MUST init +
     *  bind even on the first-ever call, and here it deliberately does NOT mirror
     *  {@link LightBuffer#uploadEmpty()}'s no-op-when-uninitialised: {@code LightBuffer}
     *  can no-op because its {@link LightBuffer#upload()} rebinds binding 7 every
     *  enabled frame, but {@link #buildAndUpload} — the only OTHER binder of point
     *  {@value #BINDING} — never runs while clustering is disabled, so this is the sole
     *  path that can bind it. The CR surface shader reads irlite_clusterHeader.z from
     *  binding {@value #BINDING} UNCONDITIONALLY every frame (IRLITE_CLUSTER is an
     *  always-on compile define, no runtime gate), so a no-op while uninitialised would
     *  leave the point unbound and the header.z==0 fallback resting on GL-undefined
     *  unbound-SSBO reads — dark patches / culled lights on drivers that don't zero
     *  them, i.e. clustering-OFF rendering WORSE than the feature not existing.
     *
     *  <p>The 16-byte header upload is skipped once already EMPTY (identical bytes),
     *  but the bind runs EVERY frame the disabled path fires — point {@value #BINDING}
     *  is shared GL state, so we rebind like {@link #buildAndUpload} does rather than
     *  assume nothing else rebound the point since the last EMPTY.</p> */
    public static void uploadEmpty()
    {
        begin();
        init();

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, ssbo);

        if (lastUploadState != STATE_EMPTY)
        {
            scratch.clear();
            scratch.putInt(GRID_X).putInt(GRID_Y).putInt(0).putInt(0);
            scratch.flip();

            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, scratch);

            scratch.clear();
            lastUploadState = STATE_EMPTY;
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    public static void delete()
    {
        begin();
        if (ssbo != 0)
        {
            GL15.glDeleteBuffers(ssbo);
            ssbo = 0;
        }

        if (scratch != null)
        {
            MemoryUtil.memFree(scratch);
            scratch = null;
            wideView = null;
        }

        initialized = false;
        lastUploadState = STATE_NEVER;
    }
}
