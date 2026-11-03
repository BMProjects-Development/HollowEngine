package org.qualet.irl.light.shadow;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the bbox of a local light and gathers every block whose shape is
 * non-empty so its silhouette can be baked into per-light depth maps.
 *
 * Shape priority is cullingShape -> collisionShape -> outlineShape. For
 * fences / glass panes / iron bars cullingShape uses thin arms (radius
 * 1/16) so the post and arms decompose into distinct sub-AABBs with gaps
 * between them - exactly the lacy silhouette we want. collisionShape and
 * outlineShape use the same radius as the post, so on a connected block
 * they fuse into a single wide plank with no gaps.
 *
 * Fallbacks catch blocks where cullingShape is empty (signs, torches,
 * transparent decoratives), and finally outline for open trapdoors /
 * ladders / open doors where collision is also empty.
 *
 * The caller is responsible for clamping {@code radius} (the bake far-plane
 * may exceed the collection radius; blocks beyond it just cast no shadow).
 */
public final class BlockShadowCollector
{
    private BlockShadowCollector()
    {
    }

    public static List<BlockShadowEntry> collectForLight(ClientLevel world,
                                                         float lx, float ly, float lz,
                                                         float radius,
                                                         int hostX, int hostY, int hostZ)
    {
        List<BlockShadowEntry> out = new ArrayList<>();
        if (world == null || radius < 1e-3f)
        {
            return out;
        }

        int minX = (int) Math.floor(lx - radius);
        int minY = (int) Math.floor(ly - radius);
        int minZ = (int) Math.floor(lz - radius);
        int maxX = (int) Math.floor(lx + radius);
        int maxY = (int) Math.floor(ly + radius);
        int maxZ = (int) Math.floor(lz + radius);

        float r2 = radius * radius;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++)
        {
            float dx = (x + 0.5f) - lx;
            float dx2 = dx * dx;
            if (dx2 > r2) continue;

            for (int z = minZ; z <= maxZ; z++)
            {
                float dz = (z + 0.5f) - lz;
                float dxz2 = dx2 + dz * dz;
                if (dxz2 > r2) continue;

                for (int y = minY; y <= maxY; y++)
                {
                    float dy = (y + 0.5f) - ly;
                    if (dxz2 + dy * dy > r2) continue;

                    mut.set(x, y, z);
                    BlockState state = world.getBlockState(mut);
                    if (state.isAir()) continue;

                    // The block the light sits INSIDE (an emitting torch /
                    // glowstone / lantern / lava / ...) must not cast a shadow:
                    // the bulb is inside it, so its own silhouette would
                    // otherwise trap the light in its own depth map. Scoped to
                    // the host cell (floor of the light position) so emitters
                    // ELSEWHERE in range still cast shadows normally. Same idea
                    // as the INVISIBLE ModelBlock skip just below.
                    if (x == hostX && y == hostY && z == hostZ && state.getLightEmission() > 0) continue;

                    // RenderShape.INVISIBLE means the block draws nothing
                    // through the vanilla render path - ModelBlock, barrier,
                    // light_block, structure_void. ModelBlock in particular
                    // hosts the IRLight itself and would otherwise bake a
                    // phantom full-cube around the bulb (its outline falls
                    // back to Shapes.fullCube because Block has no
                    // override and collision is empty), trapping the light
                    // inside its own shadow map.
                    if (state.getRenderShape() == RenderShape.INVISIBLE) continue;

                    // Cutout blocks (doors with glass, iron bars, trapdoors,
                    // ladders, leaves) are baked from their textured BakedModel
                    // via vanilla's alpha-test cutout shader so transparent
                    // texture pixels let light through. Shape is unused for
                    // these - geometry comes from the model, not the AABB.
                    boolean cutout;
                    try
                    {
                        RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
                        cutout = layer == RenderType.cutout() || layer == RenderType.cutoutMipped();
                    }
                    catch (Throwable t)
                    {
                        cutout = false;
                    }
                    if (cutout)
                    {
                        out.add(new BlockShadowEntry(mut.immutable(), null, true));
                        continue;
                    }

                    VoxelShape shape;
                    try
                    {
                        shape = state.getOcclusionShape(world, mut);
                        if (shape == null || shape.isEmpty())
                        {
                            shape = state.getCollisionShape(world, mut);
                        }
                        if (shape == null || shape.isEmpty())
                        {
                            shape = state.getShape(world, mut);
                        }
                    }
                    catch (Throwable t)
                    {
                        continue;
                    }
                    if (shape == null || shape.isEmpty()) continue;

                    out.add(new BlockShadowEntry(mut.immutable(), shape, false));
                }
            }
        }

