package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.qualet.irl.light.LightRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shadow bake driver. Collects nearby occluders (world entities) and, for each
 * spot/point in the {@link LightRegistry}, bakes its depth
 * map(s) and records the atlas tile / cube slot back into the registry
 * (-> SSBO vlParams.w). Runs at renderWorld HEAD, before Iris activates.
 *
 * Occluders: world blocks. A
 * per-light signature cache (see {@link #bake}) skips the GL depth render for
 * any light whose own geometry, in-range static occluders and world blocks are
 * unchanged. Atlas tiles / cube slots are STICKY per light id (see
 * {@link #acquireTile}), so a light appearing or dropping out does not shift —
 * and thereby re-bake — every other light's map.
 *
 * TWO-LAYER bake: a light with a live entity/replay in range runs in OVERLAY
 * mode — its static content (model blocks + world blocks) is baked into a
 * separate STATIC tile only when that content changes, then each frame the
 * base is GPU-copied into the live tile and just the dynamic casters render on
 * top. Re-tessellating cutout blocks / re-rendering model-block form trees no
 * longer happens per frame, so the per-frame cost of an animated scene depends
 * on the moving subjects, not on the scenery around the lamp.
 */
public final class ShadowBaker
{
    /** Bounded per-frame caster pool. 128 covers a full 64-light stress scene
     *  with one independent model caster per lamp while leaving room for actors. */
    private static final int MAX_OCCLUDERS = 128;
    private static final float OVERLAP_MARGIN = 0.5f;
    /** A dyn/copy rect covering at least NUM/DEN of its tile is promoted to
     *  {@link ShadowRect#FULL}: partial copy + filter dispatch stops paying
     *  off (aprons and per-rect setup eat the margin). Shared by the two
     *  coversMost call sites so they can never drift apart. */
    private static final int COVERS_MOST_NUM = 15;
    private static final int COVERS_MOST_DEN = 16;

    private static final long FNV_OFFSET = 1469598103934665603L;
    private static final long FNV_PRIME = 1099511628211L;
    /** Odd multiplier folding the in-range static COUNT into a light's signature
     *  (seam INVARIANT 3); count 0 (every redactor light) leaves the sig untouched. */
    private static final long STATIC_COUNT_MIX = 0x9E3779B97F4A7C15L;

    private static final Object[] occ = new Object[MAX_OCCLUDERS];
    private static final int[] occType = new int[MAX_OCCLUDERS];
    private static final float[] ox = new float[MAX_OCCLUDERS];
    private static final float[] oy = new float[MAX_OCCLUDERS];
    private static final float[] oz = new float[MAX_OCCLUDERS];
    private static final float[] orad = new float[MAX_OCCLUDERS];
    /** Horizontal half-DIAGONAL of the caster's own (unposed) box footprint,
     *  0.5*hypot(ex,ez)*scale, NO margin — the yaw-invariant tight bound the
     *  spot dyn-rect AABB is built from ({@link #computeSpotDynRect}); the
     *  raw-sphere emit path stores the full radius here (sphere box + slack).
     *  Cull still consumes only {@link #orad}. */
    private static final float[] orh = new float[MAX_OCCLUDERS];
    /** Vertical half-extent (ey*0.5)*scale, NO margin; sphere path = radius. */
    private static final float[] ohv = new float[MAX_OCCLUDERS];
    /** Static-layer membership, INDEPENDENT of {@link #occType} (seam INVARIANT 2).
     *  True => baked into the never-rebaked static base; its silhouette changes only
     *  when {@link #ostatichash} changes. False (every redactor caster — all dynamic
     *  entities) => live overlay; unknown revisions re-render every frame. */
    private static final boolean[] oStatic = new boolean[MAX_OCCLUDERS];
    /** Per-occluder signature of everything that changes a STATIC (model-block)
     *  caster's baked silhouette but isn't its center: form identity + transform
     *  translate/scale/rotate. Folded into a light's signature for model blocks
     *  in range; unused (0) for entity/replay casters, which are always treated
     *  dirty. */
    private static final long[] ostatichash = new long[MAX_OCCLUDERS];
    private static final CasterRevision[] oRevision = new CasterRevision[MAX_OCCLUDERS];
    private static final ShadowOverlayCache spotOverlays = new ShadowOverlayCache();
    private static final ShadowOverlayCache pointOverlays = new ShadowOverlayCache();
    private static final ShadowOverlayCache.Members overlayMembers = new ShadowOverlayCache.Members();
    /** References reused within one bake only; Member records never mutate. */
    private static final ShadowOverlayCache.Member[] spotMembersBySlot =
        new ShadowOverlayCache.Member[MAX_OCCLUDERS];
    private static final boolean NO_OVERLAY_REUSE = Boolean.getBoolean("irlite.noOverlayReuse");
    private static ClientLevel overlayWorld;
    /** Squared camera distance of each kept occluder's center — the
     *  replacement metric of the nearest-N policy (OPEN-2, see {@link #put}). */
    private static final float[] odist2 = new float[MAX_OCCLUDERS];
    private static final FarthestCasterHeap farthestOccHeap = new FarthestCasterHeap(odist2);
    private static int occCount;
    /** Cached argmax of {@link #odist2}. Once the pool is full this makes the
     *  common rejected-candidate path O(1); accepted replacements update a heap. */
    private static int farthestOccIdx;
    private static float farthestOccDist2;

    /** Frame camera position, captured by {@link #collect} BEFORE the source
     *  emits, so {@link #put} can rank every occluder by camera distance. */
    private static float occCamX;
    private static float occCamY;
    private static float occCamZ;

    // --- Per-light dirty cache (replaces the old global scene hash) ----------
    // Keyed by LightRegistry.getId (stable identity), like BlockShadowCache.
    // lastSig:   light geometry + sum of in-range model-block hashes last baked.
    // lastTile:  the atlas tile / cube slot the light last baked into. Also the
    //            "have we ever baked this light?" marker (containsKey).
    // lastBlocks:the world-block list instance last baked. BlockShadowCache
    //            returns the SAME instance until a block in range changes, so a
    //            reference compare detects terrain edits precisely.
    private static final Long2LongOpenHashMap lastSig = new Long2LongOpenHashMap();
    private static final Long2IntOpenHashMap lastTile = new Long2IntOpenHashMap();
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> lastBlocks = new Long2ObjectOpenHashMap<>();
    /** Ids whose LIVE tile currently contains dynamic (entity/replay) casters.
     *  Dynamic casters aren't in lastSig (they re-render every frame instead),
     *  so when the subject leaves range the signature returns to its earlier
     *  value and the cache would otherwise reuse the last map with the subject
     *  still baked in. While set, the light runs in overlay mode; the frame the
     *  subject is gone restores a clean static base (copy or bake) once. */
    private static final LongOpenHashSet wasDynamic = new LongOpenHashSet();

    // --- Static-layer (base) tile state, parallel to the live maps above -----
    // A light's STATIC tile/cube holds only its static content (model blocks +
    // world blocks) and is re-baked only when that content changes. On overlay
    // frames (dynamic subject in range) the base is GPU-copied into the live
    // tile and just the dynamic casters are re-rendered on top — the per-frame
    // cost no longer depends on how much static scenery surrounds the lamp.
    // Same id key + same eviction rules (purge/reset/retain) as the live maps.
    private static final Long2LongOpenHashMap lastStaticSig = new Long2LongOpenHashMap();
    private static final Long2IntOpenHashMap lastStaticTile = new Long2IntOpenHashMap();
    private static final Long2ObjectOpenHashMap<List<BlockShadowEntry>> lastStaticBlocks = new Long2ObjectOpenHashMap<>();
    /** Per-light 6-bit mask of cube faces a dynamic caster was drawn into on the
     *  LAST point overlay frame (T1.2). When the static base is unchanged, the
     *  per-frame restore copies only the faces that need it — the ones a dynamic
     *  caster touches now (dynFaceMaskScratch) OR touched last frame (this mask,
     *  so a vacated face restores instead of keeping a stale silhouette) —
     *  rather than blitting all 6. Absent key reads 0 (FastUtil default). Same
     *  lifecycle as lastSig (purge / retain / reset). */
    private static final Long2IntOpenHashMap lastFaceDynamic = new Long2IntOpenHashMap();

    /** Per-light TILE-LOCAL depth-pixel rect ({@link ShadowRect} packing) the
     *  SPOT overlay's dynamic casters were drawn into on the LAST overlay
     *  frame — the spot twin of {@link #lastFaceDynamic}: the per-frame
     *  static->live restore must cover last frame's silhouettes as well as
     *  this frame's, so the copy (and the filter rebuild) runs on the union.
     *  {@link ShadowRect#FULL} = the whole tile was written (an estimate
     *  bailed, or the no-static clear path ran). Absent key = treated FULL.
     *  Same lifecycle as lastSig (purge / retain / reset); any tile
     *  change/re-bake mid-overlay forces the full-tile path (BAKE_FORCED), so
     *  a stored rect never outlives the tile it was measured in. */
    private static final Long2LongOpenHashMap lastDynRect = new Long2LongOpenHashMap();

    /** Kill switch for the partial-tile spot overlay (copy/scissor/filter
     *  rects): -Dirlite.noPartialFilter=true restores the full-tile paths.
     *  The per-mod LIVE toggle is {@link ShadowConfig#shadowPartialTile()},
     *  checked next to this flag at the single overlay gate. */
    private static final boolean NO_PARTIAL = Boolean.getBoolean("irlite.noPartialFilter");

    /** Diagnostic bisect of the partial-tile path
     *  (-Dirlite.partialFullFilters=true): keeps the dynamic depth draws
     *  scissored to the projected rect but forces every static->live copy and
     *  pyramid/EVSM refilter back to the full tile. Splits "the scissor clips
     *  the silhouette" (still clipped with this on) from "the partial filter
     *  rects miss texels the sampler reads" (clean with this on). */
    private static final boolean PARTIAL_FULL_FILTERS = Boolean.getBoolean("irlite.partialFullFilters");

    /** Diagnostic overlay of the dyn rect itself
     *  (-Dirlite.dynRectDebug=true): fills the scissored rect's depth with
     *  the near plane, so the rect appears in the world as a solid shadowed
     *  block of the spot's light — shows exactly where the rect lands
     *  relative to the caster it must cover. Combine with
     *  partialFullFilters so the filters can't hide it. */
    private static final boolean DYNRECT_DEBUG = Boolean.getBoolean("irlite.dynRectDebug");

    /** Scratch for {@link #computeSpotDynRect} (render thread only). */
    private static final Matrix4f dynRectMatrix = new Matrix4f();
    private static final Vector4f dynRectVec = new Vector4f();

    /** Set true by {@link #scanInRange} when any in-range occluder is an entity
     *  or film replay (a dynamic subject) -> the light re-bakes every frame. */
    private static boolean dynamicInRangeScratch;
    /** Set by {@link #scanInRange} to the order-independent sum of the in-range
     *  model-block {@link #ostatichash} values. */
    private static long staticOccSigScratch;
    /** Set by {@link #scanInRange} to the number of in-range model-block
     *  (static) occluders. */
    private static int staticInRangeScratch;

    // --- Shortlist of in-range occluders (T1.1) ------------------------------
    // scanInRange records the occluders that passed its range (+ cone for spots)
    // test into shortIdx[0..shortCount), so the render passes that follow iterate
    // only those — without re-running the range/cone/face tests a second (and
    // third) time. shortFaceMask[s] is, for POINT scans (cone == false), the
    // 6-bit cube-face mask of occluder shortIdx[s] (which face frustums its
    // sphere touches), and dynFaceMaskScratch is the OR of that mask over the
    // DYNAMIC (entity/replay) occluders — replacing the per-face faceHasDynamic
    // walk in the point overlay. Spot scans leave shortFaceMask unused.
    //
    // Filled per-light right before that light's render passes; never reused
    // across two scanInRange calls (the spot loop fully precedes the point loop,
    // collect() fills occ[] once before both, so occ[] and these indices are
    // stable across a light's whole bake). One source of truth for the
    // scan == render invariant: the set rendered is exactly the set scanned.
    private static final int[] shortIdx = new int[MAX_OCCLUDERS];
    private static final int[] shortFaceMask = new int[MAX_OCCLUDERS];
    private static int shortCount;
    private static int dynFaceMaskScratch;

    // --- Sticky tile / cube-slot ownership ------------------------------------
    // A light KEEPS its atlas tile / cube slot across frames — including frames
    // where it is culled, toggled off, or has nothing in range — so one light
    // dropping out no longer shifts every later light onto a different tile
    // (which used to re-bake them all: a camera pan across a light's
    // behind-plane boundary re-baked the whole scene). A tileless light takes a
    // free tile first; only when none are free does it steal from the
    // most-stale owner that hasn't requested for >= STALE_FRAMES frames (so an
    // owner that merely iterates later in THIS frame is never robbed), and the
    // victim's dirty state is purged so it cleanly first-bakes if it returns.
    // A tile's content can only ever be its owner's, so a returning owner with
    // an unchanged signature safely RE-USES its old depth map — zero rebake.
    private static final long NO_OWNER = Long.MIN_VALUE;
    private static final int STALE_FRAMES = 2;
    private static final long[] spotTileOwner = new long[SpotlightDepthAtlas.tileCount()];
    private static final int[] spotTileActive = new int[SpotlightDepthAtlas.tileCount()];
    private static final long[] pointSlotOwner = new long[PointDepthAtlas.blockCount()];
    private static final int[] pointSlotActive = new int[PointDepthAtlas.blockCount()];

    // --- LOD tier assignment (I3) ---------------------------------------------
    // Exclusive END of each tier's range in the type's FLAT pool index space
    // (spot: flat atlas tile, point: global cube slot), derived from the layout
    // owners — never hardcoded here. Because both layouts are tier-contiguous,
    // the SAME boundaries double as the rank-space tier thresholds: the rank-r
    // light (0-based, C1 priority order) wants tier t iff r < tierEnd[t] (first
    // match) — there are exactly tierEnd[t] tiles of tier <= t, so the r-th
    // ranked light fits.
    private static final int[] SPOT_TIER_END;
    private static final int[] POINT_TIER_END;
    /** Per-frame HANDOUT cap of each point tier (exclusive end in the global
     *  block space), refreshed every bake from
     *  {@link ShadowVramBudget#approvedPointBlocks}: physical filter-array
     *  capacity plus at most one pre-approved growth chunk. Rank->tier
     *  mapping stays {@link #POINT_TIER_END} (frozen GLSL ABI) — the cap only
     *  bounds which blocks may be handed OUT, never which tier a rank wants.
     *  Spot has no equivalent (its filter textures are atlas-sized). */
    private static final int[] POINT_TIER_CAP = new int[3];
    /** Sentinel "no tier" — compares WORSE (larger) than any real tier index; a
     *  light ranked past the last tier goes unshadowed for the frame. */
    private static final int TIER_NONE = Integer.MAX_VALUE;
    /** Schmitt-hysteresis demote margin: a light holds its current (better)
     *  tier until its rank is at least this far past the tier's rank range.
     *  Bounded rank inversion <= margin BY DESIGN — boundary-adjacent lights
     *  are near-equal in importance, so trading a <= 2-rank inversion for zero
     *  tile churn (and zero re-bakes) at a tier boundary is a win. Under
     *  full-pool SATURATION (every fallback tier owned by active lights) the
     *  inversion is instead bounded in TIME by
     *  {@link #CONTENTION_HOLD_FRAMES}. */
    private static final int DEMOTE_MARGIN = 2;
    /** Cap on CONSECUTIVE frames a demoted light may keep publishing (and
     *  active-refreshing) its old tile after {@link #acquireTileTiered} finds
     *  every fallback tier full, and — same shared counter — on the frames a
     *  light is Schmitt-held past the WHOLE pool (raw tier NONE): past the cap
     *  such a light degrades to the spare-capacity path (age-edge stamp, see
     *  {@link #acquireSpareTile}) instead of pinning its slot at full
     *  strength. While holding, the light keeps a valid shadow
     *  (no blink) at a stale tier; past the cap it stops publishing AND
     *  refreshing, so the tile ages out exactly like a culled light's and real
     *  pool pressure re-sorts ownership back to rank order — without the cap a
     *  saturated pool would freeze an UNBOUNDED rank inversion (a demoted
     *  light pinning a tier-0 tile forever). Saturation-case inversion bound =
     *  cap + {@link #STALE_FRAMES} + bake latency (~12 frames); the
     *  uncontended case stays rank-bounded by {@link #DEMOTE_MARGIN}. The
     *  counter re-arms only on a successful IN-POOL acquire — rank back inside
     *  the pool (an expired hold cannot
     *  re-arm itself by rank oscillation). KNOWN ACCEPTED LIMITS: the count is
     *  of slot-pinning frames only (all-fail acquire holds and Schmitt-held
     *  past-pool frames share it), so (a) frames spent in branches that pin
     *  nothing (behind-culled in-pool owners, spare-mode frames) pause rather
     *  than reset it — a resumed hold is SHORTER, never longer; (b) a mixed regime alternating all-fail with
     *  acquire-success-whose-bake-is-budget-refused re-arms it and can extend
     *  the inversion — reachable only under sustained mandatory-budget
     *  starvation plus per-frame tile churn (C1 hysteresis + Schmitt + sticky
     *  tiles all failing at once); every such publish is still owner-matched,
     *  so it erodes the re-sort goal, never the w-ownership invariant. */
    private static final int CONTENTION_HOLD_FRAMES = 8;
    /** Consecutive all-fail hold counter per light id (see
     *  {@link #CONTENTION_HOLD_FRAMES}); lifecycle follows the dirty-state
     *  maps (purge on steal, retain sweep, reset). */
    private static final Long2IntOpenHashMap contentionHold = new Long2IntOpenHashMap();

    /** Per-light contention-hold cap: {@link #CONTENTION_HOLD_FRAMES} plus a
     *  0-3 frame id-keyed jitter, so a batch of lights displaced together (one
     *  camera move) doesn't expire its holds — and pay the resulting steal +
     *  FORCED re-bake — in one synchronized frame hitch. */
    private static int holdCap(long id)
    {
        return CONTENTION_HOLD_FRAMES + (int) (id & 3L);
    }
    /** Monotonic bake counter driving the staleness test (not wall time). */
    private static int frameIndex;
    /** Remaining DEFERRABLE full static bakes this frame (T2.4). Reset each frame
     *  from {@link ShadowConfig#shadowBakeBudget()} ({@code <= 0} -> unlimited).
     *  A re-bake with our own valid map still on the tile runs only while this is
     *  positive. Mandatory bakes (first bake / tile reassigned / a subject
     *  leaving) also decrement it — so they defer the frame's remaining
     *  deferrable bakes — but their gate is {@link #mandatoryBakeBudget}
     *  (throttleable) or nothing at all (forced), never this counter. Dynamic
     *  overlays and static->live copies are NOT counted. */
    private static int staticBakeBudget;
    /** Remaining MANDATORY-THROTTLEABLE full static bakes this frame (C2). Reset
     *  to {@code max(1, budget)} ({@code <= 0} -> unlimited) at frame start AND
     *  again before the point loop, so the spot and point loops get independent
     *  pools — far spots (baked first) can't starve near points of a first bake.
     *  Caps the cold-start spike (first bake / shadow-quality change / shaders
     *  re-enabled, where dozens of lights first-bake at once): a first bake /
     *  just-reassigned tile bake (no own map to fall back on) runs only while this
     *  is positive — beyond it the light is marked shadow-pending
     *  ({@link LightRegistry#setShadowPending}) and OMITTED from the SSBO for the
     *  frame (no shadow -> no light, never unshadowed-through-walls), retrying next
     *  frame nearest-first (C1). Forced bakes (a subject leaving, which can't be
     *  deferred) bypass the gate but still decrement this, so they can't let extra
     *  cold bakes slip past the frame's cap. */
    private static int mandatoryBakeBudget;
    /** Last seen shadow-quality setting; a change frees + re-allocates the
     *  depth textures, so every cached map must be forgotten with it. */
    private static int lastQuality = Integer.MIN_VALUE;

    static
    {
        Arrays.fill(spotTileOwner, NO_OWNER);
        Arrays.fill(pointSlotOwner, NO_OWNER);
        SPOT_TIER_END = new int[3];
        for (int t = 0; t < SPOT_TIER_END.length; t++)
        {
            SPOT_TIER_END[t] = SpotlightDepthAtlas.tierStartTile(t) + SpotlightDepthAtlas.tierTileCount(t);
        }
        POINT_TIER_END = new int[3];
        for (int t = 0; t < POINT_TIER_END.length; t++)
        {
            POINT_TIER_END[t] = PointDepthAtlas.tierStartBlock(t) + PointDepthAtlas.tierBlockCount(t);
            POINT_TIER_CAP[t] = POINT_TIER_END[t]; // pre-first-bake default; refreshed every prologue
        }
    }

    /** Scratch set of current tile owners for the defensive end-of-frame
     *  dirty-state sweep (dirty state may outlive a skipped frame — that is
     *  the sticky-tile win — but never its tile ownership). */
    private static final LongOpenHashSet ownerIdScratch = new LongOpenHashSet();

    // --- Optional bake profiler: -Dirlite.profileShadows=true -----------------
    // Logs once per second: avg/max bake() wall time, dirty re-bakes per kind,
    // occluder/light counts. Baseline + validation tool for the perf work.
    private static final boolean PROFILE = Boolean.getBoolean("irlite.profileShadows");
    private static long profWindowStart;
    private static long profNanos;
    private static long profMaxNanos;
    private static int profFrames;
    /** Full static-content bakes (clear + model blocks + world blocks). */
    private static int profSpotBakes;
    private static int profPointBakes;
    /** Overlay frames (static base copied / cleared + dynamic casters drawn). */
    private static int profSpotOverlays;
    private static int profPointOverlays;
    /** Peak one-frame tile demand (ranks consumed, behind-culled included) in
     *  the window — compare against the pool sizes printed next to it. */
    private static int profSpotDemand;
    private static int profPointDemand;
    /** Peak one-frame behind-camera-culled light count in the window. */
    private static int profSpotBehind;
    private static int profPointBehind;
    /** Reusable scratch set of all current light ids, used to evict the block-
     *  list + VBO caches for lights that disappeared. */
    private static final LongOpenHashSet liveIds = new LongOpenHashSet();

    // --- Optional GPU bake probe (ShadowEngine.installBakeProbe, dev-only) ----
    // Sections partition the host mod's single bake GPU-timer bracket into
    // SIBLING brackets at the bakeInner seams (GL_TIME_ELAPSED cannot nest);
    // counters feed the host's per-window work table. With no probe installed
    // (every production run) each call site is a null-check no-op.
    /** Counter keys for full static bakes, indexed by LOD tier (I3). */
    private static final String[] SPOT_BAKE_KEY = {"sp.bake.t0", "sp.bake.t1", "sp.bake.t2"};
    private static final String[] POINT_BAKE_KEY = {"pt.bake.t0", "pt.bake.t1", "pt.bake.t2"};

    private static void probeSection(String name)
    {
        ShadowBakeProbe p = ShadowEngine.bakeProbe();
        if (p != null)
        {
            p.section(name);
        }
    }

    private static void probeCount(String key, int amount)
    {
        ShadowBakeProbe p = ShadowEngine.bakeProbe();
        if (p != null && amount != 0)
        {
            p.counter(key, amount);
        }
    }

    private static void probeDetailedSection(String name)
    {
        ShadowBakeProbe p = ShadowEngine.bakeProbe();
        if (p != null && p.detailedTimings())
        {
            p.section(name);
        }
    }

    private ShadowBaker()
    {}

    public static void bake(ClientLevel world, Vec3 cameraPos, Vec3 cameraForward, float tickDelta)
    {
        try
        {
            bakeProfiled(world, cameraPos, cameraForward, tickDelta);
        }
        catch (RuntimeException | Error failure)
        {
            // Includes failures in the batched filters AFTER depth was written.
            spotOverlays.clear();
            pointOverlays.clear();
            throw failure;
        }
    }

    private static void bakeProfiled(ClientLevel world, Vec3 cameraPos, Vec3 cameraForward, float tickDelta)
    {
        if (!PROFILE)
        {
            bakeInner(world, cameraPos, cameraForward, tickDelta);
            if (ShadowClipTelemetry.ENABLED)
            {
                ShadowClipTelemetry.recordFrame();
            }
            return;
        }

        long t0 = System.nanoTime();
        try
        {
            bakeInner(world, cameraPos, cameraForward, tickDelta);
        }
        finally
        {
            profRecordFrame(System.nanoTime() - t0);
            if (ShadowClipTelemetry.ENABLED)
            {
                ShadowClipTelemetry.recordFrame();
            }
        }
    }

    private static void bakeInner(ClientLevel world, Vec3 cameraPos, Vec3 cameraForward, float tickDelta)
    {
        if (overlayWorld != world)
        {
            resetTileState();
            overlayWorld = world;
        }
        if (world == null || cameraPos == null)
        {
            return;
        }
        if (!ShadowEngine.config().shadowsEnabled() || LightRegistry.getCount() == 0)
        {
            // Shadows switched off, or no lights at all — forget tiles + dirty
            // state and drain every cache so nothing lingers in VRAM/heap. The
            // enable is what makes the mod's "shadows off" toggle actually save
            // the bake cost (13.5-17.8 ms/frame + ~1.6 GB), not just skip the
            // shader-side sampling. Re-enabling rebuilds from scratch next frame,
            // exactly like walking back toward a lamp.
            resetTileState();
            liveIds.clear();
            BlockShadowCache.retainOnly(liveIds);
            ShadowRenderer.retainBlockVbos(liveIds);
            return;
        }

        // Apply the shadow resolution preset (no-op unless it changed). On a
        // change the depth textures are freed + re-allocated, so every cached
        // map is gone: forget tiles + dirty state too, or a "clean" light would
        // skip its rebake and sample a blank (or not yet allocated) map.
        int quality = ShadowEngine.config().shadowQuality();
        IRLShadowQuality.applyFromSetting(quality);
        if (quality != lastQuality)
        {
            resetTileState();
            lastQuality = quality;
        }

        // Per-frame growth approval for the demand-sized point filter arrays:
        // the acquire paths below never hand out a point block past the
        // approved per-tier cap, so the post-loop filter flushes can always
        // allocate the layers behind every published block. Denied growth
        // (VRAM reserve would break) just shrinks the effective pool — lamps
        // past it take the existing spare/unshadowed paths.
        ShadowVramBudget.updatePointCaps();
        for (int t = 0; t < POINT_TIER_CAP.length; t++)
        {
            POINT_TIER_CAP[t] = PointDepthAtlas.tierStartBlock(t) + ShadowVramBudget.approvedPointBlocks(t);
        }

        collect(world, cameraPos, tickDelta);
        // NOTE: no early-out on occCount == 0 — a light shining only on world
        // blocks (no entity/model/replay occluders nearby) still needs its
        // block silhouette baked. The per-light skip below also checks blocks.

        int n = LightRegistry.getCount();
        boolean cache = ShadowEngine.config().shadowCache();
        if (!cache || NO_OVERLAY_REUSE)
        {
            spotOverlays.clear();
            pointOverlays.clear();
        }
        frameIndex++;
        // Per-frame full-static-bake budgets (T2.4 deferrable / C2 mandatory).
        // <= 0 means unlimited. The deferrable pool spans the whole frame; the
        // mandatory pool is re-inited per loop (here for spots, again before the
        // point loop) so the two types don't starve each other. max(1, ...) keeps
        // at least one mandatory bake so a lone cold light can never stall forever.
        int budget = ShadowEngine.config().shadowBakeBudget();
        staticBakeBudget = budget <= 0 ? Integer.MAX_VALUE : budget;
        mandatoryBakeBudget = budget <= 0 ? Integer.MAX_VALUE : Math.max(1, budget);
        ShadowRenderer.beginBake();

        // Behind-camera cull inputs: a light whose whole influence sphere is
        // behind the camera plane lights no on-screen surface (diffuse/specular
        // only sample its shadow map for in-range fragments, and volumetrics
        // ignore the map), so its bake work is skipped (the per-light test in
        // each loop below). Conservative: only the fully-behind half-space is
        // culled, never a side-of-frustum light, so no shadow can go missing.
        // The cull does NOT skip rank consumption (I3): rank must be a pure
        // function of camera POSITION (C1 distance order), never of view
        // DIRECTION — when it did, a rotation that culled half a lamp field
        // shifted every remaining light's rank across the tier/pool boundaries,
        // and once demand exceeded the pool the shadows visibly jumped between
        // sources on every camera pan.
        double camX = cameraPos.x, camY = cameraPos.y, camZ = cameraPos.z;
        boolean haveFwd = cameraForward != null;
        double fwdX = haveFwd ? cameraForward.x : 0.0;
        double fwdY = haveFwd ? cameraForward.y : 0.0;
        double fwdZ = haveFwd ? cameraForward.z : 0.0;

        probeSection("bake-spot");

        // --- spotlights: one perspective atlas tile each ---
        // Iterate in priority order (perf C1): tiles + the static-bake budget go
        // to the highest-priority lights first. Tiles are sticky per id, so the
        // reordering never re-bakes an otherwise-unchanged light.
        // I3: spotRank counts this frame's spot TILE REQUESTS (0-based, C1
        // order) — a light consumes a rank only after passing every
        // CAMERA-INDEPENDENT skip check (the behind-camera cull consumes a
        // rank too, see the cull-inputs note); the rank picks its LOD tier.
        int spotRank = 0;
        int behindSpots = 0;
        for (int k = 0; k < n; k++)
        {
            int i = LightRegistry.orderedIndex(k);
            if (LightRegistry.getType(i) != 1)
            {
                continue;
            }

            float lx = LightRegistry.getX(i);
            float ly = LightRegistry.getY(i);
            float lz = LightRegistry.getZ(i);
            // DOUBLE light position feeds the light-relative bake anchor + view eye
            // (ShadowRenderer derives A = round((float) lxD) == round(lx), eye = L-A).
            // The float lx/ly/lz stay the cull / block-cache / signature ABI; by
            // construction (float) lxD == lx, so the anchor stays locked to the snap.
            double lxD = LightRegistry.getXd(i), lyD = LightRegistry.getYd(i), lzD = LightRegistry.getZd(i);
            float range = LightRegistry.getRange(i);
            if (range < 1e-3f)
            {
                continue;
            }
            // Whole sphere behind the camera -> the bake work is skipped below,
            // AFTER this light consumes its rank (see the cull-inputs note). Its
            // SSBO tile stays -1 (unshadowed, never sampled) while off; the
            // sticky tile it owns is kept alive, so when the camera turns back
            // an unchanged light re-uses its old map without any rebake.
            boolean behind = haveFwd && (lx - camX) * fwdX + (ly - camY) * fwdY + (lz - camZ) * fwdZ < -range;
            boolean castsShadows = LightRegistry.getShadows(i);
            long id = LightRegistry.getId(i);

            // Spot axis + cone half-angle drive the cone cull in scanInRange /
            // renderInRangeCone: an occluder fully outside the lit cone can only
            // shadow unlit fragments, so it need not be baked (and an out-of-cone
            // subject must not dirty the light). Dir is stored normalized;
            // re-normalize defensively and disable the cull on a degenerate dir.
            float dx = LightRegistry.getDirX(i);
            float dy = LightRegistry.getDirY(i);
            float dz = LightRegistry.getDirZ(i);
            float cosOuter = LightRegistry.getCosOuter(i);
            float coneTheta = (float) Math.acos(Mth.clamp(cosOuter, -1f, 1f));
            float dlen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean cone = dlen > 1e-4f;
            float ndx = cone ? dx / dlen : 0f;
            float ndy = cone ? dy / dlen : 0f;
            float ndz = cone ? dz / dlen : 0f;

            // "Shadows" toggle (default on): when off this light casts no shadow
            // at all — neither entities nor world blocks. Forcing both inputs
            // empty drops it into the same "nothing in range" skip below, leaving
            // its shadow tile unassigned (-1 = none in the SSBO -> unshadowed).
            if (ShadowClipTelemetry.ENABLED)
            {
                ShadowClipTelemetry.beginScan(id, true);
            }
            int entInRange = castsShadows ? scanInRange(lx, ly, lz, range, ndx, ndy, ndz, coneTheta, cone) : 0;
            // Collect blocks every frame (NOT gated on dirty): the skip/tile
            // decision must match the frame that actually baked, or the atlas
            // tile a light points to in the SSBO could drift off its baked
            // depth map. Cached by id -> O(1) on a hit, and the returned list
            // instance is stable until a block in range changes. A BEHIND
            // light only reads blocks in the skip check below, which
            // short-circuits on entInRange > 0 — skip the collect there
            // (rank-neutral: the skip outcome is identical either way), so a
            // moving culled light doesn't pay per-cell block re-collects.
            List<BlockShadowEntry> blocks = castsShadows && (!behind || entInRange == 0)
                ? collectBlocks(id, world, lx, ly, lz, range) : Collections.emptyList();
            if (entInRange == 0 && blocks.isEmpty())
            {
                continue;
            }

            // I3 tier assignment: every camera-independent skip check passed ->
            // consume one rank (behind-culled lights included, see above).
            int rank = spotRank++;
            int prevTile = ownedPrevTile(spotTileOwner, id);
            int lastTier = prevTile >= 0 ? tierForIndex(prevTile, SPOT_TIER_END) : TIER_NONE;
            int desired = desiredTier(rank, lastTier, SPOT_TIER_END);
            boolean pastPool = tierForIndex(rank, SPOT_TIER_END) == TIER_NONE;
            if (pastPool && desired != TIER_NONE)
            {
                // Schmitt-held past the WHOLE pool: the light pins a slot it
                // no longer ranks for, and with rotation-stable ranks + the
                // behind keep-alive below nothing else would ever re-sort a
                // static shot — so the pin is bounded in TIME by the shared
                // contention counter (ticking visible AND behind: it is the
                // slot-pinning being bounded, not a publish). Past the cap the
                // light degrades to the spare path below; an in-pool acquire
                // (rank back inside) re-arms the counter.
                int held = (contentionHold.containsKey(id) ? contentionHold.get(id) : 0) + 1;
                if (held <= holdCap(id))
                {
                    contentionHold.put(id, held);
                }
                else
                {
                    desired = TIER_NONE;
                }
            }
            if (behind)
            {
                // Culled: no bake, no publish (no on-screen fragment samples
                // this light, SSBO tile stays -1). The owned tile's stamp is
                // kept fresh only while the light still ranks for the tier it
                // holds (desired <= lastTier = the Schmitt hold and pending
                // promotes; a light demoted past the margin — or past the
                // bounded past-pool hold above — stops refreshing, so a
                // too-good tile ages out and re-sorts to a light that ranks
                // for it). A pan over an in-pool owner therefore steals
                // nothing, and the turn-back re-uses the old map bake-free.
                behindSpots++;
                if (prevTile >= 0 && desired != TIER_NONE && desired <= lastTier)
                {
                    spotTileActive[prevTile] = frameIndex;
                }
                continue;
            }
            int myTile;
            if (desired == TIER_NONE)
            {
                // Ranked past the pool (or past the bounded Schmitt hold):
                // SPARE-CAPACITY mode instead of an instant shadow drop.
                // Re-take the still-owned tile, else a free / long-dead slot —
                // never a live steal — stamped active = frameIndex - 1:
                // permanently on the staleness edge, so any in-pool light with
                // a real claim (they all iterate earlier and observe age >=
                // STALE_FRAMES) steals it the moment demand exists. Spare
                // usage costs the rank re-sort nothing, and a shadow now dies
                // only when its slot is actually taken — not the frame the
                // rank crosses the pool edge. Publishing is safe: the slot is
                // owned, so the normal path below either re-uses our own valid
                // map or first-bakes (mandatory-gated, SHADOW_PENDING on
                // refusal) before anything samples it.
                myTile = acquireSpareTile(spotTileOwner, spotTileActive, id, SPOT_TIER_END, SPOT_TIER_END);
                if (myTile < 0)
                {
                    continue; // nothing owned, free or reclaimable: unshadowed (tile -1)
                }
            }
            else
            {
                myTile = acquireTileTiered(spotTileOwner, spotTileActive, id, desired, SPOT_TIER_END, SPOT_TIER_END);
                if (myTile < 0)
                {
                    // Every tile of every fallback tier is owned by a recently-
                    // active light. A light that still OWNS a valid map (only
                    // reachable on an accepted demote — hold/promote would have
                    // owner-matched it in the scan) keeps sampling it instead of
                    // going unshadowed (leaking through walls), handoff-style:
                    // publish the owned tile + refresh its active stamp so it
                    // can't be stale-stolen mid-use (an unrefreshed published
                    // tile could be stale-stolen and re-baked THIS frame by a
                    // later-iterating light -> foreign-w; refreshed it is age 0,
                    // and later lights only owner-match their own tiles, take
                    // free slots or steal STALE ones). The hold is BOUNDED
                    // (CONTENTION_HOLD_FRAMES consecutive failures): past the cap
                    // the light neither publishes nor refreshes, so the tile ages
                    // out — no foreign-w window, nothing
                    // is published on any frame the tile could be stolen — and
                    // sustained saturation re-sorts the pool to true rank order
                    // instead of freezing the inversion. Tile-less lights stay
                    // unshadowed (-1), exactly like the old full single pool.
                    if (prevTile >= 0)
                    {
                        int held = (contentionHold.containsKey(id) ? contentionHold.get(id) : 0) + 1;
                        if (held <= holdCap(id))
                        {
                            contentionHold.put(id, held);
                            LightRegistry.setShadowTile(i, prevTile);
                            spotTileActive[prevTile] = frameIndex;
                        }
                    }
                    continue;
                }
                if (!pastPool)
                {
                    contentionHold.remove(id); // successful in-pool acquire re-arms the saturation hold
                }
            }
            long sig = lightGeomSig(lx, ly, lz, dx, dy, dz, range, cosOuter, castsShadows) + staticOccSigScratch;
            sig ^= staticInRangeScratch * STATIC_COUNT_MIX; // fold static count (INVARIANT 3); 0 on redactor
            boolean dyn = dynamicInRangeScratch;
            boolean hasStatic = staticInRangeScratch > 0 || !blocks.isEmpty();
            float outerDeg = (float) Math.toDegrees(coneTheta * 2.0);
            LightRegistry.setShadowTile(i, myTile);

            if (!cache)
            {
                // Cache disabled: everything straight into the live tile, every frame.
                if (PROFILE)
                {
                    profSpotBakes++;
                }
                probeCount(SPOT_BAKE_KEY[tierForIndex(myTile, SPOT_TIER_END)], 1);
                ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, true);
                if (entInRange > 0)
                {
                    renderInRangeCone(CASTERS_ALL, tickDelta);
                }
                if (!blocks.isEmpty())
                {
                    ShadowRenderer.renderBlocksDepth(id, blocks);
                }
                ShadowRenderer.endPass();
                SpotShadowPyramid.markDirty(myTile);
                SpotShadowEvsm.markDirty(myTile, range);
                // Full-tile unscissored draws: a stored partial rect from an
                // earlier cached-overlay episode understates what this frame
                // wrote — forget it, or a cache OFF->ON flip mid-overlay would
                // partial-restore around a stale rect and keep frozen ghost
                // silhouettes outside it (same rule as the no-static branch).
                lastDynRect.remove(id);
                rememberLive(id, sig, myTile, blocks, dyn);
                releaseOldTile(spotTileOwner, id, prevTile, myTile); // bake succeeded into myTile -> free the old tier's tile
                continue;
            }

            if (!dyn && !wasDynamic.contains(id))
            {
                // Pure static: bake straight into the live tile when something
                // changed; otherwise last frame's map is still exactly right.
                boolean dirty = !lastTile.containsKey(id)   // first bake
                    || lastSig.get(id) != sig               // geometry / static occluder moved
                    || lastBlocks.get(id) != blocks         // terrain in range changed
                    || lastTile.get(id) != myTile;          // assigned a different tile
                if (dirty)
                {
                    // First bake / tile reassigned has no map of our own on the
                    // live tile, so it's throttled by the mandatory budget (C2)
                    // rather than freely deferrable (T2.4) — but it still can't be
                    // dropped silently: a refusal marks it shadow-pending below, so
                    // flush omits it (no light) instead of leaking it unshadowed.
                    boolean mustBake = !lastTile.containsKey(id) || lastTile.get(id) != myTile;
                    if (allowStaticBake(mustBake ? BAKE_MANDATORY : BAKE_DEFERRABLE))
                    {
                        if (PROFILE)
                        {
                            profSpotBakes++;
                        }
                        probeCount(SPOT_BAKE_KEY[tierForIndex(myTile, SPOT_TIER_END)], 1);
                        ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, true);
                        if (staticInRangeScratch > 0)
                        {
                            renderInRangeCone(CASTERS_STATIC, tickDelta);
                        }
                        if (!blocks.isEmpty())
                        {
                            ShadowRenderer.renderBlocksDepth(id, blocks);
                        }
                        ShadowRenderer.endPass();
                        SpotShadowPyramid.markDirty(myTile);
                        SpotShadowEvsm.markDirty(myTile, range);
                        rememberLive(id, sig, myTile, blocks, false);
                        releaseOldTile(spotTileOwner, id, prevTile, myTile); // tier flip completed -> free the old tile
                    }
                    else if (mustBake)
                    {
                        if (prevTile >= 0 && prevTile != myTile)
                        {
                            // HANDOFF (I3, zero-blink tier flip): the mandatory
                            // bake into the NEW tile was throttled, but we still
                            // own the OLD tile with our own valid map — keep
                            // sampling it this frame instead of going dark.
                            // Refresh its active stamp so it can't be stale-
                            // stolen while the handoff is pending: on the first
                            // pending frame the old tile was active last frame
                            // (frameIndex - active == 1 < STALE_FRAMES == 2 ->
                            // unstealable), and every later pending frame this
                            // refresh keeps it so. Dirty state untouched -> the
                            // bake retries next frame, nearest-first (C2/C1).
                            LightRegistry.setShadowTile(i, prevTile);
                            spotTileActive[prevTile] = frameIndex;
                            // The NEW tile stays unbaked and unpublished —
                            // release it now. Kept, it would strand in dual
                            // ownership (a next-frame hold re-owner-matches
                            // prevTile and never rescans this tier), go stale,
                            // and its steal would purgeDirtyState(this light)
                            // while it still actively samples prevTile. The
                            // retry re-acquires from the then-current ranks.
                            spotTileOwner[myTile] = NO_OWNER;
                        }
                        else
                        {
                            // True first bake (no owned prev tile), throttled:
                            // publishing -1 would render the light UNSHADOWED
                            // (leaking through walls); publishing myTile would
                            // sample foreign content. So mark it shadow-pending —
                            // flush omits it from the SSBO this frame (no shadow
                            // -> no light). Ownership is kept (acquire ran this
                            // frame) and the dirty state is left untouched, so
                            // next frame mustBake is still true and the bake
                            // retries, nearest-first (C1).
                            LightRegistry.setShadowPending(i);
                        }
                    }
                    // else deferrable re-bake deferred: keep our own (older) live
                    // map, SSBO still points at myTile; dirty state untouched so
                    // this re-bake retries next frame.
                }
                else
                {
                    rememberLive(id, sig, myTile, blocks, false);
                }
                continue;
            }

            long overlayPolicy = overlayPolicy(lxD, lyD, lzD);
            if (dyn && !NO_OVERLAY_REUSE && SpotShadowPyramid.readyForReuse() && SpotShadowEvsm.readyForReuse()
                && staticBaseCurrent(id, sig, myTile, blocks, hasStatic)
                && spotOverlays.reusable(id, myTile, sig, blocks, overlayPolicy, overlayMembers))
            {
                probeCount("sp.reuse", 1);
                continue;
            }
            long overlayFailures = ShadowRenderer.casterFailures();

            // Overlay mode: a dynamic subject is in range (or just left). The
            // static base lives in the STATIC tile, re-baked only when it
            // changes; every frame it is GPU-copied into the live tile and only
            // the dynamic casters are re-rendered on top — the per-frame cost
            // no longer scales with the static scenery around the lamp.
            // Rect the filters must rebuild this frame; FULL outside the
            // partial-tile overlay path (bakedStatic frames, no-static clears,
            // bailed estimates, the kill switch).
            long filterRect = ShadowRect.FULL;
            if (hasStatic)
            {
                boolean staticStale = !lastStaticTile.containsKey(id)
                    || lastStaticSig.get(id) != sig
                    || lastStaticBlocks.get(id) != blocks
                    || lastStaticTile.get(id) != myTile;
                // A static re-bake is deferrable only while a dynamic subject is
                // present (dyn) AND we still own a previously-baked static tile to
                // fall back on: the copy below restores that older base under the
                // live overlay until budget lets it re-bake (T2.4). The frame a
                // subject LEAVES (dyn false) must bake — the transition's
                // rememberLive() marks the light clean, so a deferred stale base
                // would never be noticed by the pure-static path again.
                boolean staticMustBake = !dyn
                    || !lastStaticTile.containsKey(id)
                    || lastStaticTile.get(id) != myTile;
                boolean bakedStatic = false;
                // A must-bake here is FORCED (the copy below has no valid base to
                // fall back on, or the leave transition can't carry over) — never
                // throttled; the deferrable case still honours the frame budget.
                if (staticStale && allowStaticBake(staticMustBake ? BAKE_FORCED : BAKE_DEFERRABLE))
                {
                    bakedStatic = true;
                    if (PROFILE)
                    {
                        profSpotBakes++;
                    }
                    probeCount(SPOT_BAKE_KEY[tierForIndex(myTile, SPOT_TIER_END)], 1);
                    ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, true, true);
                    if (staticInRangeScratch > 0)
                    {
                        renderInRangeCone(CASTERS_STATIC, tickDelta);
                    }
                    if (!blocks.isEmpty())
                    {
                        ShadowRenderer.renderBlocksDepth(id, blocks);
                    }
                    ShadowRenderer.endPass();
                    lastStaticSig.put(id, sig);
                    lastStaticTile.put(id, myTile);
                    lastStaticBlocks.put(id, blocks);
                    // Tile change in overlay mode forces this bake (staticMustBake
                    // -> BAKE_FORCED, never throttled), so a successful static
                    // bake is the one guaranteed spot to complete the handoff.
                    releaseOldTile(spotTileOwner, id, prevTile, myTile);
                }

                // Partial-tile overlay: everything that changes on a steady
                // overlay frame lies inside the dynamic casters' projected
                // bbox — restore/refilter only union(this frame's rect, last
                // frame's rect: a vacated region must return to the static
                // base, the lastFaceDynamic pattern). A fresh static bake
                // invalidates the whole tile's filtered content -> FULL.
                int ts = SpotlightDepthAtlas.tileSizePx(myTile);
                long dynRect = ShadowRect.FULL;
                if (!NO_PARTIAL && ShadowEngine.config().shadowPartialTile() && dyn && !bakedStatic)
                {
                    dynRect = computeSpotDynRect(lx, ly, lz, lxD, lyD, lzD,
                        ndx, ndy, ndz, cone, range, outerDeg, ts, id);
                }
                long copyRect = (bakedStatic || PARTIAL_FULL_FILTERS) ? ShadowRect.FULL
                    : ShadowRect.union(dynRect, lastDynRect.containsKey(id) ? lastDynRect.get(id) : ShadowRect.FULL);
                if (copyRect != ShadowRect.FULL && ShadowRect.coversMost(copyRect, ts, COVERS_MOST_NUM, COVERS_MOST_DEN))
                {
                    copyRect = ShadowRect.FULL;
                }
                probeDetailedSection("bake-spot-copy");
                if (copyRect == ShadowRect.FULL)
                {
                    SpotlightDepthAtlas.copyStaticToLive(myTile);
                }
                else
                {
                    SpotlightDepthAtlas.copyStaticToLiveRect(myTile,
                        ShadowRect.x0(copyRect), ShadowRect.y0(copyRect),
                        ShadowRect.x1(copyRect) - ShadowRect.x0(copyRect),
                        ShadowRect.y1(copyRect) - ShadowRect.y0(copyRect));
                    probeCount("sp.rect", 1);
                }
                probeCount("sp.copy", 1);
                probeDetailedSection("bake-spot");
                if (dyn)
                {
                    if (PROFILE)
                    {
                        profSpotOverlays++;
                    }
                    probeCount("sp.dyn", 1);
                    ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, false);
                    if (dynRect != ShadowRect.FULL)
                    {
                        // HARD bound: depth writes may not escape the rect the
                        // filters rebuild (an under-estimate clips visibly
                        // instead of desyncing the pyramid/EVSM).
                        ShadowRenderer.restrictScissorSpot(myTile,
                            ShadowRect.x0(dynRect), ShadowRect.y0(dynRect),
                            ShadowRect.x1(dynRect) - ShadowRect.x0(dynRect),
                            ShadowRect.y1(dynRect) - ShadowRect.y0(dynRect));
                        if (DYNRECT_DEBUG)
                        {
                            ShadowRenderer.debugFillScissoredDepth();
                        }
                    }
                    renderInRangeCone(CASTERS_DYNAMIC, tickDelta);
                    ShadowRenderer.endPass();
                    lastDynRect.put(id, dynRect);
                }
                else
                {
                    // Leave frame: the (forced) full copy just restored pure
                    // static content — nothing dynamic left to track.
                    lastDynRect.remove(id);
                }
                filterRect = copyRect;
            }
            else
            {
                // No static content at all: clear + dynamic casters only.
                if (PROFILE && dyn)
                {
                    profSpotOverlays++;
                }
                probeCount("sp.clear", 1);
                if (dyn)
                {
                    probeCount("sp.dyn", 1);
                }
                ShadowRenderer.beginSpot(myTile, lxD, lyD, lzD, dx, dy, dz, range, outerDeg, false, true);
                if (dyn)
                {
                    renderInRangeCone(CASTERS_DYNAMIC, tickDelta);
                }
                ShadowRenderer.endPass();
                // Full-tile clear + unscissored draws: any stored rect
                // understates what this path wrote — forget it, so a later
                // hasStatic episode starts from a FULL restore.
                lastDynRect.remove(id);
                // The live tile was fully rewritten (clear + dynamics), i.e. a
                // successful bake into myTile -> same release rule as above.
                releaseOldTile(spotTileOwner, id, prevTile, myTile);
            }
            spotOverlays.completed(id, myTile, sig, blocks, overlayPolicy, overlayMembers,
                dyn && !NO_OVERLAY_REUSE && ShadowRenderer.casterFailures() == overlayFailures
                    && staticBaseCurrent(id, sig, myTile, blocks, hasStatic));
            // Changed overlays rebuild depth and its dependent filters together,
            // on the same rect the copy/draws touched.
            if (filterRect == ShadowRect.FULL)
            {
                SpotShadowPyramid.markDirty(myTile);
                SpotShadowEvsm.markDirty(myTile, range);
            }
            else
            {
                SpotShadowPyramid.markDirtyRect(myTile, filterRect);
                SpotShadowEvsm.markDirtyRect(myTile, filterRect, range);
            }

            if (dyn)
            {
                wasDynamic.add(id);
                // Keep the live-tile record current through overlay frames
                // (rememberLive is intentionally skipped while dyn): after a
                // tier flip releaseOldTile freed the tile lastTile pointed at,
                // and a dyn-from-birth light never had an entry at all — either
                // way ownedPrevTile would read tier-less next frame and the
                // Schmitt hysteresis would be lost (I3). The signature is NOT
                // refreshed here; the pure-static path is unreachable until
                // the leave transition's rememberLive() makes it consistent.
                lastTile.put(id, myTile);
            }
            else
            {
                // Subject just left: the live tile holds pure static content again.
                rememberLive(id, sig, myTile, blocks, false);
            }
        }

        // one batched pyramid + EVSM pass over every tile the spot loop dirtied (before the SSBO flush / Iris passes)
        probeSection("bake-spot-pyr");
        SpotShadowPyramid.flushDirty();
        probeSection("bake-spot-evsm");
        SpotShadowEvsm.flushDirty();
        probeSection("bake-point");

        // Give the point loop its own mandatory pool (C2): the spot and point
        // loops run sequentially, so a shared pool lets far spots (baked first)
        // starve near points of a first bake — a starved point marks itself
        // shadow-pending and shows no light at all until the spots drain. Per-type
        // pools keep each type's cold-start bounded and priority-ordered on its
        // own. The deferrable pool stays shared frame-wide (a deferred re-bake
        // keeps its own map, so sharing it costs a staler shadow, never a leak).
        mandatoryBakeBudget = budget <= 0 ? Integer.MAX_VALUE : Math.max(1, budget);

        // --- point lights: flat atlas, one 6-face block each ---
        // Priority order, sticky tiles (see the spot loop). pointRank is the
        // point-type twin of spotRank (I3 tier assignment).
        int pointRank = 0;
        int behindPoints = 0;
        for (int k = 0; k < n; k++)
        {
            int i = LightRegistry.orderedIndex(k);
            if (LightRegistry.getType(i) != 0)
            {
                continue;
            }

            float lx = LightRegistry.getX(i);
            float ly = LightRegistry.getY(i);
            float lz = LightRegistry.getZ(i);
            // DOUBLE light position for the light-relative bake anchor + view eye
            // (see the spot loop). Float lx/ly/lz remain the cull/cache/signature ABI.
            double lxD = LightRegistry.getXd(i), lyD = LightRegistry.getYd(i), lzD = LightRegistry.getZd(i);
            float radius = LightRegistry.getRange(i);
            if (radius < 1e-3f)
            {
                continue;
            }
            // Behind-camera cull (see the spot loop): bake work only — the
            // rank is still consumed below.
            boolean behind = haveFwd && (lx - camX) * fwdX + (ly - camY) * fwdY + (lz - camZ) * fwdZ < -radius;
            boolean castsShadows = LightRegistry.getShadows(i);
            long id = LightRegistry.getId(i);

            // See the spot loop: "Shadows" off -> no entities, no blocks.
            // Points are omnidirectional -> no cone cull (cone=false); the 6 cube
            // faces are culled individually in renderInRangeFace below.
            if (ShadowClipTelemetry.ENABLED)
            {
                ShadowClipTelemetry.beginScan(id, false);
            }
            int entInRange = castsShadows ? scanInRange(lx, ly, lz, radius, 0f, 0f, 0f, 0f, false) : 0;
            // Collected once, reused across all 6 cube faces (see spot note);
            // behind lights skip the collect when entInRange already decides
            // the skip check (rank-neutral, see the spot loop).
            List<BlockShadowEntry> blocks = castsShadows && (!behind || entInRange == 0)
                ? collectBlocks(id, world, lx, ly, lz, radius) : Collections.emptyList();
            if (entInRange == 0 && blocks.isEmpty())
            {
                continue;
            }

            // I3 tier assignment (see the spot loop): every camera-independent
            // skip check passed -> consume one rank (behind-culled included),
            // Schmitt-pick the tier, acquire with fallback.
            int rank = pointRank++;
            int prevTile = ownedPrevTile(pointSlotOwner, id);
            int lastTier = prevTile >= 0 ? tierForIndex(prevTile, POINT_TIER_END) : TIER_NONE;
            int desired = desiredTier(rank, lastTier, POINT_TIER_END);
            boolean pastPool = tierForIndex(rank, POINT_TIER_END) == TIER_NONE;
            if (pastPool && desired != TIER_NONE)
            {
                // Schmitt-held past the WHOLE pool: bounded in TIME by the
                // shared contention counter — see the spot loop.
                int held = (contentionHold.containsKey(id) ? contentionHold.get(id) : 0) + 1;
                if (held <= holdCap(id))
                {
                    contentionHold.put(id, held);
                }
                else
                {
                    desired = TIER_NONE;
                }
            }
            if (behind)
            {
                // Culled keep-alive: no bake, no publish; stamp refreshed only
                // while the light still ranks for the tier it holds — see the
                // spot loop.
                behindPoints++;
                if (prevTile >= 0 && desired != TIER_NONE && desired <= lastTier)
                {
                    pointSlotActive[prevTile] = frameIndex;
                }
                continue;
            }
            int myBlock;
            if (desired == TIER_NONE)
            {
                // Ranked past the pool (or past the bounded Schmitt hold):
                // SPARE-CAPACITY mode, age-edge stamp — see the spot loop's
                // TIER_NONE note. The shadow dies only when the slot is
                // actually taken by an in-pool light, not at the rank edge.
                myBlock = acquireSpareTile(pointSlotOwner, pointSlotActive, id, POINT_TIER_END, POINT_TIER_CAP);
                if (myBlock < 0)
                {
                    continue; // nothing owned, free or reclaimable: unshadowed (tile -1)
                }
            }
            else
            {
                myBlock = acquireTileTiered(pointSlotOwner, pointSlotActive, id, desired, POINT_TIER_END, POINT_TIER_CAP);
                if (myBlock < 0)
                {
                    // Every slot of every fallback tier owned by a recently-active
                    // light: keep sampling a still-owned valid cube handoff-style
                    // rather than leaking unshadowed — BOUNDED to
                    // CONTENTION_HOLD_FRAMES like the spot loop (see there).
                    if (prevTile >= 0)
                    {
                        int held = (contentionHold.containsKey(id) ? contentionHold.get(id) : 0) + 1;
                        if (held <= holdCap(id))
                        {
                            contentionHold.put(id, held);
                            LightRegistry.setShadowTile(i, prevTile);
                            pointSlotActive[prevTile] = frameIndex;
                        }
                    }
                    continue;
                }
                if (!pastPool)
                {
                    contentionHold.remove(id); // successful in-pool acquire re-arms the saturation hold
                }
            }
            // myBlock is the GLOBAL atlas block (what vlParams.w carries); the
            // per-tier filter textures address their layers by the LOCAL block
            // (myBlock - tierStartBlock), resolved inside their flushDirty.
            long sig = lightGeomSig(lx, ly, lz, 0f, 0f, 0f, radius, 1f, castsShadows) + staticOccSigScratch;
            sig ^= staticInRangeScratch * STATIC_COUNT_MIX; // fold static count (INVARIANT 3); 0 on redactor
            boolean dyn = dynamicInRangeScratch;
            boolean hasStatic = staticInRangeScratch > 0 || !blocks.isEmpty();
            LightRegistry.setShadowTile(i, myBlock);

            if (!cache)
            {
                // Cache disabled: everything into all live faces, every frame.
                if (PROFILE)
                {
                    profPointBakes++;
                }
                probeCount(POINT_BAKE_KEY[tierForIndex(myBlock, POINT_TIER_END)], 1);
                for (int face = 0; face < 6; face++)
                {
                    ShadowRenderer.beginPointFace(myBlock, face, lxD, lyD, lzD, radius, false, true);
                    if (entInRange > 0)
                    {
                        renderInRangeFace(face, CASTERS_ALL, tickDelta);
                    }
                    if (!blocks.isEmpty())
                    {
                        ShadowRenderer.renderBlocksDepth(id, blocks);
                    }
                    ShadowRenderer.endPass();
                }
                PointShadowPyramid.markDirty(myBlock);
                PointShadowEvsm.markDirty(myBlock, radius);
                rememberLive(id, sig, myBlock, blocks, dyn);
                releaseOldTile(pointSlotOwner, id, prevTile, myBlock); // bake succeeded into myBlock -> free the old tier's block
                continue;
            }

            if (!dyn && !wasDynamic.contains(id))
            {
                // Pure static (see the spot loop).
                boolean dirty = !lastTile.containsKey(id)
                    || lastSig.get(id) != sig
                    || lastBlocks.get(id) != blocks
                    || lastTile.get(id) != myBlock;
                if (dirty)
                {
                    // First bake / tile reassigned: throttled by the mandatory
                    // budget (C2), not freely deferrable (T2.4); a refusal marks it
                    // shadow-pending below (flush omits it, see the spot loop).
                    boolean mustBake = !lastTile.containsKey(id) || lastTile.get(id) != myBlock;
                    if (allowStaticBake(mustBake ? BAKE_MANDATORY : BAKE_DEFERRABLE))
                    {
                        if (PROFILE)
                        {
                            profPointBakes++;
                        }
                        probeCount(POINT_BAKE_KEY[tierForIndex(myBlock, POINT_TIER_END)], 1);
                        for (int face = 0; face < 6; face++)
                        {
                            ShadowRenderer.beginPointFace(myBlock, face, lxD, lyD, lzD, radius, false, true);
                            if (staticInRangeScratch > 0)
                            {
                                renderInRangeFace(face, CASTERS_STATIC, tickDelta);
                            }
                            if (!blocks.isEmpty())
                            {
                                ShadowRenderer.renderBlocksDepth(id, blocks);
                            }
                            ShadowRenderer.endPass();
                        }
                        PointShadowPyramid.markDirty(myBlock);
                        PointShadowEvsm.markDirty(myBlock, radius);
                        rememberLive(id, sig, myBlock, blocks, false);
                        releaseOldTile(pointSlotOwner, id, prevTile, myBlock); // tier flip completed -> free the old block
                    }
                    else if (mustBake)
                    {
                        if (prevTile >= 0 && prevTile != myBlock)
                        {
                            // HANDOFF (I3, zero-blink tier flip): keep sampling
                            // the old, still-owned, still-valid cube while the
                            // throttled bake into the new slot is pending, and
                            // refresh its active stamp so it can't be stale-
                            // stolen meanwhile (first pending frame: active last
                            // frame -> age 1 < STALE_FRAMES 2; later frames this
                            // refresh keeps it so). Dirty state untouched -> the
                            // bake retries next frame. See the spot loop.
                            LightRegistry.setShadowTile(i, prevTile);
                            pointSlotActive[prevTile] = frameIndex;
                            // Release the unbaked NEW slot immediately — see
                            // the spot handoff: kept, it would strand in dual
                            // ownership, go stale, and its steal would purge
                            // this light's dirty state mid-handoff.
                            pointSlotOwner[myBlock] = NO_OWNER;
                        }
                        else
                        {
                            // True first bake throttled: no cube of our own
                            // anywhere. Mark it shadow-pending — flush omits it
                            // (no shadow -> no light) rather than uploading it
                            // unshadowed (leaking through walls) or sampling
                            // foreign faces. Ownership kept, dirty state
                            // untouched -> retries next frame, nearest-first
                            // (C1). See the spot loop.
                            LightRegistry.setShadowPending(i);
                        }
                    }
                    // else deferrable re-bake deferred: keep our own (older) live
                    // cube; dirty state unchanged so it retries next frame
                    // (SSBO -> myBlock).
                }
                else
                {
                    rememberLive(id, sig, myBlock, blocks, false);
                }
                continue;
            }

            long overlayPolicy = overlayPolicy(lxD, lyD, lzD);
            int overlayTier = tierForIndex(myBlock, POINT_TIER_END);
            int overlayLocal = myBlock - PointDepthAtlas.tierStartBlock(overlayTier);
            if (dyn && !NO_OVERLAY_REUSE && PointShadowPyramid.readyForReuse(overlayTier, overlayLocal)
                && PointShadowEvsm.readyForReuse(overlayTier, overlayLocal)
                && staticBaseCurrent(id, sig, myBlock, blocks, hasStatic)
                && pointOverlays.reusable(id, myBlock, sig, blocks, overlayPolicy, overlayMembers))
            {
                probeCount("pt.reuse", 1);
                continue;
            }
            long overlayFailures = ShadowRenderer.casterFailures();

            // Overlay mode (see the spot loop). The static base lives in the
            // STATIC atlas layer, re-baked only when it changes; each frame it
            // is GPU-copied into the live block and only the dynamic casters
            // redraw, into the faces their spheres actually touch.
            if (hasStatic)
            {
                boolean staticStale = !lastStaticTile.containsKey(id)
                    || lastStaticSig.get(id) != sig
                    || lastStaticBlocks.get(id) != blocks
                    || lastStaticTile.get(id) != myBlock;
                // Deferrable only while a dynamic subject is present and we own a
                // prior static base to fall back on (see the spot overlay note).
                boolean staticMustBake = !dyn
                    || !lastStaticTile.containsKey(id)
                    || lastStaticTile.get(id) != myBlock;
                boolean bakedStatic = false;
                // Must-bake here is FORCED (no valid base for the copy, or a leave
                // transition) — never throttled; the deferrable case still honours
                // the frame budget (see the spot overlay note).
                if (staticStale && allowStaticBake(staticMustBake ? BAKE_FORCED : BAKE_DEFERRABLE))
                {
                    bakedStatic = true;
                    if (PROFILE)
                    {
                        profPointBakes++;
                    }
                    probeCount(POINT_BAKE_KEY[tierForIndex(myBlock, POINT_TIER_END)], 1);
                    for (int face = 0; face < 6; face++)
                    {
                        ShadowRenderer.beginPointFace(myBlock, face, lxD, lyD, lzD, radius, true, true);
                        if (staticInRangeScratch > 0)
                        {
                            renderInRangeFace(face, CASTERS_STATIC, tickDelta);
                        }
                        if (!blocks.isEmpty())
                        {
                            ShadowRenderer.renderBlocksDepth(id, blocks);
                        }
                        ShadowRenderer.endPass();
                    }
                    lastStaticSig.put(id, sig);
                    lastStaticTile.put(id, myBlock);
                    lastStaticBlocks.put(id, blocks);
                    // Block change in overlay mode forces this bake (staticMustBake
                    // -> BAKE_FORCED), completing the handoff (see the spot loop).
                    releaseOldTile(pointSlotOwner, id, prevTile, myBlock);
                }

                // Restore the static base into the live block (T1.2). When the
                // static layer was just re-baked (bakedStatic) every live face
                // needs the new base, so blit the whole block in one call — this
                // also covers first-overlay and post-tile-steal (both force a
                // bake via an absent / purged lastStaticTile). Otherwise copy
                // only the faces that need it: the ones a dynamic caster touches
                // now (dynNow) OR touched last frame (lastFaceDynamic, so a
                // vacated face restores to static instead of keeping its stale
                // silhouette). Untouched faces already hold the static base —
                // including when a stale re-bake was DEFERRED (T2.4): the static
                // layer is unchanged, so the live block's static faces still match.
                int dynNow = dynFaceMaskScratch;
                probeDetailedSection("bake-point-copy");
                if (bakedStatic)
                {
                    PointDepthAtlas.copyStaticToLive(myBlock);
                    probeCount("pt.copy.f", 6);
                }
                else
                {
                    int copyMask = dynNow | lastFaceDynamic.get(id);
                    probeCount("pt.copy.f", Integer.bitCount(copyMask));
                    for (int face = 0; face < 6; face++)
                    {
                        if ((copyMask & (1 << face)) != 0)
                        {
                            PointDepthAtlas.copyStaticFaceToLive(myBlock, face);
                        }
                    }
                }

                probeDetailedSection("bake-point");
                if (dyn)
                {
                    if (PROFILE)
                    {
                        profPointOverlays++;
                    }
                    probeCount("pt.dyn.f", Integer.bitCount(dynNow));
                    for (int face = 0; face < 6; face++)
                    {
                        if ((dynNow & (1 << face)) == 0)
                        {
                            continue; // no dynamic caster reaches this face; the copy refreshed it
                        }
                        ShadowRenderer.beginPointFace(myBlock, face, lxD, lyD, lzD, radius, false, false);
                        renderInRangeFace(face, CASTERS_DYNAMIC, tickDelta);
                        ShadowRenderer.endPass();
                    }
                }
                lastFaceDynamic.put(id, dynNow);
            }
            else
            {
                // No static content: every face still clears (a vacated face
                // would keep a phantom shadow), dynamics drawn where they reach.
                if (PROFILE && dyn)
                {
                    profPointOverlays++;
                }
                probeCount("pt.clear.f", 6);
                if (dyn)
                {
                    probeCount("pt.dyn.f", Integer.bitCount(dynFaceMaskScratch));
                }
                for (int face = 0; face < 6; face++)
                {
                    ShadowRenderer.beginPointFace(myBlock, face, lxD, lyD, lzD, radius, false, true);
                    if (dyn)
                    {
                        renderInRangeFace(face, CASTERS_DYNAMIC, tickDelta);
                    }
                    ShadowRenderer.endPass();
                }
                // All 6 live faces fully rewritten (clear + dynamics), i.e. a
                // successful bake into myBlock -> same release rule as above.
                releaseOldTile(pointSlotOwner, id, prevTile, myBlock);
            }
            pointOverlays.completed(id, myBlock, sig, blocks, overlayPolicy, overlayMembers,
                dyn && !NO_OVERLAY_REUSE && ShadowRenderer.casterFailures() == overlayFailures
                    && staticBaseCurrent(id, sig, myBlock, blocks, hasStatic));
            // Changed point overlays still rebuild ALL dependent faces/mips/moments.
            PointShadowPyramid.markDirty(myBlock);
            PointShadowEvsm.markDirty(myBlock, radius);

            if (dyn)
            {
                wasDynamic.add(id);
                // Keep the live-block record current through overlay frames —
                // see the spot loop tail: preserves the Schmitt hysteresis
                // across tier flips and for dyn-from-birth lights (I3).
                lastTile.put(id, myBlock);
            }
            else
            {
                rememberLive(id, sig, myBlock, blocks, false);
            }
        }

        // one batched pyramid + EVSM pass over every block the point loop dirtied (before the SSBO flush / Iris passes)
        probeSection("bake-point-pyr");
        PointShadowPyramid.flushDirty();
        probeSection("bake-point-evsm");
        PointShadowEvsm.flushDirty();
        probeSection("bake-tail");

        if (PROFILE)
        {
            profSpotDemand = Math.max(profSpotDemand, spotRank);
            profPointDemand = Math.max(profPointDemand, pointRank);
            profSpotBehind = Math.max(profSpotBehind, behindSpots);
            profPointBehind = Math.max(profPointBehind, behindPoints);
        }

        // Defensive invariant sweep: per-light dirty state may only exist for
        // current tile owners (steals and resets purge eagerly; this catches
        // anything they miss). Owners DO keep their state across frames they
        // skip — that is the sticky-tile win: a light that returns with its
        // tile and an unchanged signature re-uses its old map, zero rebake.
        ownerIdScratch.clear();
        for (int t = 0; t < spotTileOwner.length; t++)
        {
            if (spotTileOwner[t] != NO_OWNER)
            {
                ownerIdScratch.add(spotTileOwner[t]);
            }
        }
        for (int t = 0; t < pointSlotOwner.length; t++)
        {
            if (pointSlotOwner[t] != NO_OWNER)
            {
                ownerIdScratch.add(pointSlotOwner[t]);
            }
        }
        retainDirtyState(ownerIdScratch);

        // Block-list + VBO caches: keep ALL registered lights so a momentary
        // out-of-range frame doesn't thrash the (expensive) block re-collect.
        // Empty set when the feature is off -> both caches drain.
        liveIds.clear();
        if (ShadowEngine.config().shadowBlocks())
        {
            for (int i = 0; i < n; i++)
            {
                liveIds.add(LightRegistry.getId(i));
            }
        }
        BlockShadowCache.retainOnly(liveIds);
        ShadowRenderer.retainBlockVbos(liveIds);
    }

    /** Sticky allocation: the owner keeps its tile (and is marked active);
     *  otherwise prefer a free tile; otherwise steal the most-stale tile whose
     *  owner hasn't requested for {@link #STALE_FRAMES}+ frames — never one
     *  active this or last frame, so a light that merely iterates later in the
     *  same frame is not robbed (which would ping-pong tiles between two
     *  lights, re-baking both every frame). Returns -1 when nothing is
     *  available; the light then simply casts no shadow this frame.
     *
     *  RANGE SEMANTICS: only indices in {@code [from, to)} are scanned — the
     *  owner check, the free pick and the stale steal are all range-scoped.
     *  {@link #acquireTileTiered} passes one tier's sub-range per call; a
     *  light's sticky ownership is scoped to the scanned range BY DESIGN (a
     *  tile it owns outside the range is invisible here — the cross-tier
     *  handoff of an owner is the caller's job: release-on-bake-success /
     *  keep-sampling-on-refusal, see the loops). */
    private static int acquireTile(long[] owner, int[] active, long id, int from, int to, int capTo)
    {
        int free = -1;
        int stale = -1;
        int staleAge = Integer.MAX_VALUE;
        for (int t = from; t < to; t++)
        {
            if (owner[t] == id)
            {
                active[t] = frameIndex;
                return t;
            }
            if (t >= capTo)
            {
                // ownership above is still honored (updatePointCaps floors the
                // cap at the highest handed-out block, so this only fires in
                // the window before the first prologue), but nothing new is
                // handed out past the cap: no filter layers exist there yet
                continue;
            }
            if (owner[t] == NO_OWNER)
            {
                if (free < 0)
                {
                    free = t;
                }
            }
            else if (frameIndex - active[t] >= STALE_FRAMES && active[t] < staleAge)
            {
                stale = t;
                staleAge = active[t];
            }
        }

        int take = free >= 0 ? free : stale;
        if (take < 0)
        {
            return -1;
        }
        if (free < 0)
        {
            purgeDirtyState(owner[take]); // victim first-bakes if it returns
        }
        owner[take] = id;
        active[take] = frameIndex;
        return take;
    }

    /** Highest handed-out block of point tier {@code t}, as a 1-based count
     *  from the tier start (0 = none owned). The budget's growth floor:
     *  handed-out blocks get backed by the flush regardless of this frame's
     *  approval, so {@link ShadowVramBudget#updatePointCaps} never lets the
     *  cap regress below them (render thread, same thread as bake). */
    static int ownedPointBlocks(int t)
    {
        int start = PointDepthAtlas.tierStartBlock(t);
        for (int b = POINT_TIER_END[t] - 1; b >= start; b--)
        {
            if (pointSlotOwner[b] != NO_OWNER)
            {
                return b - start + 1;
            }
        }
        return 0;
    }

    /** Tier whose range {@code [tierEnd[t-1], tierEnd[t])} contains
     *  {@code index}, or {@link #TIER_NONE} past the last tier. Serves BOTH
     *  spaces (see the {@code SPOT_TIER_END} note): a rank -> desired tier,
     *  and a flat pool index -> the tier of an owned tile. */
    private static int tierForIndex(int index, int[] tierEnd)
    {
        for (int t = 0; t < tierEnd.length; t++)
        {
            if (index < tierEnd[t])
            {
                return t;
            }
        }
        return TIER_NONE;
    }

    /** The tile this light currently owns per the dirty cache:
     *  {@code lastTile[id]} if present AND still ours in the pool, else the
     *  same check on {@code lastStaticTile[id]}, else -1 (stolen / never
     *  baked / purged). The static fallback matters for OVERLAY-mode lights
     *  (the primary film-scene case): dyn frames refresh {@code lastTile} at
     *  the loop tail, but a light whose most recent tile record came from an
     *  overlay STATIC bake (e.g. its live record was refused mid-flip) is
     *  still anchored by {@code lastStaticTile} — without the fallback such
     *  lights would read as tier-less every frame, losing the Schmitt
     *  hysteresis exactly where rank jitter turns into per-frame BAKE_FORCED
     *  full bakes. */
    private static int ownedPrevTile(long[] owner, long id)
    {
        int p = ownedTileFrom(lastTile, owner, id);
        return p >= 0 ? p : ownedTileFrom(lastStaticTile, owner, id);
    }

    /** {@code map[id]} if present and still owned by {@code id} in the pool,
     *  else -1. */
    private static int ownedTileFrom(Long2IntOpenHashMap map, long[] owner, long id)
    {
        if (!map.containsKey(id))
        {
            return -1;
        }
        int p = map.get(id);
        return p >= 0 && p < owner.length && owner[p] == id ? p : -1;
    }

    /** Schmitt-hysteresis tier choice (I3). {@code lastTier} is the tier of
     *  the tile the light currently owns ({@link #TIER_NONE} if none).
     *  EAGER PROMOTE: a raw tier better (smaller) than the owned one is taken
     *  immediately — the acquire fallback chain absorbs contention without
     *  churn (a full better tier just falls back to the still-owned tile).
     *  LAZY DEMOTE: a worse raw tier (larger, or NONE) is accepted only once
     *  the rank is clearly past the owned tier's rank range
     *  ({@code >= tierEnd[lastTier] + DEMOTE_MARGIN}); until then the light
     *  HOLDS its tier, so lights oscillating across a tier boundary don't
     *  re-bake every frame. No owned tile -> the raw tier as-is. */
    private static int desiredTier(int rank, int lastTier, int[] tierEnd)
    {
        int raw = tierForIndex(rank, tierEnd);
        if (lastTier == TIER_NONE || raw <= lastTier)
        {
            return raw;
        }
        return rank >= tierEnd[lastTier] + DEMOTE_MARGIN ? raw : lastTier;
    }

    /** Acquire with tier fallback (I3): try the desired tier's pool sub-range
     *  first, then every worse tier down to the last. First success wins; -1
     *  when every tile of every tier from {@code desired} down is owned by a
     *  recently-active light (the caller then keeps its still-owned tile if it
     *  has one, else leaves the light unshadowed like the old single-pool -1).
     *  The flat pool index IS the value
     *  published to vlParams.w (spot flat tile / point global slot).
     *  {@code tierCap} bounds what may be handed OUT per tier (point: the
     *  budget-approved filter capacity; spot passes its tierEnd = no cap). */
    private static int acquireTileTiered(long[] owner, int[] active, long id, int desired, int[] tierEnd, int[] tierCap)
    {
        for (int t = desired; t < tierEnd.length; t++)
        {
            int tile = acquireTile(owner, active, id, t == 0 ? 0 : tierEnd[t - 1], tierEnd[t], tierCap[t]);
            if (tile >= 0)
            {
                return tile;
            }
        }
        return -1;
    }

    /** Spare-capacity acquire for a light ranked past the WHOLE pool: its own
     *  still-owned tile first, else a free slot, else the stalest slot not
     *  touched for {@code STALE_FRAMES + 1}+ frames (a long-dead owner — gone
     *  lights are otherwise only reclaimed by in-pool steals), preferring
     *  worse tiers (lowest index within the worst tier that has room, so
     *  demand-sized filter arrays stay compact) — the good tiers stay with
     *  real ranks.
     *  NEVER steals from a live holder: full-strength stamps are observed at
     *  age <= 1, and a live spare holder re-stamps {@code frameIndex - 1}
     *  every frame so its observed age is exactly {@link #STALE_FRAMES} —
     *  under this reclaim threshold (no spare-vs-spare thrash) but exactly AT
     *  the in-pool steal threshold of {@link #acquireTile}, which is the
     *  design: spare tenure lasts precisely until real demand shows up (the
     *  claimant iterates earlier in rank order, so within one frame it steals
     *  BEFORE the spare holder would re-stamp — no foreign-w window). A LIVE
     *  owner that merely stopped refreshing (a behind-culled light demoted
     *  past its margin) counts as reclaimable BY DESIGN: a visible past-pool
     *  shadow outranks an invisible cached map, and the returning owner
     *  first-bakes (mandatory-gated) only if actually reclaimed. */
    private static int acquireSpareTile(long[] owner, int[] active, long id, int[] tierEnd, int[] tierCap)
    {
        int last = tierEnd[tierEnd.length - 1];
        for (int t = 0; t < last; t++)
        {
            if (owner[t] == id)
            {
                active[t] = frameIndex - 1;
                return t;
            }
        }
        // Worse tiers first (so the good tiers stay with real ranks), but
        // LOWEST index within a tier — compact ownership keeps the
        // demand-sized filter arrays small (a spare grab at the top of tier 2
        // would otherwise force the whole tier's layers to allocate).
        int free = -1;
        int dead = -1;
        int deadAge = Integer.MAX_VALUE;
        for (int tier = tierEnd.length - 1; tier >= 0; tier--)
        {
            int from = tier == 0 ? 0 : tierEnd[tier - 1];
            int to = Math.min(tierEnd[tier], tierCap[tier]);
            for (int t = from; t < to; t++)
            {
                if (owner[t] == NO_OWNER)
                {
                    if (free < 0)
                    {
                        free = t;
                    }
                }
                else if (frameIndex - active[t] > STALE_FRAMES && active[t] < deadAge)
                {
                    dead = t;
                    deadAge = active[t];
                }
            }
        }
        int take = free >= 0 ? free : dead;
        if (take < 0)
        {
            return -1;
        }
        if (free < 0)
        {
            purgeDirtyState(owner[take]); // victim first-bakes if it returns
        }
        owner[take] = id;
        active[take] = frameIndex - 1;
        return take;
    }

    /** RELEASE-OLD half of the zero-blink tier handoff (I3): a bake just
     *  SUCCEEDED into {@code myTile}; if the light still owns a DIFFERENT old
     *  tile, free it for the pool. No purge of the TAKER side — the old
     *  content belonged to THIS light, and the next taker first-bakes anyway
     *  via its own lastTile mismatch. But THIS light's own
     *  lastStaticSig/lastStaticTile/lastStaticBlocks records must not survive
     *  pointing at the freed tile: pre-I3 a tile could only be lost via steal
     *  (purgeDirtyState) or reset (clear), so the overlay staticStale check
     *  never met a stale record — release-without-purge is a third loss path,
     *  and a later re-acquisition of the SAME tile (after a foreign light
     *  overlay-baked its base into the tile's static layer) would wrongly
     *  pass staticStale and copyStaticToLive a FOREIGN silhouette every
     *  frame. The removal is VALUE-KEYED (only when the record points at
     *  {@code prevTile}), so the overlay forced-bake call sites — which put
     *  lastStaticTile = myTile just before releasing — keep their fresh
     *  records. INVARIANT (honored at every publish site): vlParams.w never
     *  points at a tile whose pool owner is not this light — by the time this
     *  runs the SSBO already points at {@code myTile}, so freeing the old
     *  tile can orphan nothing. */
    private static void releaseOldTile(long[] owner, long id, int prevTile, int myTile)
    {
        // POOL-SCAN, not prevTile-only: ownership can stray from the dirty
        // maps — a first-bake budget refusal (SHADOW_PENDING) keeps the slot
        // but records no lastTile, and a later rank shift can acquire a
        // DIFFERENT tile, leaving an orphan no map-keyed release ever frees
        // (its eventual steal would purgeDirtyState this light's LIVE state on
        // its new tile: spurious full re-bake + a possible one-frame blink).
        // A successful bake into myTile is the one moment every other owned
        // slot is provably unpublished (the SSBO already points at myTile), so
        // free them all. O(pool) per successful bake — negligible next to it.
        for (int t = 0; t < owner.length; t++)
        {
            if (owner[t] == id && t != myTile)
            {
                owner[t] = NO_OWNER;
                if (lastStaticTile.containsKey(id) && lastStaticTile.get(id) == t)
                {
                    lastStaticSig.remove(id);
                    lastStaticTile.remove(id);
                    lastStaticBlocks.remove(id);
                }
            }
        }
    }

    /** A re-bake whose own valid map still sits on the tile: deferrable, so it
     *  runs only while the deferrable budget remains. */
    private static final int BAKE_DEFERRABLE = 0;
    /** First bake / just-reassigned tile: no own map to fall back on, so it can't
     *  be freely deferred — but it IS throttled by {@link #mandatoryBakeBudget}
     *  to cap the cold-start spike. When the gate refuses, the caller marks the
     *  light shadow-pending so flush OMITS it (no shadow -> no light, not
     *  unshadowed-through-walls) and retries next frame (C2). */
    private static final int BAKE_MANDATORY = 1;
    /** A subject leaving a lamp (dyn->static in the overlay path): the transition
     *  bookkeeping can't carry over, so the base must bake this frame — never
     *  throttled. */
    private static final int BAKE_FORCED = 2;

    /** Budget gate for one FULL STATIC bake (T2.4 / C2). By {@code kind}:
     *  {@link #BAKE_DEFERRABLE} runs only while {@link #staticBakeBudget} > 0;
     *  {@link #BAKE_MANDATORY} runs only while {@link #mandatoryBakeBudget} > 0
     *  (bounding the cold-start spike); {@link #BAKE_FORCED} always runs. A bake
     *  that runs consumes one unit of {@link #staticBakeBudget} either way (so
     *  mandatory/forced bakes defer the frame's remaining deferrable bakes), and
     *  the two mandatory kinds also consume one unit of {@link #mandatoryBakeBudget}
     *  (so a forced bake can't let an extra cold bake slip past the frame's cap).
     *  Both budgets may go below zero. Returns true to bake now, false to defer —
     *  the caller then falls back (keep the existing map, or mark the light
     *  shadow-pending so flush omits it for a refused mandatory bake) and leaves
     *  its dirty state untouched so the same re-bake is retried next frame. */
    private static boolean allowStaticBake(int kind)
    {
        if (kind == BAKE_MANDATORY && mandatoryBakeBudget <= 0)
        {
            return false;
        }
        if (kind == BAKE_DEFERRABLE && staticBakeBudget <= 0)
        {
            return false;
        }
        staticBakeBudget--;
        if (kind != BAKE_DEFERRABLE)
        {
            mandatoryBakeBudget--;
        }
        return true;
    }

    /** Drop one light's dirty state so its next bake is a clean first bake
     *  (used when a tile is stolen — its map content now belongs to another
     *  light). */
    private static void purgeDirtyState(long id)
    {
        spotOverlays.forget(id);
        pointOverlays.forget(id);
        lastSig.remove(id);
        lastTile.remove(id);
        lastBlocks.remove(id);
        lastStaticSig.remove(id);
        lastStaticTile.remove(id);
        lastStaticBlocks.remove(id);
        lastFaceDynamic.remove(id);
        lastDynRect.remove(id);
        wasDynamic.remove(id);
        contentionHold.remove(id);
    }

    /** Forget all tile ownership + per-light dirty state (no lights left, or
     *  the depth textures were just re-allocated): everything that returns
     *  first-bakes into a fresh tile. */
    private static void resetTileState()
    {
        spotOverlays.clear();
        pointOverlays.clear();
        overlayMembers.clear();
        Arrays.fill(occ, null);
        Arrays.fill(oRevision, null);
        Arrays.fill(spotMembersBySlot, null);
        Arrays.fill(spotTileOwner, NO_OWNER);
        Arrays.fill(pointSlotOwner, NO_OWNER);
        lastSig.clear();
        lastTile.clear();
        lastBlocks.clear();
        lastStaticSig.clear();
        lastStaticTile.clear();
        lastStaticBlocks.clear();
        lastFaceDynamic.clear();
        lastDynRect.clear();
        wasDynamic.clear();
        contentionHold.clear();
    }

    /** Shaders just went off: nothing samples the shadow maps anymore. Forget
     *  all tile ownership + dirty state and free the depth textures and the
     *  block/VBO caches (same drain as the no-lights path, plus the textures).
     *  Everything re-allocates lazily and first-bakes when shaders return. */
    public static void onShadersDisabled()
    {
        resetTileState();
        liveIds.clear();
        BlockShadowCache.retainOnly(liveIds);
        ShadowRenderer.retainBlockVbos(liveIds);
        SpotlightDepthAtlas.delete();
        PointDepthAtlas.delete();
    }

    /** Record that the LIVE tile now holds this light's pure static content
     *  (a static bake or a static->live copy), or — with {@code dyn} — that it
     *  contains dynamic casters and must run the overlay path until they go. */
    private static void rememberLive(long id, long sig, int tile, List<BlockShadowEntry> blocks, boolean dyn)
    {
        lastSig.put(id, sig);
        lastTile.put(id, tile);
        lastBlocks.put(id, blocks);
        if (dyn)
        {
            wasDynamic.add(id);
        }
        else
        {
            wasDynamic.remove(id);
        }
    }

    /** Accumulate one frame into the profiler window and log roughly once per
     *  second (only ever called with -Dirlite.profileShadows=true). */
    private static void profRecordFrame(long nanos)
    {
        profNanos += nanos;
        if (nanos > profMaxNanos)
        {
            profMaxNanos = nanos;
        }
        profFrames++;

        long now = System.nanoTime();
        if (profWindowStart == 0L)
        {
            profWindowStart = now;
            return;
        }
        if (now - profWindowStart < 1_000_000_000L)
        {
            return;
        }

        System.out.println(String.format(Locale.ROOT,
            "[irlite] shadows: bake avg %.2f ms, max %.2f ms | static bakes: %d spot, %d point(x6) | overlays: %d spot, %d point | demand: spot %d/%d (behind %d), point %d/%d (behind %d) | occluders %d, lights %d | %d frames",
            profNanos / 1e6 / Math.max(profFrames, 1), profMaxNanos / 1e6,
            profSpotBakes, profPointBakes, profSpotOverlays, profPointOverlays,
            profSpotDemand, SPOT_TIER_END[SPOT_TIER_END.length - 1], profSpotBehind,
            profPointDemand, POINT_TIER_END[POINT_TIER_END.length - 1], profPointBehind,
            occCount, LightRegistry.getCount(), profFrames));

        profWindowStart = now;
        profNanos = 0L;
        profMaxNanos = 0L;
        profFrames = 0;
        profSpotBakes = 0;
        profPointBakes = 0;
        profSpotOverlays = 0;
        profPointOverlays = 0;
        profSpotDemand = 0;
        profPointDemand = 0;
        profSpotBehind = 0;
        profPointBehind = 0;
    }

    /** Drop per-light dirty state for ids not in {@code keep}. An empty set
     *  drains it. */
    private static void retainDirtyState(LongSet keep)
    {
        spotOverlays.retain(keep::contains);
        pointOverlays.retain(keep::contains);
        if (!lastSig.isEmpty())
        {
            lastSig.keySet().retainAll(keep);
        }
        if (!lastTile.isEmpty())
        {
            lastTile.keySet().retainAll(keep);
        }
        if (!lastBlocks.isEmpty())
        {
            lastBlocks.keySet().retainAll(keep);
        }
        if (!lastStaticSig.isEmpty())
        {
            lastStaticSig.keySet().retainAll(keep);
        }
        if (!lastStaticTile.isEmpty())
        {
            lastStaticTile.keySet().retainAll(keep);
        }
        if (!lastStaticBlocks.isEmpty())
        {
            lastStaticBlocks.keySet().retainAll(keep);
        }
        if (!lastFaceDynamic.isEmpty())
        {
            lastFaceDynamic.keySet().retainAll(keep);
        }
        if (!lastDynRect.isEmpty())
        {
            lastDynRect.keySet().retainAll(keep);
        }
        if (!wasDynamic.isEmpty())
        {
            wasDynamic.retainAll(keep);
        }
        if (!contentionHold.isEmpty())
        {
            contentionHold.keySet().retainAll(keep);
        }
    }

    /** Block occluders around a light, clamped to the configurable
     *  {@link ShadowConfig#shadowBlockRadius()} (default 24). Empty when the
     *  feature is off. Backed by the identity-keyed {@link BlockShadowCache}
     *  (O(1) on a hit; recollects on light-move, radius change, or a block change
     *  in range). */
    private static List<BlockShadowEntry> collectBlocks(long id, ClientLevel world, float lx, float ly, float lz, float range)
    {
        if (!ShadowEngine.config().shadowBlocks() || world == null)
        {
            return Collections.emptyList();
        }
        return BlockShadowCache.getOrCompute(id, world, lx, ly, lz, Math.min(range, (float) ShadowEngine.config().shadowBlockRadius()));
    }

    /** FNV-1a fold of one float (raw bits) into a running hash. */
    private static long mix(long h, float v)
    {
        return (h ^ (Float.floatToRawIntBits(v) & 0xffffffffL)) * FNV_PRIME;
    }

    /** SplitMix64 finalizer — a full-avalanche scramble of one 64-bit value, used to
     *  fold per-occluder static hashes order-independently without the additive
     *  cancellation a plain sum allows (seam INVARIANT 3). */
    private static long mix64(long z)
    {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Signature of a light's own bake-relevant geometry: position, direction,
     *  range, spot cone (cosOuter), and the shadows flag. Any change re-bakes. */
    private static long lightGeomSig(float lx, float ly, float lz, float dx, float dy, float dz, float range, float cosOuter, boolean shadows)
    {
        long h = FNV_OFFSET;
        h = mix(h, lx); h = mix(h, ly); h = mix(h, lz);
        h = mix(h, dx); h = mix(h, dy); h = mix(h, dz);
        h = mix(h, range); h = mix(h, cosOuter);
        h = (h ^ (shadows ? 1L : 0L)) * FNV_PRIME;
        return h;
    }

    /** Count in-range occluders and, as a side effect, set
     *  {@link #dynamicInRangeScratch} (any entity/replay in range) and
     *  {@link #staticOccSigScratch} (order-independent sum of in-range
     *  model-block hashes). reach = reachBase + occluderRadius. When {@code cone}
     *  is set (spotlights), occluders fully outside the lit cone (unit axis
     *  dirX/Y/Z, half-angle coneTheta) are skipped: they can only shadow unlit
     *  fragments, so excluding them both avoids the draw AND stops an out-of-cone
     *  subject from dirtying the light. Points pass cone=false (omnidirectional;
     *  the per-face frustum cull is in {@link #renderInRangeFace}). */
    private static int scanInRange(float lx, float ly, float lz, float reachBase,
                                   float dirX, float dirY, float dirZ, float coneTheta, boolean cone)
    {
        overlayMembers.clear(!NO_OVERLAY_REUSE && ShadowEngine.config().shadowCache());
        int sc = 0;
        int statics = 0;
        boolean dyn = false;
        long sig = 0L;
        int dynFaces = 0;
        for (int k = 0; k < occCount; k++)
        {
            float ddx = ox[k] - lx, ddy = oy[k] - ly, ddz = oz[k] - lz;
            float reach = reachBase + orad[k];
            float d2c = ddx * ddx + ddy * ddy + ddz * ddz;
            if (d2c > reach * reach)
            {
                if (ShadowClipTelemetry.ENABLED)
                {
                    // Probe: a sphere honest up to xMUL+ADD would have passed ->
                    // potential whole-shadow vanish at the range boundary.
                    float probeReach = reachBase
                        + orad[k] * ShadowClipTelemetry.PROBE_MUL + ShadowClipTelemetry.PROBE_ADD;
                    if (d2c <= probeReach * probeReach)
                    {
                        ShadowClipTelemetry.noteRangeMiss(occType[k], ox[k], oy[k], oz[k],
                            (float) Math.sqrt(d2c) - reach);
                    }
                }
                continue;
            }
            if (cone && !insideCone(dirX, dirY, dirZ, coneTheta, ddx, ddy, ddz, orad[k]))
            {
                if (ShadowClipTelemetry.ENABLED
                    && insideCone(dirX, dirY, dirZ, coneTheta, ddx, ddy, ddz,
                        orad[k] * ShadowClipTelemetry.PROBE_MUL + ShadowClipTelemetry.PROBE_ADD))
                {
                    // Probe flips the cone verdict -> potential vanish at the cone edge.
                    ShadowClipTelemetry.noteConeMiss(occType[k], ox[k], oy[k], oz[k]);
                }
                continue;
            }
            if (ShadowClipTelemetry.ENABLED)
            {
                // Declared sphere already crosses the bake far plane (= reachBase):
                // the caster's far side is range-clipped even with an honest sphere.
                // The far cut is AXIAL view-depth, not radial distance — spot views
                // look along the cone axis, point faces along each face axis (the
                // dominant component bounds all touched faces) — so measure the
                // matching axial depth or casters near the radial boundary would
                // report phantom far-crossings. (A degenerate-dir spot scans with
                // cone=false and gets the point-style bound; telemetry-only.)
                float axial = cone
                    ? ddx * dirX + ddy * dirY + ddz * dirZ
                    : Math.max(Math.abs(ddx), Math.max(Math.abs(ddy), Math.abs(ddz)));
                float overhang = axial + orad[k] - reachBase;
                if (overhang > 0f)
                {
                    ShadowClipTelemetry.noteFarCross(occType[k], ox[k], oy[k], oz[k], overhang);
                }
            }
            // Passed range (+ cone for spots): shortlist it so the render passes
            // re-use this verdict instead of re-testing. For POINT scans
            // (cone == false) also record which cube faces this occluder's sphere
            // touches (k = radius·√2, exactly as in renderInRangeFace), so the
            // face render + overlay loop read the bit instead of recomputing it.
            int faceMask = 0;
            if (!cone)
            {
                float kr = orad[k] * SQRT2;
                for (int face = 0; face < 6; face++)
                {
                    if (sphereTouchesFace(face, ddx, ddy, ddz, kr))
                    {
                        faceMask |= 1 << face;
                    }
                }
                if (ShadowClipTelemetry.ENABLED)
                {
                    float pkr = (orad[k] * ShadowClipTelemetry.PROBE_MUL
                        + ShadowClipTelemetry.PROBE_ADD) * SQRT2;
                    int probeMask = 0;
                    for (int face = 0; face < 6; face++)
                    {
                        if (sphereTouchesFace(face, ddx, ddy, ddz, pkr))
                        {
                            probeMask |= 1 << face;
                        }
                    }
                    int missingFaces = probeMask & ~faceMask;
                    if (missingFaces != 0)
                    {
                        // Probe reaches faces the declared sphere is not drawn
                        // into -> potential razor cut along a cube-face seam.
                        // Gained-faces only: a sub-1 clipProbeMul (shrinking
                        // probe) must go silent, not count lost faces.
                        ShadowClipTelemetry.noteFaceMiss(occType[k], ox[k], oy[k], oz[k],
                            missingFaces);
                    }
                }
            }
            shortIdx[sc] = k;
            shortFaceMask[sc] = faceMask;
            sc++;
            if (oStatic[k])
            {
                // INVARIANT 3: avalanche-mix each static hash before folding (a plain
                // additive sum is non-injective — compensating paired edits cancel).
                // Order-independent (commutative XOR of mixed values). Inert on
                // redactor (no static casters); live for IRLite model blocks.
                sig ^= mix64(ostatichash[k]);
                statics++;
            }
            else
            {
                dyn = true; // entity or film replay -> dynamic subject
                dynFaces |= faceMask; // per-face: which faces a dynamic caster reaches
                if (cone)
                {
                    overlayMembers.addSpot(spotMembersBySlot, k, occ[k], occType[k], oRevision[k],
                        ox[k], oy[k], oz[k], orad[k], orh[k], ohv[k]);
                }
                else
                {
                    overlayMembers.add(occ[k], occType[k], oRevision[k],
                        ox[k], oy[k], oz[k], orad[k], orh[k], ohv[k], faceMask);
                }
            }
        }
        shortCount = sc;
        dynFaceMaskScratch = dynFaces;
        dynamicInRangeScratch = dyn;
        staticOccSigScratch = sig;
        staticInRangeScratch = statics;
        return sc;
    }

    /** Caster-type filters for the renderInRange* helpers: everything (legacy
     *  no-cache path), only model blocks (the static base layer), or only
     *  entities/replays (the per-frame dynamic overlay). */
    private static final int CASTERS_ALL = 0;
    private static final int CASTERS_STATIC = 1;
    private static final int CASTERS_DYNAMIC = 2;

    private static boolean casterMatches(int filter, boolean isStatic)
    {
        if (filter == CASTERS_STATIC)
        {
            return isStatic;
        }
        if (filter == CASTERS_DYNAMIC)
        {
            return !isStatic;
        }
        return true;
    }

    /** Render shortlisted occluders of the filtered type inside a spot's lit
     *  cone. The range + cone test already ran in {@link #scanInRange}, whose
     *  shortlist this walks, so the rendered set equals the counted set that
     *  gated this bake (the scan == render invariant). Casters are batched into a
     *  single GPU flush per pass (T2.2): the begin/buffer/end bracket wraps the
     *  loop so one immediate.draw submits them all. */
    private static void renderInRangeCone(int filter, float tickDelta)
    {
        ShadowRenderer.beginCasterBatch();
        for (int s = 0; s < shortCount; s++)
        {
            int k = shortIdx[s];
            if (!casterMatches(filter, oStatic[k]))
            {
                continue;
            }
            ShadowRenderer.emitCaster(ShadowEngine.source(), occ[k], occType[k], tickDelta);
        }
        ShadowRenderer.endCasterBatch();
    }

    /** Render shortlisted occluders of the filtered type whose sphere touches
     *  ONE point-cube face's 90° frustum (face index per
     *  {@link ShadowRenderer#beginPointFace}); the other five faces never see
     *  them, removing ~5/6 of the caster draws per point. The face membership
     *  is the {@code shortFaceMask} bit computed once in {@link #scanInRange}. */
    private static void renderInRangeFace(int face, int filter, float tickDelta)
    {
        int bit = 1 << face;
        ShadowRenderer.beginCasterBatch();
        for (int s = 0; s < shortCount; s++)
        {
            if ((shortFaceMask[s] & bit) == 0)
            {
                continue;
            }
            int k = shortIdx[s];
            if (!casterMatches(filter, oStatic[k]))
            {
                continue;
            }
            ShadowRenderer.emitCaster(ShadowEngine.source(), occ[k], occType[k], tickDelta);
        }
        ShadowRenderer.endCasterBatch();
    }

    /**
     * NDC-project the dynamic shortlist casters' AABBs into the spot's tile
     * and return the union as a TILE-LOCAL depth-pixel rect ({@link ShadowRect}
     * packing), or {@link ShadowRect#FULL} when the partial path can't be
     * trusted this frame: degenerate direction, a caster corner at/behind the
     * near plane (a sphere containing the light projects unbounded), an empty
     * dynamic set (defensive — scan==render says it can't happen when dyn), or
     * a union covering most of the tile anyway. Mirrors
     * {@link ShadowRenderer#beginSpot}'s matrices exactly: anchor A =
     * round((float) light), eye = L - A, same up rule, NEAR 0.05, aspect 1 —
     * a convex box in front of the near plane projects inside the convex hull
     * of its 8 projected corners, so the AABB bounds the depth writes
     * conservatively. Every caster uses its REAL per-axis half-extents
     * ({@link #orh}/{@link #ohv}; the raw-sphere path stores the radius in
     * both) plus a pose/oversize slack on both axes ({@link
     * ShadowConfig#shadowPoseReach()}, a live user knob, deliberately
     * UNCAPPED): sources bound casters by their HITBOX while BBS content
     * routinely draws past it (wide poses, stretched form models), so the
     * knob must be able to outgrow the hitbox's cull sphere in any
     * direction. The scissor set from this rect is the HARD bound for those
     * writes, so an under-estimate degrades to visible silhouette clipping,
     * never to the filters missing fresh depth. {@code id} is the light id,
     * consumed only by the clip telemetry ({@link ShadowClipTelemetry}).
     */
    private static long computeSpotDynRect(float lx, float ly, float lz,
                                           double lxD, double lyD, double lzD,
                                           float ndx, float ndy, float ndz, boolean validDir,
                                           float range, float outerDeg, int ts, long id)
    {
        if (!validDir)
        {
            return ShadowRect.FULL;
        }
        // Beyond 2^23 blocks a float coordinate's ulp reaches 1.0: the SoA
        // centers (cast to float at collect) can drift up to ~0.5 block off
        // the double-anchored positions the casters actually draw at, eroding
        // the sphere's slack — fall back to the full tile out there rather
        // than risk the hard scissor clipping a silhouette edge.
        if (Math.abs(lx) > 8_388_608f || Math.abs(ly) > 8_388_608f || Math.abs(lz) > 8_388_608f)
        {
            return ShadowRect.FULL;
        }
        int axi = Math.round(lx), ayi = Math.round(ly), azi = Math.round(lz);
        float ex = (float) (lxD - axi), ey = (float) (lyD - ayi), ez = (float) (lzD - azi);
        float fovDeg = Math.max(outerDeg, 1.0f);
        float far = Math.max(range, 0.05f + 0.1f);
        boolean zUp = Math.abs(ndy) > 0.99f; // ShadowRenderer.pickStableUp
        dynRectMatrix.identity()
            .perspective((float) Math.toRadians(fovDeg), 1.0f, 0.05f, far)
            .lookAt(ex, ey, ez, ex + ndx, ey + ndy, ez + ndz,
                    0f, zUp ? 0f : 1f, zUp ? 1f : 0f);

        // Live user knob; sanitize so a malformed config value degrades to the
        // built-in default instead of NaN-poisoning the corner math.
        float poseReach = ShadowEngine.config().shadowPoseReach();
        if (!(poseReach >= 0f))
        {
            poseReach = 1.0f;
        }
        float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        boolean any = false;
        // Telemetry probe union (inflated extents); bail mirrors the near-plane rule.
        float pMinU = Float.POSITIVE_INFINITY, pMinV = Float.POSITIVE_INFINITY;
        float pMaxU = Float.NEGATIVE_INFINITY, pMaxV = Float.NEGATIVE_INFINITY;
        boolean probeBailed = false;
        for (int s = 0; s < shortCount; s++)
        {
            int k = shortIdx[s];
            if (oStatic[k])
            {
                continue;
            }
            any = true;
            float cx = ox[k] - axi, cy = oy[k] - ayi, cz = oz[k] - azi;
            // Real per-axis half-extents + pose/oversize slack on BOTH axes,
            // deliberately UNCAPPED: sources bound casters by their HITBOX
            // (entity box, form hitbox — including the model-block sphere,
            // where rh==hv==radius), and BBS content routinely DRAWS past it
            // — wide animation poses, stretched/oversized form models. The
            // knob must be able to outgrow the hitbox's cull sphere in any
            // direction; an oversized box just degrades to FULL via the
            // coversMost gate (= the old full-tile behavior).
            float slack = Math.max(OVERLAP_MARGIN, poseReach * ohv[k]);
            float hh = orh[k] + slack;
            float hy = ohv[k] + slack;
            for (int corner = 0; corner < 8; corner++)
            {
                dynRectVec.set(
                    cx + (((corner & 1) == 0) ? -hh : hh),
                    cy + (((corner & 2) == 0) ? -hy : hy),
                    cz + (((corner & 4) == 0) ? -hh : hh),
                    1f);
                dynRectMatrix.transform(dynRectVec);
                if (dynRectVec.w < 0.05f)
                {
                    return ShadowRect.FULL; // corner at/behind the near plane
                }
                float u = (dynRectVec.x / dynRectVec.w * 0.5f + 0.5f) * ts;
                float v = (dynRectVec.y / dynRectVec.w * 0.5f + 0.5f) * ts;
                minU = Math.min(minU, u);
                minV = Math.min(minV, v);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, v);
            }
            if (ShadowClipTelemetry.ENABLED && !probeBailed)
            {
                // Same projection with probe-inflated extents: if the probe rect
                // outgrows the armed rect, geometry that much past the hitbox is
                // being scissored (the documented silhouette-clip failure mode).
                float phh = orh[k] * ShadowClipTelemetry.PROBE_MUL + ShadowClipTelemetry.PROBE_ADD + slack;
                float phy = ohv[k] * ShadowClipTelemetry.PROBE_MUL + ShadowClipTelemetry.PROBE_ADD + slack;
                for (int corner = 0; corner < 8; corner++)
                {
                    dynRectVec.set(
                        cx + (((corner & 1) == 0) ? -phh : phh),
                        cy + (((corner & 2) == 0) ? -phy : phy),
                        cz + (((corner & 4) == 0) ? -phh : phh),
                        1f);
                    dynRectMatrix.transform(dynRectVec);
                    if (dynRectVec.w < 0.05f)
                    {
                        probeBailed = true;
                        break;
                    }
                    float pu = (dynRectVec.x / dynRectVec.w * 0.5f + 0.5f) * ts;
                    float pv = (dynRectVec.y / dynRectVec.w * 0.5f + 0.5f) * ts;
                    pMinU = Math.min(pMinU, pu);
                    pMinV = Math.min(pMinV, pv);
                    pMaxU = Math.max(pMaxU, pu);
                    pMaxV = Math.max(pMaxV, pv);
                }
            }
        }
        if (!any)
        {
            return ShadowRect.FULL;
        }
        // +1 px slack against rasterization rounding; clamp into the tile.
        int x0 = Math.max(0, (int) Math.floor(minU) - 1);
        int y0 = Math.max(0, (int) Math.floor(minV) - 1);
        int x1 = Math.min(ts, (int) Math.ceil(maxU) + 1);
        int y1 = Math.min(ts, (int) Math.ceil(maxV) + 1);
        if (x1 <= x0 || y1 <= y0)
        {
            return ShadowRect.FULL; // fully off-tile after clamp — shouldn't happen for cone-culled casters
        }
        long rect = ShadowRect.pack(x0, y0, x1, y1);
        if (ShadowRect.coversMost(rect, ts, COVERS_MOST_NUM, COVERS_MOST_DEN))
        {
            return ShadowRect.FULL;
        }
        if (ShadowClipTelemetry.ENABLED)
        {
            if (probeBailed)
            {
                // Probe corner at/behind the near plane while the armed rect is
                // partial: report a full-tile-magnitude escape.
                ShadowClipTelemetry.noteRectTight(id, ts);
            }
            else
            {
                int px0 = Math.max(0, (int) Math.floor(pMinU) - 1);
                int py0 = Math.max(0, (int) Math.floor(pMinV) - 1);
                int px1 = Math.min(ts, (int) Math.ceil(pMaxU) + 1);
                int py1 = Math.min(ts, (int) Math.ceil(pMaxV) + 1);
                int escape = Math.max(Math.max(x0 - px0, px1 - x1), Math.max(y0 - py0, py1 - y1));
                if (escape > 0)
                {
                    ShadowClipTelemetry.noteRectTight(id, escape);
                }
            }
        }
        return rect;
    }

    /** Small angular slack (radians) added to the spot cone test so a subject
     *  right at the cone edge is never wrongly culled. */
    private static final float CONE_ANGLE_MARGIN = 0.05f;
    private static final float SQRT2 = 1.4142135f;

    /** True unless an occluder sphere (offset V = center - lightPos, radius r) is
     *  ENTIRELY outside the spot's lit cone (unit axis dir, half-angle coneTheta).
     *  phi = angle of V off the axis; alpha = the sphere's angular radius. If
     *  phi - alpha > coneTheta the whole sphere sits at a larger axis angle than
     *  any lit fragment, so it can shadow only unlit fragments and is safe to
     *  drop. Conservative otherwise (a kept occluder may still be clipped by the
     *  bake projection). */
    private static boolean insideCone(float dirX, float dirY, float dirZ, float coneTheta,
                                      float vx, float vy, float vz, float r)
    {
        float d2 = vx * vx + vy * vy + vz * vz;
        if (d2 <= r * r)
        {
            return true; // light sits inside the occluder sphere -> can't cull
        }
        float dist = (float) Math.sqrt(d2);
        float cosPhi = (vx * dirX + vy * dirY + vz * dirZ) / dist; // dir is unit
        float phi = (float) Math.acos(Mth.clamp(cosPhi, -1f, 1f));
        float alpha = (float) Math.asin(Mth.clamp(r / dist, 0f, 1f));
        return phi - alpha <= coneTheta + CONE_ANGLE_MARGIN;
    }

    /** Conservative sphere-vs-cube-face-frustum test. The 90° face frustum's four
     *  side planes pass through the light with inward normals (axis ± tangent);
     *  the sphere lies outside the frustum iff it is fully beyond one of them.
     *  {@code k} = sphere radius · √2 (the plane-normal magnitude folded in).
     *  pd = signed offset along the face axis, a/b = |offset| along the two
     *  tangents; keep iff pd + k reaches both tangents. */
    private static boolean sphereTouchesFace(int face, float vx, float vy, float vz, float k)
    {
        float pd, a, b;
        switch (face)
        {
            case 0:  pd =  vx; a = Math.abs(vy); b = Math.abs(vz); break; // +X
            case 1:  pd = -vx; a = Math.abs(vy); b = Math.abs(vz); break; // -X
            case 2:  pd =  vy; a = Math.abs(vx); b = Math.abs(vz); break; // +Y
            case 3:  pd = -vy; a = Math.abs(vx); b = Math.abs(vz); break; // -Y
            case 4:  pd =  vz; a = Math.abs(vx); b = Math.abs(vy); break; // +Z
            default: pd = -vz; a = Math.abs(vx); b = Math.abs(vy); break; // -Z
        }
        float lim = pd + k;
        return lim >= a && lim >= b;
    }

    /** The variant-specific caster source behind the seam (redactor: BBS-free,
     *  vanilla entities). {@link #collect} routes through it; the orchestration
     *  otherwise touches casters only as the faceless SoA + the two seam methods
     *  ({@code collect} / {@code emitOccluder}). */

    /** Allocation-free SoA writer the source fills (one slot per emit; once the
     *  bounded pool is full, {@link #put} keeps the nearest entries by camera distance —
     *  a nearer newcomer replaces the farthest kept entry, OPEN-2 fix).
     *  {@code emitFromBox} computes the cull-pinned bounding
     *  sphere (mid-height center + circumscribing box-diagonal radius, INVARIANT 5)
     *  so no source can supply a foreign sphere, plus the per-axis half-extents
     *  ({@link #orh}/{@link #ohv}) the spot dyn-rect tightens with. */
    private static final OccluderSink SINK = new OccluderSink()
    {
        @Override
        public void emitFromBox(Object caster, int type, boolean isStatic,
                                double interpX, double interpY, double interpZ,
                                AABB box, float scale, long staticHash)
        {
            // AABB edge lengths via stable public fields (yarn renamed getXLength()
            // -> getLengthX() after 1.20.1; fields are identical across versions).
            double ex = box.maxX - box.minX, ey = box.maxY - box.minY, ez = box.maxZ - box.minZ;
            float rad = (float) (0.5 * Math.sqrt(ex * ex + ey * ey + ez * ez) * scale) + OVERLAP_MARGIN;
            float rh = (float) (0.5 * Math.sqrt(ex * ex + ez * ez) * scale);
            float hv = (float) (0.5 * ey * scale);
            put(caster, type, isStatic, (float) interpX, (float) (interpY + ey * 0.5), (float) interpZ, rad, rh, hv, staticHash);
        }

        @Override
        public void emit(Object caster, int type, boolean isStatic,
                         float cx, float cy, float cz, float radius, long staticHash)
        {
            // No box to tighten from: per-axis half-extents fall back to the
            // sphere radius (the dyn-rect AABB becomes the sphere box + pose
            // slack). The slack MUST apply here too: model-block spheres are
            // built from the FORM HITBOX, and a stretched/oversized visual
            // draws far outside them — the knob is the only guard.
            put(caster, type, isStatic, cx, cy, cz, radius, radius, radius, staticHash);
        }
    };

    /** Insert one occluder into the bounded SoA — appends while there is room;
     *  when full, keeps the nearest {@link #MAX_OCCLUDERS} by camera distance: the current farthest
     *  kept entry (argmax {@link #odist2}) is replaced iff the newcomer is
     *  nearer, else the newcomer is dropped. A heap maintains the argmax after
     *  replacements without moving the retained SoA slots or changing ties. */
    private static void put(Object caster, int type, boolean isStatic,
                            float cx, float cy, float cz, float radius,
                            float rh, float hv, long staticHash)
    {
        float ddx = cx - occCamX, ddy = cy - occCamY, ddz = cz - occCamZ;
        float d2 = ddx * ddx + ddy * ddy + ddz * ddz;
        // A malformed source must degrade to an absent caster, never poison the
        // cached argmax (all comparisons against NaN are false) or cull math.
        if (!Float.isFinite(d2) || !Float.isFinite(radius) || radius < 0f)
        {
            return;
        }
        // Malformed per-axis extents (NaN / negative) degrade the same way the
        // whole caster would: to the guaranteed-conservative sphere box.
        if (!(rh >= 0f) || !(hv >= 0f))
        {
            rh = radius;
            hv = radius;
        }
        int idx;
        boolean replacedFarthest = false;
        if (occCount < MAX_OCCLUDERS)
        {
            idx = occCount++;
        }
        else
        {
            // Pool full: either branch makes some caster vanish from ALL lights
            // this frame — the rejected newcomer or the evicted farthest entry.
            if (ShadowClipTelemetry.ENABLED)
            {
                ShadowClipTelemetry.notePoolDrop();
            }
            if (d2 >= farthestOccDist2)
            {
                return;
            }
            idx = farthestOccIdx;
            replacedFarthest = true;
        }
        occ[idx] = caster;
        occType[idx] = type;
        oStatic[idx] = isStatic;
        ox[idx] = cx;
        oy[idx] = cy;
        oz[idx] = cz;
        orad[idx] = radius;
        orh[idx] = rh;
        ohv[idx] = hv;
        ostatichash[idx] = staticHash;
        odist2[idx] = d2;

        if (!replacedFarthest)
        {
            if (d2 > farthestOccDist2)
            {
                farthestOccIdx = idx;
                farthestOccDist2 = d2;
            }
            return;
        }

        // The heap compares distance, then smaller slot first, matching the
        // previous strict-'>' rescan exactly. Equal newcomers still reject above.
        farthestOccIdx = farthestOccHeap.afterReplace();
        farthestOccDist2 = odist2[farthestOccIdx];
    }

    private static void collect(ClientLevel world, Vec3 cameraPos, float tickDelta)
    {
        // Camera captured BEFORE the source emits: put() ranks every occluder
        // by squared camera distance for the bounded nearest-N policy.
        occCamX = (float) cameraPos.x;
        occCamY = (float) cameraPos.y;
        occCamZ = (float) cameraPos.z;
        occCount = 0;
        farthestOccIdx = 0;
        farthestOccDist2 = Float.NEGATIVE_INFINITY;
        farthestOccHeap.reset();
        Arrays.fill(spotMembersBySlot, null);
        ShadowEngine.source().collect(world, cameraPos, tickDelta, SINK);
        for (int k = 0; k < occCount; k++)
        {
            CasterRevision revision = CasterRevision.UNKNOWN;
            if (!oStatic[k] && !NO_OVERLAY_REUSE && ShadowEngine.config().shadowCache())
            {
                try
                {
                    revision = ShadowEngine.source().revision(occ[k], occType[k], tickDelta);
                }
                catch (RuntimeException | LinkageError unavailable)
                {
                    // Optional host capability failure changes cost, never correctness.
                }
            }
            oRevision[k] = revision == null ? CasterRevision.UNKNOWN : revision;
            if (!oStatic[k]) probeCount(oRevision[k].known() ? "caster.known" : "caster.unknown", 1);
        }
        Arrays.fill(occ, occCount, MAX_OCCLUDERS, null);
        Arrays.fill(oRevision, occCount, MAX_OCCLUDERS, null);
    }

    private static boolean staticBaseCurrent(long id, long sig, int tile,
                                             List<BlockShadowEntry> blocks, boolean hasStatic)
    {
        return !hasStatic || (lastStaticTile.containsKey(id) && lastStaticTile.get(id) == tile
            && lastStaticSig.get(id) == sig && lastStaticBlocks.get(id) == blocks);
    }

    /** Filter-only recreation can discard derived content while keeping depth. */
    public static void invalidateOverlays()
    {
        spotOverlays.clear();
        pointOverlays.clear();
    }

    private static long overlayPolicy(double x, double y, double z)
    {
        // The renderer subtracts the DOUBLE light anchor. Preserve sub-float
        // movement at distant world coordinates as well as live scissor settings.
        long h = mix64(Double.doubleToLongBits(x));
        h = mix64(h ^ Double.doubleToLongBits(y));
        h = mix64(h ^ Double.doubleToLongBits(z));
        h = mix64(h ^ Float.floatToIntBits(ShadowEngine.config().shadowPoseReach()));
        return mix64(h ^ (ShadowEngine.config().shadowPartialTile() ? 1L : 0L));
    }
}
