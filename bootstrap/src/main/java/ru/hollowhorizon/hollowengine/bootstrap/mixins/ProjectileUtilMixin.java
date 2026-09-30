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

@Mixin(ProjectileUtil.class)
public class ProjectileUtilMixin {
    @WrapMethod(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;")
    private static EntityHitResult hollowengine$pick(Entity source, Vec3 start, Vec3 end, AABB bounds,
            Predicate<Entity> predicate, double distance, Operation<EntityHitResult> original) {
        var bridge = BootstrapRuntimeManager.bridge();
        var vanilla = original.call(source, start, end, bounds,
                predicate.and(entity -> !bridge.hasModelHitboxes(entity)), distance);
        return bridge.pickModelHitboxes(source.level(), source, start, end, predicate, distance, vanilla);
    }

    @WrapMethod(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;")
    private static EntityHitResult hollowengine$projectile(Level level, Entity source, Vec3 start, Vec3 end,
            AABB bounds, Predicate<Entity> predicate, float margin, Operation<EntityHitResult> original) {
        var bridge = BootstrapRuntimeManager.bridge();
        var vanilla = original.call(level, source, start, end, bounds,
                predicate.and(entity -> !bridge.hasModelHitboxes(entity)), margin);
        // This overload returns the entity's origin, not the intersection. Compare surface distances.
        if (vanilla != null) {
            var location = vanilla.getEntity().getBoundingBox().inflate(margin).clip(start, end)
                    .orElse(vanilla.getLocation());
            vanilla = new EntityHitResult(vanilla.getEntity(), location);
        }
        return bridge.pickModelHitboxes(level, source, start, end, predicate, start.distanceToSqr(end), vanilla);
    }
}
