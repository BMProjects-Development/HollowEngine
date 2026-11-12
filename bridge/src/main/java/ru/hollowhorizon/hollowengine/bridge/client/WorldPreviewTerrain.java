package ru.hollowhorizon.hollowengine.bridge.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns vanilla meshes around a camera when terrain rendering is not delegated to Sodium.
 */
public final class WorldPreviewTerrain implements AutoCloseable {
    private final ClientLevel level;
    private final SectionRenderDispatcher dispatcher;
    private final int radius;
    private final Long2ObjectOpenHashMap<SectionRenderDispatcher.RenderSection[]> columns = new Long2ObjectOpenHashMap<>();
    private final Set<BlockEntity> globalBlockEntities = new HashSet<>();
    private volatile Vec3 cameraPosition = Vec3.ZERO;

    public WorldPreviewTerrain(ClientLevel level, SectionRenderDispatcher dispatcher, int radius) {
        this.level = level;
        this.dispatcher = dispatcher;
        this.radius = radius;
    }

    private static void release(SectionRenderDispatcher.RenderSection[] column) {
        for (var section : column) {
            if (section != null) section.releaseBuffers();
        }
    }

    public boolean uses(ClientLevel level, SectionRenderDispatcher dispatcher, int radius) {
        return this.level == level && this.dispatcher == dispatcher && this.radius == radius;
    }

    public Vec3 cameraPosition() {
        return cameraPosition;
    }

    public Set<BlockEntity> globalBlockEntities() {
        return globalBlockEntities;
    }

    public void prepare(Vec3 position, Frustum frustum, List<SectionRenderDispatcher.RenderSection> visible) {
        cameraPosition = position;
        int centerX = SectionPos.posToSectionCoord(position.x);
        int centerZ = SectionPos.posToSectionCoord(position.z);
        var iterator = columns.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int x = ChunkPos.getX(entry.getLongKey());
            int z = ChunkPos.getZ(entry.getLongKey());
            if (Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius && level.hasChunk(x, z)) continue;
            release(entry.getValue());
            iterator.remove();
        }

        visible.clear();
        globalBlockEntities.clear();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                var column = columns.computeIfAbsent(ChunkPos.asLong(x, z), key -> new SectionRenderDispatcher.RenderSection[level.getSectionsCount()]);
                for (int index = 0; index < column.length; index++) {
                    var section = column[index];
                    if (section == null) {
                        if (chunk.getSection(index).hasOnlyAir()) continue;
                        var origin = SectionPos.of(x, level.getSectionYFromSectionIndex(index), z).origin();
                        int bx = origin.getX();
                        int by = origin.getY();
                        int bz = origin.getZ();
                        if (!frustum.isVisible(new AABB(bx, by, bz, bx + 16, by + 16, bz + 16))) continue;
                        section = dispatcher.new RenderSection(-1, bx, by, bz);
                        ((PreviewRenderSection) section).hollowengine$setTerrain(this);
                        column[index] = section;
                    }
                    ((PreviewRenderSection) section).hollowengine$collectGlobalEntities(globalBlockEntities);
                    if (frustum.isVisible(section.getBoundingBox())) visible.add(section);
                }
            }
        }
    }

    public void setDirty(int x, int y, int z, boolean immediate) {
        var column = columns.get(ChunkPos.asLong(x, z));
        int index = level.getSectionIndexFromSectionY(y);
        if (column != null && index >= 0 && index < column.length && column[index] != null) {
            column[index].setDirty(immediate);
        }
    }

    @Override
    public void close() {
        columns.values().forEach(WorldPreviewTerrain::release);
        columns.clear();
        globalBlockEntities.clear();
    }
}
