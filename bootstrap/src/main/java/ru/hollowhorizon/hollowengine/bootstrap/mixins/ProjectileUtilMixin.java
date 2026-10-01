package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

import java.util.function.Predicate;

/**
 * Entities with colliders are met by their colliders, not by their box: vanilla searches without them
 * and the colliders along the segment compete with what it found.
 */
@Mixin(ProjectileUtil.class)
public class ProjectileUtilMixin {
    @WrapMethod(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;")
    private static EntityHitResult hollowengine$pick(Entity shooter, Vec3 start, Vec3 end, AABB search,
                                                     Predicate<Entity> filter, double distance, Operation<EntityHitResult> original) {
        var bridge = BootstrapRuntimeManager.bridge();
        var vanilla = original.call(shooter, start, end, search, filter.and(entity -> !bridge.hasColliderTargets(entity, false)), distance);
        return bridge.pickColliders(shooter.level(), shooter, start, end, search, filter, distance, vanilla, false);
    }

    @WrapMethod(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;")
    private static EntityHitResult hollowengine$projectile(Level level, Entity projectile, Vec3 start, Vec3 end, AABB search,
                                                           Predicate<Entity> filter, float margin, Operation<EntityHitResult> original) {
        var bridge = BootstrapRuntimeManager.bridge();
        var vanilla = original.call(level, projectile, start, end, search, filter.and(entity -> !bridge.hasColliderTargets(entity, true)), margin);
        return bridge.pickColliders(level, projectile, start, end, search, filter, start.distanceToSqr(end), vanilla, true);
    }
}
