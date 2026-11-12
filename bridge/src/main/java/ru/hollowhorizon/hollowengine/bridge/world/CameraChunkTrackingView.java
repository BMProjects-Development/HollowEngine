package ru.hollowhorizon.hollowengine.bridge.world;

import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

public record CameraChunkTrackingView(Positioned player, List<Positioned> cameras) implements ChunkTrackingView {
    public CameraChunkTrackingView {
        cameras = List.copyOf(cameras);
    }

    public static void difference(ChunkTrackingView previous, ChunkTrackingView next, Consumer<ChunkPos> add, Consumer<ChunkPos> drop) {
        if (previous.equals(next)) return;
        previous.forEach(pos -> {
            if (!next.contains(pos)) drop.accept(pos);
        });
        next.forEach(pos -> {
            if (!previous.contains(pos)) add.accept(pos);
        });
    }

    @Override
    public boolean contains(int x, int z, boolean includeOuterChunks) {
        if (player.contains(x, z, includeOuterChunks)) return true;
        for (var camera : cameras) {
            if (camera.contains(x, z, includeOuterChunks)) return true;
        }
        return false;
    }

    @Override
    public void forEach(Consumer<ChunkPos> action) {
        player.forEach(action);
        var seen = new HashSet<ChunkPos>();
        for (var camera : cameras) {
            camera.forEach(pos -> {
                if (!player.contains(pos) && seen.add(pos)) action.accept(pos);
            });
        }
    }
}
