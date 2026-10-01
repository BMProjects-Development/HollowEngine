package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

import java.util.List;

/**
 * The body component of any entity: whether it is pushed, whether it is solid, how big it is, and the
 * solid colliders it moves among. Living entities override some of these, so {@link LivingEntityMixin}
 * wraps their versions as well.
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

    @WrapMethod(method = "collide")
    private Vec3 hollowengine$collide(Vec3 movement, Operation<Vec3> original) {
        return BootstrapRuntimeManager.bridge().collideWithColliders((Entity) (Object) this, movement, () -> original.call(movement));
    }

    @WrapMethod(method = "collideWithShapes")
    private static Vec3 hollowengine$collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes, Operation<Vec3> original) {
        return BootstrapRuntimeManager.bridge().collideShapesWithColliders(movement, box, shapes, () -> original.call(movement, box, shapes));
    }

    @WrapMethod(method = "collectCandidateStepUpHeights")
    private static float[] hollowengine$stepHeights(AABB box, List<VoxelShape> colliders, float limit, float current,
                                                   Operation<float[]> original) {
        return BootstrapRuntimeManager.bridge().stepHeightsWithColliders(box, limit, original.call(box, colliders, limit, current));
    }

    @WrapMethod(method = "getDimensions")
    private EntityDimensions hollowengine$getDimensions(Pose pose, Operation<EntityDimensions> original) {
        return BootstrapRuntimeManager.bridge().bodyDimensions((Entity) (Object) this, original.call(pose));
    }
}
