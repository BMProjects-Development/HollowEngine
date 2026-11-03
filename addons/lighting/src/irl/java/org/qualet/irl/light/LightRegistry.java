package org.qualet.irl.light;

import java.util.Arrays;

/**
 * Per-frame accumulator for all collected lights, from two sources:
 *  - the scanner (ModelBlocks) at renderWorld HEAD, and
 *  - the form render-path (live actors / film replays) during world render.
 *
 * Single buffer: {@link #flush()} (called at HEAD after the scanner) packs the
 * current set into {@link LightBuffer} and clears it. Render-path registrations
 * land after the flush and are uploaded on the next frame's flush (one frame
 * stale — acceptable for moving lights). Dedup by identity keeps a light that
 * gets rendered more than once per frame from registering twice.
 */
public final class LightRegistry
{
    private static final int MAX = LightBuffer.MAX_LIGHTS;

    private static final int[] type = new int[MAX];
    private static final LightProfile[] profiles = new LightProfile[MAX];
    private static final boolean[] hasProfile = new boolean[MAX];
    // Absolute world positions, kept in DOUBLE so a light far from origin (e.g.
    // X=100000) is not float-quantized before the camera-relative flush subtracts
    // the eye. getX/Y/Z narrow to float for the shadow baker (translation-invariant,
    // see flush); only the small post-subtraction residual is cast to float.
    private static final double[] px = new double[MAX];
    private static final double[] py = new double[MAX];
    private static final double[] pz = new double[MAX];
    private static final float[] cr = new float[MAX];
    private static final float[] cg = new float[MAX];
    private static final float[] cb = new float[MAX];
    private static final float[] intensity = new float[MAX];
    private static final float[] radius = new float[MAX];
    private static final float[] dx = new float[MAX];
    private static final float[] dy = new float[MAX];
    private static final float[] dz = new float[MAX];
    private static final float[] cosOuter = new float[MAX];
    private static final float[] cosInner = new float[MAX];
    private static final boolean[] entitiesOnly = new boolean[MAX];
    private static final boolean[] blocksOnly = new boolean[MAX];
    private static final float[] anisotropy = new float[MAX];
    private static final float[] density = new float[MAX];
    private static final float[] beam = new float[MAX];
    private static final float[] bulbSize = new float[MAX];
    // Spot gobo/cookie (-1 layer = none); see LightBuffer cookie vec4. Point lights ignore these.
    private static final float[] cookieLayer = new float[MAX];
    private static final float[] cookieRot = new float[MAX];
    private static final float[] cookieScale = new float[MAX];
    private static final float[] cookieFlags = new float[MAX];
    private static final boolean[] shadows = new boolean[MAX];
    private static final int[] shadowTile = new int[MAX];
    private static final long[] id = new long[MAX];

    // Open-addressing dedup index (identity -> slot), replacing the O(count) linear
    // scan in slot(). Capacity is 2x MAX (power of two, load <= 0.5) so linear probing
    // always lands on an empty cell without wrap pressure. Occupancy is tracked by
    // stamp[j] == generation rather than clearing the table each frame: the per-frame
    // reset in clear()/flush() just bumps generation, invalidating every cell in O(1)
    // (a full stamp wipe happens only on the ~2-billion-frame int wrap).
    private static final int HASH_CAP = 4096;
    private static final long[] keys = new long[HASH_CAP];
    private static final int[] slots = new int[HASH_CAP];
    private static final int[] stamp = new int[HASH_CAP];
    private static int generation = 1;

    private static int count;

    // --- Upload priority + cap (perf C1) -------------------------------------
    // prioritize() ranks the registered lights by a camera-distance score and
    // flush() packs only the top uploadCap into the SSBO. The full set stays
    // registered — the shadow baker and its sticky per-id caches still see every
    // light — so the cap only trims the per-fragment shader loop, never the shadow
    // bookkeeping. order[0,count) is the ranked index list, valid only while
    // orderedCount == count (else callers fall back to the identity order).
    private static final int[] order = new int[MAX];
    private static final int[] orderScratch = new int[MAX];
    private static final double[] priorityScore = new double[MAX];
    private static int orderedCount = -1;

    /** Blocks of score slack given to a light already uploaded last frame, so a
     *  light hovering at the cap boundary stays uploaded instead of flickering in
     *  and out of the SSBO as its rank crosses the cut each frame. */
    private static final double HYSTERESIS = 8.0;

