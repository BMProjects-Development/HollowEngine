package ru.hollowhorizon.hollowengine.bridge.client;

import net.minecraft.world.level.ChunkPos;

import java.util.List;

public interface CameraChunkCache {
    void hollowengine$enableCameraChunks();
    List<ChunkPos> hollowengine$tiledChunks();
}
