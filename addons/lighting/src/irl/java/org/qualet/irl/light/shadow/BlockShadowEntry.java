package org.qualet.irl.light.shadow;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

// cutout=true entries skip the AABB path; the textured BakedModel is baked
// with vanilla's alpha-test cutout shader so transparent texture pixels
// (door glass insets, iron grate gaps, ladder rungs) let light through.
public final class BlockShadowEntry
{
    public final BlockPos pos;
    public final VoxelShape shape;
    public final boolean cutout;

    public BlockShadowEntry(BlockPos pos, VoxelShape shape, boolean cutout)
    {
        this.pos = pos;
        this.shape = shape;
        this.cutout = cutout;
    }
}