    /** Blocks of score slack given to a light with a volumetric beam. A beam is
     *  visible from far beyond the light's surface reach, so dropping it at the
     *  cap boundary pops the whole light shaft; bias beam lights to be cut last. */
    private static final double BEAM_BONUS = 64.0;

    /** {@code <= 0} uploads every registered light; a positive value caps the SSBO
     *  upload at that many highest-priority lights. Set per frame by the mod from
     *  its "max shader lights" setting; the core default (no cap) keeps the
     *  editor's behavior unchanged. Registration and the shadow caches are never
     *  affected — only the shader upload is trimmed. */
    private static int uploadCap = 0;

    /** Ids packed by the last flush, sorted ascending for the hysteresis
     *  binarySearch in prioritize(). Reset in clear() and rebuilt every flush. */
    private static final long[] uploadedIds = new long[MAX];
    private static int uploadedCount;

    private LightRegistry()
    {}

    /** Float-position overload (kept for ABI); widens to the double-position
     *  {@code registerPoint} so the far-from-origin precision win is available to
     *  callers that already narrowed. */
    public static void registerPoint(float x, float y, float z, float r, float g, float b, float in, float rad, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, long identity)
    {
        registerPoint((double) x, (double) y, (double) z, r, g, b, in, rad, eOnly, bOnly, aniso, dens, bm, bulb, castsShadows, identity);
    }

    /** Register a point light at an absolute world position kept in DOUBLE, so a
     *  light far from origin is not float-quantized before the camera-relative
     *  {@link #flush(double, double, double)} subtracts the eye. */
    public static void registerPoint(double x, double y, double z, float r, float g, float b, float in, float rad, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, long identity)
    {
        int i = slot(identity);
        if (i < 0)
        {
            return;
        }

        type[i] = 0;
        hasProfile[i] = false;
        px[i] = x; py[i] = y; pz[i] = z;
        cr[i] = r; cg[i] = g; cb[i] = b;
        intensity[i] = in; radius[i] = rad;
        dx[i] = 0F; dy[i] = 0F; dz[i] = 0F;
        cosOuter[i] = 1F; cosInner[i] = 1F;
        entitiesOnly[i] = eOnly; blocksOnly[i] = bOnly;
        anisotropy[i] = aniso; density[i] = dens; beam[i] = bm;
        bulbSize[i] = bulb; shadows[i] = castsShadows;
    }

    /** No-cookie float overload (the BBS addon's call path): delegates with the gobo
     *  disabled (layer -1). Keeps the addon ABI stable across the cookie struct bump. */
    public static void registerSpot(float x, float y, float z, float ndx, float ndy, float ndz, float r, float g, float b, float in, float range, float cosO, float cosI, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, long identity)
    {
        registerSpot(x, y, z, ndx, ndy, ndz, r, g, b, in, range, cosO, cosI, eOnly, bOnly, aniso, dens, bm, bulb, castsShadows, -1F, 0F, 1F, 0F, identity);
    }

    /** No-cookie double overload: same gobo-disabled delegation, keeping the absolute
     *  position in double (render-path spot lights far from origin). */
    public static void registerSpot(double x, double y, double z, float ndx, float ndy, float ndz, float r, float g, float b, float in, float range, float cosO, float cosI, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, long identity)
    {
        registerSpot(x, y, z, ndx, ndy, ndz, r, g, b, in, range, cosO, cosI, eOnly, bOnly, aniso, dens, bm, bulb, castsShadows, -1F, 0F, 1F, 0F, identity);
    }

    /** Cookie float overload (kept for ABI); widens to the double-position cookie overload. */
    public static void registerSpot(float x, float y, float z, float ndx, float ndy, float ndz, float r, float g, float b, float in, float range, float cosO, float cosI, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, float cLayer, float cRot, float cScale, float cFlags, long identity)
    {
        registerSpot((double) x, (double) y, (double) z, ndx, ndy, ndz, r, g, b, in, range, cosO, cosI, eOnly, bOnly, aniso, dens, bm, bulb, castsShadows, cLayer, cRot, cScale, cFlags, identity);
    }

