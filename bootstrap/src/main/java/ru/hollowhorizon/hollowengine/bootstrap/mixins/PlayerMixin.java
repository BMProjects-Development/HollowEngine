package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.hollowhorizon.hollowengine.api.extensions.PlayerExtension;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;

@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity implements PlayerExtension {
    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Shadow @Nullable public abstract ItemEntity drop(ItemStack droppedItem, boolean dropAround, boolean includeThrowerName);

    @Shadow protected abstract void doCloseContainer();

    @WrapOperation(method = "canFallAtLeast", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean hollowengine$canFallPastColliders(Level level, Entity entity, AABB box, Operation<Boolean> original) {
        return original.call(level, entity, box) && !BootstrapRuntimeManager.bridge().overlapsSolidColliders(entity, box);
    }

    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void onInteract(Entity entityToInteractOn, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (level().isClientSide) return;
        if (BootstrapRuntimeManager.bridge().onPlayerInteractEntity((Player) (Object) this, hand, entityToInteractOn)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }


    @WrapMethod(method = "attack")
    private void hollowengine$attack(Entity target, Operation<Void> original) {
        BootstrapRuntimeManager.bridge().onPlayerAttack((Player) (Object) this, target, () -> original.call(target));
    }

    @Override
    public void hollowcore$closeContainer() {
        doCloseContainer();
    }
}
