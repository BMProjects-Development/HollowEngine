package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(Player.class)
public class ModelHitboxPlayerMixin {
    @WrapOperation(method = "canInteractWithEntity(Lnet/minecraft/world/entity/Entity;D)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private AABB hollowengine$reach(Entity entity, Operation<AABB> original) {
        var bridge = BootstrapRuntimeManager.bridge();
        return bridge.hasModelHitboxes(entity) ? bridge.modelHitboxBounds(entity) : original.call(entity);
    }

    @WrapOperation(method = "attack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean hollowengine$sweep(LivingEntity target, DamageSource source, float amount, Operation<Boolean> original) {
        return BootstrapRuntimeManager.bridge().withModelHitboxAreaDamage(() -> original.call(target, source, amount));
    }
}
