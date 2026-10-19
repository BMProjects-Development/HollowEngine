package ru.hollowhorizon.hollowengine.bootstrap.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.api.extensions.EntitySectionManagerExtension;

import java.util.function.LongConsumer;

@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin implements EntitySectionManagerExtension {
    @Unique
    @Nullable
    private LongConsumer hollowengine$chunkEntitiesLoaded;

    @Override
    public void hollowengine$setChunkEntitiesLoadedListener(LongConsumer listener) {
        hollowengine$chunkEntitiesLoaded = listener;
    }

    @Inject(
            method = "processPendingLoads",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;",
                    shift = At.Shift.AFTER
            )
    )
    private void hollowengine$onChunkEntitiesLoaded(CallbackInfo ci, @Local ChunkEntities<?> chunkEntities) {
        if (hollowengine$chunkEntitiesLoaded != null) {
            hollowengine$chunkEntitiesLoaded.accept(chunkEntities.getPos().toLong());
        }
    }
}
