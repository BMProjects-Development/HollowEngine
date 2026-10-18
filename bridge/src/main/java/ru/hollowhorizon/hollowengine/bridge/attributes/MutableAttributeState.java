package ru.hollowhorizon.hollowengine.bridge.attributes;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Mutates the actual vanilla map, retaining its dirty tracking, modifiers and NBT representation. */
public final class MutableAttributeState {
    private final AttributeSupplier supplier;
    private final Map<Holder<Attribute>, AttributeInstance> attributes;
    private final Consumer<AttributeInstance> modified;
    private final Set<AttributeInstance> toSync;
    private final Set<AttributeInstance> toUpdate;

    public MutableAttributeState(AttributeSupplier supplier, Map<Holder<Attribute>, AttributeInstance> attributes,
                                 Consumer<AttributeInstance> modified, Set<AttributeInstance> toSync,
                                 Set<AttributeInstance> toUpdate) {
        this.supplier = supplier;
        this.attributes = attributes;
        this.modified = modified;
        this.toSync = toSync;
        this.toUpdate = toUpdate;
    }

    public boolean hasAttribute(Holder<Attribute> attribute) {
        return attributes.containsKey(attribute) || supplier.hasAttribute(attribute);
    }

    public double defaultValue(Holder<Attribute> attribute) {
        return supplier.hasAttribute(attribute) ? supplier.getBaseValue(attribute) : attribute.value().getDefaultValue();
    }

    public double baseValue(Holder<Attribute> attribute) {
        var instance = attributes.get(attribute);
        return instance == null ? supplier.getBaseValue(attribute) : instance.getBaseValue();
    }

    public AttributeInstance getOrCreate(Holder<Attribute> attribute) {
        var existing = attributes.get(attribute);
        if (existing != null) return existing;
        var instance = supplier.createInstance(modified, attribute);
        if (instance == null) instance = new AttributeInstance(attribute, modified);
        attributes.put(attribute, instance);
        // Even an unchanged registry default must be sent when the attribute first appears.
        modified.accept(instance);
        return instance;
    }

    public Set<Holder<Attribute>> additionalAttributes() {
        var additional = new LinkedHashSet<>(attributes.keySet());
        additional.removeIf(supplier::hasAttribute);
        return additional;
    }

    /** Required attributes remain available; only per-entity additions can be removed. */
    public boolean remove(Holder<Attribute> attribute) {
        if (supplier.hasAttribute(attribute)) return false;
        var removed = attributes.remove(attribute);
        if (removed == null) return false;
        toSync.remove(removed);
        toUpdate.remove(removed);
        return true;
    }

    /** Prepares instances before vanilla restores their base values and permanent modifiers. */
    public void registerSavedAttributes(ListTag saved) {
        for (int i = 0; i < saved.size(); i++) {
            var location = ResourceLocation.tryParse(saved.getCompound(i).getString("id"));
            if (location != null) BuiltInRegistries.ATTRIBUTE.getHolder(location).ifPresent(this::getOrCreate);
        }
    }
}