    /** Register a spot light at an absolute world position kept in DOUBLE (see
     *  {@link #registerPoint(double, double, double, float, float, float, float, float, boolean, boolean, float, float, float, float, boolean, long)}).
     *  Directions are unit vectors and are NOT affected by the far-from-origin loss. */
    public static void registerSpot(double x, double y, double z, float ndx, float ndy, float ndz, float r, float g, float b, float in, float range, float cosO, float cosI, boolean eOnly, boolean bOnly, float aniso, float dens, float bm, float bulb, boolean castsShadows, float cLayer, float cRot, float cScale, float cFlags, long identity)
    {
        int i = slot(identity);
        if (i < 0)
        {
            return;
        }

        type[i] = 1;
        hasProfile[i] = false;
        px[i] = x; py[i] = y; pz[i] = z;
        cr[i] = r; cg[i] = g; cb[i] = b;
        intensity[i] = in; radius[i] = range;
        dx[i] = ndx; dy[i] = ndy; dz[i] = ndz;
        cosOuter[i] = cosO; cosInner[i] = cosI;
        entitiesOnly[i] = eOnly; blocksOnly[i] = bOnly;
        anisotropy[i] = aniso; density[i] = dens; beam[i] = bm;
        bulbSize[i] = bulb; shadows[i] = castsShadows;
        cookieLayer[i] = cLayer; cookieRot[i] = cRot; cookieScale[i] = cScale; cookieFlags[i] = cFlags;
    }

    /** Returns the slot for this identity (existing = overwrite, else a new one), or -1 if full.
     *  Open-addressing hash lookup (linear probing) over {@link #keys}/{@link #slots};
     *  an identity already seen this frame maps back to its slot so a form rendered more
     *  than once per frame overwrites in place. Two distinct forms sharing an
     *  identityHashCode collide onto one slot exactly as the old linear scan did. */
    private static int slot(long identity)
    {
        final int mask = HASH_CAP - 1;
        int j = mix64(identity) & mask;
        // Load factor <= 0.5 guarantees an empty cell, so this bounded probe never wraps
        // past every cell; the bound is a safety net for the impossible full-table case.
        for (int probe = 0; probe < HASH_CAP; probe++)
        {
            if (stamp[j] != generation)
            {
                // Empty cell for this generation: identity is new.
                if (count >= MAX)
                {
                    return -1;
                }

                int i = count++;
                id[i] = identity;
                shadowTile[i] = -1;
                cookieLayer[i] = -1F;
                cookieScale[i] = 1F;

                keys[j] = identity;
                slots[j] = i;
                stamp[j] = generation;
                return i;
            }
            if (keys[j] == identity)
            {
                return slots[j];
            }
            j = (j + 1) & mask;
        }
        return -1;
    }

    /** murmur3 fmix64 finalizer — spreads identityHashCode's low-entropy bits across the
     *  low 12 index bits so linear probing keeps short runs. */
    private static int mix64(long k)
    {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k;
    }

    /** Copies overrides into an already registered source, including deduplicated registrations. */
    public static void setProfile(long identity, LightProfile profile)
    {
        int j = mix64(identity) & (HASH_CAP - 1);
        for (int probe = 0; probe < HASH_CAP && stamp[j] == generation; probe++)
        {
            if (keys[j] == identity)
            {
                int i = slots[j];
                hasProfile[i] = profile != null;
                if (profile != null)
                {
                    if (profiles[i] == null) profiles[i] = new LightProfile();
                    profiles[i].copyFrom(profile);
                }
                return;
            }
            j = (j + 1) & (HASH_CAP - 1);
        }
    }

    // --- accessors for the shadow baker (iterate spots, assign tiles) ---

    public static int getCount()
    {
        return count;
    }

    public static int getType(int i)
    {
        return type[i];
    }

    // Absolute positions narrowed to float for the shadow baker. The bake is
    // distance/translation-invariant, so the far-from-origin residual is sub-block
    // and irrelevant to the cull + block sampling (see the migration note); the
    // camera-relative SSBO residual stays double-precise via flush().
    public static float getX(int i) { return (float) px[i]; }
    public static float getY(int i) { return (float) py[i]; }
    public static float getZ(int i) { return (float) pz[i]; }

