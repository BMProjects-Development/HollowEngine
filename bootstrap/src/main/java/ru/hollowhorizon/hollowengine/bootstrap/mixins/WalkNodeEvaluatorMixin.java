package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.hollowhorizon.hollowengine.bootstrap.impl.PathObstacleContext;

@Mixin(WalkNodeEvaluator.class)
public class WalkNodeEvaluatorMixin {
    @WrapOperation(
            method = "getPathTypeStatic(Lnet/minecraft/world/level/pathfinder/PathfindingContext;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/PathfindingContext;getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;", ordinal = 1)
    )
    private static PathType hollowengine$noFloorOnObstacles(PathfindingContext context, int x, int y, int z, Operation<PathType> original) {
        if (((PathObstacleContext) context).hollowengine$isObstacle(x, y, z)) return PathType.OPEN;
        return original.call(context, x, y, z);
    }
}
