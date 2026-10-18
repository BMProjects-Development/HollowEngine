package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(Projectile.class)
public class ProjectileMixin {
    @WrapMethod(method = "onHit")
    private void hollowengine$onHit(HitResult result, Operation<Void> original) {
        BootstrapRuntimeManager.bridge().onProjectileHit((Projectile) (Object) this, result, () -> original.call(result));
    }
}
