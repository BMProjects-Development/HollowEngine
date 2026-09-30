package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @WrapMethod(method = "hurt")
    private boolean hollowengine$hitboxDamage(DamageSource source, float amount, Operation<Boolean> original) {
        float adjusted = BootstrapRuntimeManager.bridge().modelHitboxDamage((LivingEntity) (Object) this, source, amount);
        return adjusted >= 0f && original.call(source, adjusted);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void hollowengine$tick(CallbackInfo ci) {
        BootstrapRuntimeManager.bridge().onLivingEntityTick((LivingEntity) (Object) this);
    }

    // LivingEntity overrides Entity#hurt without calling super, so the Entity mixin never fires for
    // living entities. Post EntityEvent.Hurt here too so onHurt handlers run for mobs and players.
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void hollowengine$hurt(DamageSource damageSource, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (BootstrapRuntimeManager.bridge().onEntityHurt((LivingEntity) (Object) this, damageSource, amount)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void hollowengine$die(DamageSource damageSource, CallbackInfo ci) {
        if (BootstrapRuntimeManager.bridge().onLivingEntityDeath((LivingEntity) (Object) this, damageSource)) {
            ci.cancel();
        }
    }
}
