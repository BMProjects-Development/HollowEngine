package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(BlockItem.class)
public class BlockItemMixin {
    @WrapOperation(method = "canPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isUnobstructed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Z"))
    private boolean hollowengine$unobstructedByColliders(Level level, BlockState state, BlockPos pos, CollisionContext context,
                                                         Operation<Boolean> original) {
        if (!original.call(level, state, pos, context)) return false;
        return !BootstrapRuntimeManager.bridge().isObstructedByColliders(level, state.getCollisionShape(level, pos, context).move(pos.getX(), pos.getY(), pos.getZ()));
    }
}
