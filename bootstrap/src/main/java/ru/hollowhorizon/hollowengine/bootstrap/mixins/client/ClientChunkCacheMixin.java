package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bridge.client.CameraChunkCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin implements CameraChunkCache {
    @Unique
    private static final int TILE_RADIUS = 4;
    @Unique
    private static final int TILE_WIDTH = TILE_RADIUS * 2 + 1;

    @Shadow
    volatile ClientChunkCache.Storage storage;
    @Shadow
    @Final
    ClientLevel level;
    @Unique
    private volatile Map<Long, ClientChunkCache.Storage> hollowengine$tiles;

    @Unique
    private static long hollowengine$tileKey(int x, int z) {
        return ChunkPos.asLong(Math.floorDiv(x, TILE_WIDTH), Math.floorDiv(z, TILE_WIDTH));
    }

    @Override
    public void hollowengine$enableCameraChunks() {
        if (hollowengine$tiles != null) return;
        var tiles = new ConcurrentHashMap<Long, ClientChunkCache.Storage>();
        var chunks = storage.chunks;
        for (int index = 0; index < chunks.length(); index++) {
            var chunk = chunks.get(index);
            if (chunk == null) continue;
            var pos = chunk.getPos();
            if (storage.inRange(pos.x, pos.z)) {
                var tile = tiles.computeIfAbsent(hollowengine$tileKey(pos.x, pos.z), this::hollowengine$newTile);
                tile.replace(tile.getIndex(pos.x, pos.z), chunk);
            }
        }
        hollowengine$tiles = tiles;
        for (int index = 0; index < chunks.length(); index++) {
            var chunk = chunks.getAndSet(index, null);
            if (chunk != null && !storage.inRange(chunk.getPos().x, chunk.getPos().z)) {
                level.unload(chunk);
            }
        }
        storage.chunkCount = 0;
    }

    @Override
    public List<ChunkPos> hollowengine$tiledChunks() {
        var tiles = hollowengine$tiles;
        if (tiles == null) return List.of();
        var positions = new ArrayList<ChunkPos>();
        for (var tile : tiles.values()) {
            for (int index = 0; index < tile.chunks.length(); index++) {
                var chunk = tile.chunks.get(index);
                if (chunk != null) positions.add(chunk.getPos());
            }
        }
        return positions;
    }

    @ModifyExpressionValue(method = "replaceWithPacketData", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;", opcode = Opcodes.GETFIELD))
    private ClientChunkCache.Storage hollowengine$receiveTile(ClientChunkCache.Storage original, int x, int z, FriendlyByteBuf buffer, CompoundTag tag, Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> tags) {
        var tiles = hollowengine$tiles;
        return tiles == null ? original : tiles.computeIfAbsent(hollowengine$tileKey(x, z), this::hollowengine$newTile);
    }

    @ModifyExpressionValue(method = "replaceBiomes", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;", opcode = Opcodes.GETFIELD))
    private ClientChunkCache.Storage hollowengine$biomeTile(ClientChunkCache.Storage original, int x, int z, FriendlyByteBuf buffer) {
        return hollowengine$findTile(original, x, z);
    }

    @ModifyExpressionValue(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;", opcode = Opcodes.GETFIELD))
    private ClientChunkCache.Storage hollowengine$readTile(ClientChunkCache.Storage original, int x, int z, ChunkStatus status, boolean requireChunk) {
        return hollowengine$findTile(original, x, z);
    }

    @ModifyExpressionValue(method = "drop", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;", opcode = Opcodes.GETFIELD))
    private ClientChunkCache.Storage hollowengine$dropTile(ClientChunkCache.Storage original, ChunkPos pos) {
        return hollowengine$findTile(original, pos.x, pos.z);
    }

    @Inject(method = "drop", at = @At("RETURN"))
    private void hollowengine$discardEmptyTile(ChunkPos pos, CallbackInfo ci) {
        var tiles = hollowengine$tiles;
        if (tiles != null) {
            tiles.computeIfPresent(hollowengine$tileKey(pos.x, pos.z), (key, tile) -> tile.chunkCount == 0 ? null : tile);
        }
    }

    @Inject(method = "getLoadedChunksCount", at = @At("HEAD"), cancellable = true)
    private void hollowengine$countTiles(CallbackInfoReturnable<Integer> cir) {
        var tiles = hollowengine$tiles;
        if (tiles != null) cir.setReturnValue(tiles.values().stream().mapToInt(tile -> tile.chunkCount).sum());
    }

    @Unique
    private ClientChunkCache.Storage hollowengine$findTile(ClientChunkCache.Storage original, int x, int z) {
        var tiles = hollowengine$tiles;
        return tiles == null ? original : tiles.getOrDefault(hollowengine$tileKey(x, z), original);
    }

    @Unique
    private ClientChunkCache.Storage hollowengine$newTile(long key) {
        var tile = ((ClientChunkCache) (Object) this).new Storage(TILE_RADIUS);
        tile.viewCenterX = ChunkPos.getX(key) * TILE_WIDTH + TILE_RADIUS;
        tile.viewCenterZ = ChunkPos.getZ(key) * TILE_WIDTH + TILE_RADIUS;
        return tile;
    }
}
