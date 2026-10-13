package ru.hollowhorizon.hollowengine.common.attachments.editor

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeSupplier

/** Attribute names belong to the entity's supplier; the editor changes their base values only. */
internal object EntityAttributeEditing {
    fun read(attributes: AttributeMap): Map<String, Double> = BuiltInRegistries.ATTRIBUTE.holders()
        .filter { attributes.hasAttribute(it) }
        .map { it.key().location().toString() to attributes.getBaseValue(it) }
        .toList().sortedBy { it.first }.toMap()

    fun defaults(attributes: AttributeMap, supplier: AttributeSupplier?): Map<String, Double> {
        if (supplier == null) return emptyMap()
        return BuiltInRegistries.ATTRIBUTE.holders()
            .filter { attributes.hasAttribute(it) && supplier.hasAttribute(it) }
            .map { it.key().location().toString() to supplier.getBaseValue(it) }
            .toList().toMap()
    }

    fun apply(attributes: AttributeMap, values: Map<String, Double>) {
        values.forEach { (id, value) ->
            if (!value.isFinite()) return@forEach
            val location = ResourceLocation.tryParse(id) ?: return@forEach
            val holder = BuiltInRegistries.ATTRIBUTE.getHolder(location).orElse(null) ?: return@forEach
            val instance = attributes.getInstance(holder) ?: return@forEach
            instance.baseValue = holder.value().sanitizeValue(value)
        }
    }
}
