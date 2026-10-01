package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void hollowengine$tick(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onLivingEntityTick((LivingEntity) (Object) this);
    }

    // LivingEntity overrides Entity#hurt without calling super, so the Entity mixin never fires for
    // living entities. Post EntityEvent.Hurt here too so onHurt handlers run for mobs and players.
    @WrapMethod(method = "hurt")
    private boolean hollowengine$hurt(DamageSource damageSource, float amount, Operation<Boolean> original) {
        var bridge = BootstrapRuntimeManager.bridge();
        var entity = (LivingEntity) (Object) this;
        var source = bridge.resolveColliderDamage(entity, damageSource);
        float dealt = bridge.onLivingEntityHurt(entity, source, amount);
        if (Float.isNaN(dealt)) return false;
        return original.call(source, dealt);
    }

    @WrapMethod(method = "isPushable")
    private boolean hollowengine$isPushable(Operation<Boolean> original) {
        return BootstrapRuntimeManager.bridge().bodyPushable((LivingEntity) (Object) this, original.call());
    }

    @WrapMethod(method = "getDefaultDimensions")
    private EntityDimensions hollowengine$getDefaultDimensions(Pose pose, Operation<EntityDimensions> original) {
        return BootstrapRuntimeManager.bridge().bodyDimensions((LivingEntity) (Object) this, original.call(pose));
    }

    @WrapMethod(method = "pushEntities")
    private void hollowengine$pushEntities(Operation<Void> original) {
        if (BootstrapRuntimeManager.bridge().bodyPushesOthers((LivingEntity) (Object) this)) original.call();
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void hollowengine$die(DamageSource damageSource, CallbackInfo ci) {
        if (BootstrapRuntimeManager.bridge().onLivingEntityDeath((LivingEntity) (Object) this, damageSource)) {
            ci.cancel();
        }
    }
}