        return out;
    }

    /** How a state would enter a bake, mirroring collectForLight's decisions:
     *  NONE never emits geometry, CUTOUT bakes its textured BakedModel, SHAPE
     *  bakes its resolved VoxelShape. */
    private static final int KIND_NONE = 0;
    private static final int KIND_CUTOUT = 1;
    private static final int KIND_SHAPE = 2;

    /**
     * True when swapping {@code oldState} for {@code newState} at {@code pos}
     * provably leaves every lamp's baked silhouette unchanged, so the block
     * cache does NOT need to invalidate. Used by
     * {@link BlockShadowCache#invalidateChange} to ignore state churn that a
     * depth map can't see: grass->dirt, a furnace lighting up, fluid level
     * ticks (fluids are INVISIBLE render type), leaves' distance updates.
     *
     * The classification here must stay in lockstep with collectForLight
     * above; any new keep/skip rule there needs a matching input here. On any
     * doubt (cutout models that differ, resolution throwing) this returns
     * false — a wasted rebake is cheap, a stale shadow is a visible bug.
     */
    public static boolean sameSilhouette(BlockGetter world, BlockPos pos,
                                         BlockState oldState, BlockState newState)
    {
        try
        {
            VoxelShape[] shapeOut = new VoxelShape[2];
            int oldKind = casterKind(world, pos, oldState, shapeOut, 0);
            int newKind = casterKind(world, pos, newState, shapeOut, 1);
            if (oldKind == KIND_NONE && newKind == KIND_NONE)
            {
                return true;
            }
            if (oldKind != newKind)
            {
                return false;
            }
            // The host-cell emitter skip keys on luminance>0: a lamp living
            // inside this block drops it from its caster list only while it
            // emits, so a luminance flip (redstone lamp) changes that lamp's
            // list even with an identical shape. We can't see host cells from
            // here, so treat any flip as a real change.
            if ((oldState.getLightEmission() > 0) != (newState.getLightEmission() > 0))
            {
                return false;
            }
            if (oldKind == KIND_CUTOUT)
            {
                // Cutout geometry is the baked model. States sharing one baked
                // model instance (leaves distance=1 vs 2, fire ages) render
                // identically; distinct instances may differ — assume they do.
                BlockModelShaper models = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
                return models.getBlockModel(oldState) == models.getBlockModel(newState);
            }
            VoxelShape a = shapeOut[0], b = shapeOut[1];
            // Most blocks serve shapes from per-state static caches (full cubes
            // are one singleton), so identity catches the common case; fall
            // back to comparing the AABB decomposition the bake actually draws.
            return a == b || a.toAabbs().equals(b.toAabbs());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Classify one state the way collectForLight would treat it; for
     *  KIND_SHAPE the resolved shape is left in {@code shapeOut[slot]}. */
    private static int casterKind(BlockGetter world, BlockPos pos, BlockState state,
                                  VoxelShape[] shapeOut, int slot)
    {
        if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE)
        {
            return KIND_NONE;
        }
        try
        {
            RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
            if (layer == RenderType.cutout() || layer == RenderType.cutoutMipped())
            {
                return KIND_CUTOUT;
            }
        }
        catch (Throwable t)
        {
            // collectForLight treats a throwing layer lookup as non-cutout too
        }
        VoxelShape shape = state.getOcclusionShape(world, pos);
        if (shape == null || shape.isEmpty())
        {
            shape = state.getCollisionShape(world, pos);
        }
        if (shape == null || shape.isEmpty())
        {
            shape = state.getShape(world, pos);
        }
        if (shape == null || shape.isEmpty())
        {
            return KIND_NONE;
        }
        shapeOut[slot] = shape;
        return KIND_SHAPE;
    }
}
