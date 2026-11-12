package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bridge.world.CameraChunkTracker;
import ru.hollowhorizon.hollowengine.bridge.world.CameraChunkTrackingView;

import java.util.*;

@Mixin(ChunkMap.class)
public abstract class CameraChunkMapMixin implements CameraChunkTracker {
    @Unique
    private static final TicketType<UUID> CAMERA_TICKET = TicketType.create("hollowengine_camera_preview", UUID::compareTo);

    @Unique
    private static final int SENDABLE_CHUNK_LEVEL = ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING);
    @Unique
    private final Map<ServerPlayer, List<ChunkTrackingView.Positioned>> hollowengine$cameras = new HashMap<>();
    @Shadow
    @Final
    private ServerLevel level;

    @Unique
    private static Set<ChunkPos> hollowengine$chunks(List<ChunkTrackingView.Positioned> views) {
        var chunks = new HashSet<ChunkPos>();
        for (var view : views) view.forEach(chunks::add);
        return chunks;
    }

    @Shadow
    public abstract DistanceManager getDistanceManager();

    @Shadow
    abstract int getPlayerViewDistance(ServerPlayer player);

    @Shadow
    private void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView view) {
    }

    @Shadow
    private void markChunkPendingToSend(ServerPlayer player, ChunkPos pos) {
    }

    @Override
    public void hollowengine$trackCameraChunks(ServerPlayer player, List<ChunkTrackingView.Positioned> cameras) {
        if (player.level() != level || player.hasDisconnected()) return;
        if (hollowengine$setCameras(player, cameras)) {
            applyChunkTrackingView(player, ChunkTrackingView.of(player.chunkPosition(), getPlayerViewDistance(player)));
        }
    }

    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void hollowengine$releaseCameraTickets(ServerPlayer player, boolean track, CallbackInfo ci) {
        if (!track) hollowengine$setCameras(player, List.of());
    }

    @WrapMethod(method = "applyChunkTrackingView")
    private void hollowengine$mergeTrackingViews(ServerPlayer player, ChunkTrackingView next, Operation<Void> original) {
        var previous = player.getChunkTrackingView();
        var cameras = hollowengine$cameras.get(player);
        if (cameras == null && !(previous instanceof CameraChunkTrackingView)) {
            original.call(player, next);
            return;
        }
        if (player.level() != level) return;

        if (next instanceof ChunkTrackingView.Positioned playerView) {
            if (previous instanceof CameraChunkTrackingView(ChunkTrackingView.Positioned player1, List<ChunkTrackingView.Positioned> cameras1)) {
                if (player1.equals(playerView) && cameras1.equals(cameras)) return;
            }
            var oldPlayerView = previous instanceof CameraChunkTrackingView combined ? combined.player() : previous;
            if (!(oldPlayerView instanceof ChunkTrackingView.Positioned old) || !old.center().equals(playerView.center())) {
                var center = playerView.center();
                player.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
            }
            if (cameras != null) next = new CameraChunkTrackingView(playerView, cameras);
        }
        CameraChunkTrackingView.difference(previous, next, pos -> markChunkPendingToSend(player, pos), pos -> player.connection.chunkSender.dropChunk(player, pos));
        player.setChunkTrackingView(next);
    }

    @Unique
    private boolean hollowengine$setCameras(ServerPlayer player, List<ChunkTrackingView.Positioned> cameras) {
        var previous = hollowengine$cameras.getOrDefault(player, List.of());
        if (previous.equals(cameras)) return false;
        var before = hollowengine$chunks(previous);
        var after = hollowengine$chunks(cameras);
        var distanceManager = getDistanceManager();
        for (var pos : after) {
            if (!before.contains(pos))
                distanceManager.addTicket(CAMERA_TICKET, pos, SENDABLE_CHUNK_LEVEL, player.getUUID());
        }
        for (var pos : before) {
            if (!after.contains(pos))
                distanceManager.removeTicket(CAMERA_TICKET, pos, SENDABLE_CHUNK_LEVEL, player.getUUID());
        }
        if (cameras.isEmpty()) hollowengine$cameras.remove(player);
        else hollowengine$cameras.put(player, List.copyOf(cameras));
        return true;
    }
}
