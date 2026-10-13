package ru.hollowhorizon.hollowengine.common.attachments.editor

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeState

/** Edits registered per-entity attributes while retaining the type's required attributes. */
internal object EntityAttributeEditing {
    fun read(attributes: MutableAttributeState): Map<String, Double> = BuiltInRegistries.ATTRIBUTE.holders()
        .filter { attributes.hasAttribute(it) }
        .map { it.key().location().toString() to attributes.baseValue(it) }
        .toList().sortedBy { it.first }.toMap()

    fun defaults(attributes: MutableAttributeState): Map<String, Double> = BuiltInRegistries.ATTRIBUTE.holders()
        .filter { attributes.hasAttribute(it) }
        .map { it.key().location().toString() to attributes.defaultValue(it) }
        .toList().toMap()

    fun apply(attributes: MutableAttributeState, values: Map<String, Double>) {
        values.forEach { (id, value) ->
            if (!value.isFinite()) return@forEach
            val location = ResourceLocation.tryParse(id) ?: return@forEach
            val holder = BuiltInRegistries.ATTRIBUTE.getHolder(location).orElse(null) ?: return@forEach
            val instance = attributes.getOrCreate(holder)
            instance.baseValue = holder.value().sanitizeValue(value)
        }
    }

    fun replace(attributes: MutableAttributeState, values: Map<String, Double>): Set<String> {
        val requested = values.keys.mapNotNull(ResourceLocation::tryParse).toSet()
        val removed = attributes.additionalAttributes().filter { it.unwrapKey().orElseThrow().location() !in requested }
        removed.forEach(attributes::remove)
        apply(attributes, values)
        return removed.mapTo(linkedSetOf()) { it.unwrapKey().orElseThrow().location().toString() }
    }
}
