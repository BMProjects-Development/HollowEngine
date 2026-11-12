package ru.hollowhorizon.hollowengine.bridge.world;

import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public interface CameraChunkTracker {
    void hollowengine$trackCameraChunks(ServerPlayer player, List<ChunkTrackingView.Positioned> cameras);
}