    // Double-precision positions for the shadow bake's per-pass anchor + view eye
    // (ShadowRenderer.beginSpot/beginPointFace). The float getX/Y/Z above are
    // ~7.8mm pre-quantized at X=1e5 and must NOT source the eye; the anchor
    // A = round((float) getXd) == round(getX) keeps lockstep with BlockShadowCache's
    // block snap, while eye = L - A carries the full-precision sub-block light motion.
    public static double getXd(int i) { return px[i]; }
    public static double getYd(int i) { return py[i]; }
    public static double getZd(int i) { return pz[i]; }
    public static float getDirX(int i) { return dx[i]; }
    public static float getDirY(int i) { return dy[i]; }
    public static float getDirZ(int i) { return dz[i]; }
    public static float getRange(int i) { return radius[i]; }
    public static float getCosOuter(int i) { return cosOuter[i]; }
    public static boolean getShadows(int i) { return shadows[i]; }

    /** Stable per-light identity (System.identityHashCode of the form). Used as
     *  the key for the block-shadow + VBO caches, since registry slots are
     *  reassigned every frame and aren't stable. */
    public static long getId(int i) { return id[i]; }

    /** {@link #shadowTile} sentinel: this light's shadow bake was throttled this
     *  frame (C2 cold-start / churn cap) and it owns no ready map, so
     *  {@link #flush(double, double, double)} OMITS it from the SSBO entirely
     *  rather than uploading it unshadowed — a shadow-caster with no map would
     *  otherwise light through walls until it bakes. Distinct from -1 (a light
     *  that legitimately casts no shadow — shadows toggled off, behind the camera,
     *  nothing in range — which correctly uploads fully lit). Re-initialised to -1
     *  whenever a slot is (re)allocated in {@link #slot(long)}, so it never
     *  persists past the frame that set it. */
    private static final int SHADOW_PENDING = -2;

    public static void setShadowTile(int i, int tile)
    {
        if (i >= 0 && i < count)
        {
            shadowTile[i] = tile;
        }
    }

    /** Mark this light's shadow as pending: its bake was throttled this frame
     *  (C2) and it owns no ready map, so {@link #flush(double, double, double)}
     *  skips it instead of uploading it unshadowed (which would leak through
     *  walls). The light re-bakes and reappears within a frame or two,
     *  nearest-first (C1). See {@link #SHADOW_PENDING}. */
    public static void setShadowPending(int i)
    {
        if (i >= 0 && i < count)
        {
            shadowTile[i] = SHADOW_PENDING;
        }
    }

    /** Drop everything accumulated for this frame without touching the GPU.
     *  Used while shaders are off: the form render-path keeps registering
     *  lights (it runs regardless of Iris), but there is no consumer, and
     *  without a per-frame reset stale entries would linger until the next
     *  flush re-uploaded them. */
    public static void clear()
    {
        count = 0;
        orderedCount = -1;
        uploadedCount = 0;
        resetIndex();
    }

    /** Invalidate every dedup-index cell for the next frame in O(1) by bumping the
     *  generation stamp. On the (~2-billion-frame) int wrap, wipe the stamps once and
     *  restart at 1 so a stale stamp can never alias the live generation. */
    private static void resetIndex()
    {
        if (generation == Integer.MAX_VALUE)
        {
            Arrays.fill(stamp, 0);
            generation = 1;
        }
        else
        {
            generation++;
        }
    }

    // --- Upload priority ordering (perf C1) ----------------------------------

    /** Rank the registered lights for this frame's SSBO upload. Builds
     *  {@link #order}[0,count) as light indices sorted by ascending priority
     *  score {@code s = max(0, dist(cam, light) - radius)} (nearest surface
     *  first), with a {@link #HYSTERESIS} discount for lights uploaded last frame
     *  so the cap boundary doesn't flicker, and a {@link #BEAM_BONUS} discount for
     *  volumetric-beam lights so a far-visible shaft is cut last; ties break by id
     *  for a frame-stable order. Call once per frame (after collect, before the shadow bake) so the
     *  baker and the {@link #flush(double, double, double)} cap both walk lights
     *  in priority order. */
    public static void prioritize(double camX, double camY, double camZ)
    {
        buildOrder(camX, camY, camZ);
    }

    /** The {@code k}-th light index in priority order, or {@code k} itself
     *  (identity order) when {@link #order} is stale — a registration happened
     *  since the last {@link #prioritize}, or it was never called. */
    public static int orderedIndex(int k)
    {
        return orderedCount == count ? order[k] : k;
    }

