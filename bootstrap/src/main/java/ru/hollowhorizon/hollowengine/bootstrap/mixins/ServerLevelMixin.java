package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.api.extensions.EntitySectionManagerExtension;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

import java.util.EnumSet;

@Mixin(ServerLevel.class)
public class ServerLevelMixin {
    @Shadow
    @Final
    private PersistentEntitySectionManager<Entity> entityManager;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hollowengine$listenToChunkEntities(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        ((EntitySectionManagerExtension) entityManager).hollowengine$setChunkEntitiesLoadedListener(
                chunkPos -> BootstrapRuntimeManager.bridge().onChunkEntitiesLoaded(level, chunkPos)
        );
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void hollowengine$onSave(ProgressListener listener, boolean flush, boolean skipSave, CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onServerLevelSave((ServerLevel) (Object) this);
    }

    @Inject(method = "updateNeighborsAtExceptFromFacing", at = @At("HEAD"), cancellable = true)
    private void hollowengine$onUpdateNeighbors(BlockPos pos, Block blockType, Direction skipSide, CallbackInfo ci) {
        EnumSet<Direction> sides = EnumSet.allOf(Direction.class);
        sides.remove(skipSide);
        if (BootstrapRuntimeManager.bridge().onServerLevelNeighborNotify((ServerLevel) (Object) this, pos, sides)) {
            ci.cancel();
        }
    }
}
