package ru.hollowhorizon.hollowengine.bridge.mixins.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeMap;

@Mixin(ClientPacketListener.class)
public abstract class ClientAttributeSyncMixin {
    @WrapOperation(method = "handleUpdateAttributes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;getInstance(Lnet/minecraft/core/Holder;)Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;"))
    private AttributeInstance hollowengine$receiveAdditionalAttribute(AttributeMap attributes, Holder<Attribute> attribute,
                                                                     Operation<AttributeInstance> original) {
        var instance = original.call(attributes, attribute);
        return instance != null ? instance : ((MutableAttributeMap) attributes).hollowengineMutableAttributes().getOrCreate(attribute);
    }
}
