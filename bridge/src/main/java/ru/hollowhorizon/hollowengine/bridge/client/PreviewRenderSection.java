package ru.hollowhorizon.hollowengine.bridge.client;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Set;

public interface PreviewRenderSection {
    void hollowengine$setTerrain(WorldPreviewTerrain terrain);
    void hollowengine$collectGlobalEntities(Set<BlockEntity> target);
}
