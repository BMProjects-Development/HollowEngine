package ru.hollowhorizon.hollowengine.bridge.mixins;

import net.minecraft.core.Holder;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeMap;
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeState;

import java.util.Map;
import java.util.Set;

@Mixin(AttributeMap.class)
public abstract class AttributeMapMixin implements MutableAttributeMap {
    @Shadow @Final private AttributeSupplier supplier;
    @Shadow @Final private Map<Holder<Attribute>, AttributeInstance> attributes;
    @Shadow @Final private Set<AttributeInstance> attributesToSync;
    @Shadow @Final private Set<AttributeInstance> attributesToUpdate;
    @Shadow protected abstract void onAttributeModified(AttributeInstance instance);

    @Unique private MutableAttributeState hollowengine$attributeState;

    @Override
    public MutableAttributeState hollowengineMutableAttributes() {
        if (hollowengine$attributeState == null) {
            hollowengine$attributeState = new MutableAttributeState(
                    supplier, attributes, this::onAttributeModified, attributesToSync, attributesToUpdate);
        }
        return hollowengine$attributeState;
    }

    @Inject(method = "load", at = @At("HEAD"))
    private void hollowengine$restoreAdditionalAttributes(ListTag saved, CallbackInfo ci) {
        hollowengineMutableAttributes().registerSavedAttributes(saved);
    }

    @Inject(method = {"assignAllValues", "assignBaseValues"}, at = @At("HEAD"))
    private void hollowengine$copyAdditionalAttributes(AttributeMap source, CallbackInfo ci) {
        var state = hollowengineMutableAttributes();
        ((MutableAttributeMap) source).hollowengineMutableAttributes().additionalAttributes().forEach(state::getOrCreate);
    }
}
