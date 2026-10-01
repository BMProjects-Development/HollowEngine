package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

/**
 * The body component of any entity: whether it is pushed, whether it is solid, how big it is. Living
 * entities override some of these, so {@link LivingEntityMixin} wraps their versions as well.
 */
@Mixin(Entity.class)
public class EntityBodyMixin {
    @WrapMethod(method = "isPushable")
    private boolean hollowengine$isPushable(Operation<Boolean> original) {
        return BootstrapRuntimeManager.bridge().bodyPushable((Entity) (Object) this, original.call());
    }

    @WrapMethod(method = "canBeCollidedWith")
    private boolean hollowengine$canBeCollidedWith(Operation<Boolean> original) {
        return BootstrapRuntimeManager.bridge().bodySolid((Entity) (Object) this, original.call());
    }

    @WrapMethod(method = "getDimensions")
    private EntityDimensions hollowengine$getDimensions(Pose pose, Operation<EntityDimensions> original) {
        return BootstrapRuntimeManager.bridge().bodyDimensions((Entity) (Object) this, original.call(pose));
    }
}
