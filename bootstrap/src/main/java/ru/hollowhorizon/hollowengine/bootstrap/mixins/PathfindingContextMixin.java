package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bootstrap.impl.PathObstacleContext;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.PathObstacles;

/**
 * Every path search of every mob reads blocks through its context: cells filled by solid colliders read as
 * blocked there, after the level's shared cache, which only knows blocks.
 */
@Mixin(PathfindingContext.class)
public class PathfindingContextMixin implements PathObstacleContext {
    @Unique
    @Nullable
    private PathObstacles hollowengine$obstacles;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void hollowengine$findObstacles(CollisionGetter level, Mob mob, CallbackInfo ci) {
        hollowengine$obstacles = BootstrapRuntimeManager.bridge().pathObstacles(mob);
    }

    @Inject(method = "getPathTypeFromState", at = @At("HEAD"), cancellable = true)
    private void hollowengine$blockedByObstacles(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
        if (hollowengine$isObstacle(x, y, z)) cir.setReturnValue(PathType.BLOCKED);
    }

    @Override
    public boolean hollowengine$isObstacle(int x, int y, int z) {
        return hollowengine$obstacles != null && hollowengine$obstacles.blocks(x, y, z);
    }
}