    /** Cap how many lights {@link #flush(double, double, double)} packs into the
     *  SSBO to the top {@code cap} by priority; {@code <= 0} uploads them all.
     *  Only the shader upload is limited — every light stays registered and keeps
     *  its shadow tile + per-id caches, so shadows are unaffected. */
    public static void setUploadCap(int cap)
    {
        uploadCap = cap;
    }

    /** How many lights the last {@link #flush(double, double, double)} actually
     *  packed into the SSBO (after the upload cap and bake-throttle omissions).
     *  Telemetry: distinguishes "registered" from "paid for by the shader loop". */
    public static int getUploadedCount()
    {
        return uploadedCount;
    }

    /** Fill {@link #order}[0,count) with light indices sorted by ascending
     *  priority score (see {@link #prioritize}); scores are computed in double so
     *  a light far from origin keeps full distance precision. */
    private static void buildOrder(double camX, double camY, double camZ)
    {
        int n = count;
        for (int i = 0; i < n; i++)
        {
            double ddx = px[i] - camX;
            double ddy = py[i] - camY;
            double ddz = pz[i] - camZ;
            double s = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) - radius[i];
            if (s < 0.0)
            {
                s = 0.0;
            }
            if (wasUploaded(id[i]))
            {
                s -= HYSTERESIS;
            }
            if (beam[i] > 0.0F)
            {
                s -= BEAM_BONUS;
            }
            // Deliberately NOT clamped to zero after the discounts: the score is
            // only ever a sort key, so letting it go negative keeps every light
            // ranked by distance. Clamping collapsed all beam lights within
            // BEAM_BONUS blocks to a single score, degenerating their order to
            // the id tie-break — a lamp two blocks away could lose its upload
            // slot to one sixty blocks away whenever demand exceeded the cap.
            priorityScore[i] = s;
            order[i] = i;
        }
        sortOrder(n);
        orderedCount = n;
    }

    /** True if {@code identity} was in the last flush's uploaded set (kept sorted,
     *  so a binary search); drives the hysteresis discount in {@link #buildOrder}. */
    private static boolean wasUploaded(long identity)
    {
        return uploadedCount > 0 && Arrays.binarySearch(uploadedIds, 0, uploadedCount, identity) >= 0;
    }

    /** Stable bottom-up merge sort of {@link #order}[0,n) by
     *  {@code (priorityScore asc, id asc)}. O(n log n), one reused scratch buffer,
     *  no per-frame allocation; n &lt;= {@link #MAX}. */
    private static void sortOrder(int n)
    {
        for (int width = 1; width < n; width <<= 1)
        {
            for (int lo = 0; lo < n; lo += width << 1)
            {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + (width << 1), n);
                mergeRuns(lo, mid, hi);
            }
        }
    }

    private static void mergeRuns(int lo, int mid, int hi)
    {
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi)
        {
            orderScratch[k++] = precedes(order[i], order[j]) ? order[i++] : order[j++];
        }
        while (i < mid)
        {
            orderScratch[k++] = order[i++];
        }
        while (j < hi)
        {
            orderScratch[k++] = order[j++];
        }
        System.arraycopy(orderScratch, lo, order, lo, hi - lo);
    }

    /** Priority predicate: light {@code a} sorts before-or-equal light {@code b} —
     *  lower score first, id as the deterministic tie-break. Taking {@code a} on
     *  ties keeps the merge stable. */
    private static boolean precedes(int a, int b)
    {
        double sa = priorityScore[a], sb = priorityScore[b];
        if (sa != sb)
        {
            return sa < sb;
        }
        return id[a] <= id[b];
    }

    /** Pack the accumulated set into the GPU buffer (absolute world positions) and
     *  reset for the next frame. Kept for ABI compatibility — delegates to the
     *  camera-relative flush with a zero origin (= absolute). */
    public static void flush()
    {
        flush(0.0, 0.0, 0.0);
    }

    /** Pack the accumulated set into the GPU buffer with positions made RELATIVE to
     *  {@code origin} (the camera/eye), then reset for the next frame.
     *
     *  <p>Light positions are collected in absolute world coordinates (kept absolute — and
     *  in {@code double}, see the {@code px/py/pz} fields — in this registry so the shadow
     *  baker can query world blocks), but the SSBO — and the shader that reads it — must
     *  work in camera-relative space: at large world coordinates the absolute position and
     *  the shaderpack's reconstructed fragment position lose precision against each other
     *  and the light visibly stops lighting. Subtracting the camera origin here (and
     *  dropping the matching {@code + cameraPosition} reconstruction in the GLSL patches)
     *  keeps both sides of the {@code light.pos - fragPos} comparison small and precise.
     *  Both the stored position and the subtraction are done in double so the residual
     *  stays exact regardless of distance from origin; only the small residual is cast to
     *  float. Directions (spot) are NOT translated.</p> */
    public static void flush(double originX, double originY, double originZ)
    {
        LightBuffer.begin();
        ClusterGridBuffer.begin();

        // A render-path registration between prioritize() and here would leave
        // order[] stale; rebuild it from the flush origin (= the camera) so the cap
        // drops the lowest-priority lights, not an arbitrary registration tail.
        if (orderedCount != count)
        {
            buildOrder(originX, originY, originZ);
        }

        int cap = uploadCap <= 0 ? count : Math.min(count, uploadCap);
        uploadedCount = 0;

        for (int k = 0; k < cap; k++)
        {
            int i = orderedIndex(k);

            // Shadow bake throttled this frame (C2): the light owns no ready map,
            // so omit it from the SSBO rather than upload it unshadowed (a
            // shadow-caster with no map leaks through walls). It re-bakes and
            // reappears within a frame or two, nearest-first. Its cap slot is
            // consumed either way — the gap is a one-frame transient, not a leak.
            if (shadowTile[i] == SHADOW_PENDING)
            {
                continue;
            }

            // cone.z light mask: 0 = all, 1 = entities only, 2 = blocks only.
            // entities-only wins the (UI-prevented) both-set case.
            float lightMask = entitiesOnly[i] ? 1F : (blocksOnly[i] ? 2F : 0F);

            float rx = (float) (px[i] - originX);
            float ry = (float) (py[i] - originY);
            float rz = (float) (pz[i] - originZ);

            if (type[i] == 0)
            {
                LightBuffer.addPoint(rx, ry, rz, cr[i], cg[i], cb[i], intensity[i], radius[i], lightMask, anisotropy[i], density[i], beam[i], (float) shadowTile[i], bulbSize[i]);
            }
            else
            {
                LightBuffer.addSpot(rx, ry, rz, dx[i], dy[i], dz[i], cr[i], cg[i], cb[i], intensity[i], radius[i], cosOuter[i], cosInner[i], lightMask, anisotropy[i], density[i], beam[i], (float) shadowTile[i], bulbSize[i], cookieLayer[i], cookieRot[i], cookieScale[i], cookieFlags[i]);
            }

            // Snapshot this light for Phase 3 clustering: uploadedCount is the SAME
            // pre-increment cursor that indexes uploadedIds below, so it is this
            // light's PACKED index in the SSBO (binding 7) — i.e. its cluster mask
            // bit. W2: EVERY packed light gets a wide-region bit (the legacy uvec2
            // region still mirrors the first MASK_LIGHTS for old-generation packs).
            // Positions are the camera-relative rx/ry/rz computed above; tiles are
            // projected later, in ClusterGridBuffer.buildAndUpload.
            if (uploadedCount < ClusterGridBuffer.WIDE_LIGHTS)
            {
                ClusterGridBuffer.record(uploadedCount, rx, ry, rz, radius[i]);
            }

            uploadedIds[uploadedCount++] = id[i];
            LightBuffer.addProfile(hasProfile[i] ? profiles[i] : null);
        }

        // Snapshot is complete for this frame — mark it fresh so the late
        // gbuffer-matrix hook rasterises THESE lights (and flags becomes 1) even
        // when zero were recorded: an all-zero mask is correct, the shader
        // early-outs on count==0. No-op while clustering is disabled.
        ClusterGridBuffer.markSnapshotFresh();

        LightBuffer.upload();

        // Keep this frame's uploaded ids sorted so the next prioritize() can
        // binarySearch them for the hysteresis bonus.
        Arrays.sort(uploadedIds, 0, uploadedCount);

        count = 0;
        orderedCount = -1;
        resetIndex();
    }
}
