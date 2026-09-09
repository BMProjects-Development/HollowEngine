@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.ui.entity

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import net.minecraft.locale.Language
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.attachments.editor.*

/**
 * What a field or a component is called, and how it wants to be edited.
 */
internal object ComponentLabels {
    fun componentKey(id: ResourceLocation): String =
        "hollowengine.component.${id.namespace}.${id.path.replace('/', '.')}"

    fun componentName(id: ResourceLocation, descriptor: SerialDescriptor): String {
        descriptor.annotations.filterIsInstance<EditorName>().firstOrNull()?.let { return translate(it.name) }
        return translateOrNull(componentKey(id)) ?: prettify(id.path.substringAfterLast('/'))
    }

    fun componentDescription(descriptor: SerialDescriptor): String? =
        descriptor.annotations.filterIsInstance<EditorDescription>().firstOrNull()?.let { translate(it.description) }

    fun componentIcon(descriptor: SerialDescriptor): String? =
        descriptor.annotations.filterIsInstance<EditorIcon>().firstOrNull()?.icon

    fun fieldName(owner: ResourceLocation?, descriptor: SerialDescriptor, index: Int): String {
        val annotations = descriptor.getElementAnnotations(index)
        annotations.filterIsInstance<EditorName>().firstOrNull()?.let { return translate(it.name) }
        val name = descriptor.getElementName(index)
        val key = owner?.let { "${componentKey(it)}.${name}" }
        return key?.let(::translateOrNull) ?: prettify(name)
    }

    fun fieldDescription(descriptor: SerialDescriptor, index: Int): String? =
        descriptor.getElementAnnotations(index).filterIsInstance<EditorDescription>().firstOrNull()
            ?.let { translate(it.description) }

    fun isHidden(descriptor: SerialDescriptor, index: Int): Boolean =
        descriptor.getElementAnnotations(index).any { it is EditorHidden }

    fun isHidden(descriptor: SerialDescriptor): Boolean = descriptor.annotations.any { it is EditorHidden }

    fun range(descriptor: SerialDescriptor, index: Int): FieldRange? =
        descriptor.getElementAnnotations(index).filterIsInstance<EditorRange>().firstOrNull()?.let {
            FieldRange(
                min = it.min.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY,
                max = it.max.toDoubleOrNull() ?: Double.POSITIVE_INFINITY,
                slider = it.slider,
            )
        }

    fun isBone(descriptor: SerialDescriptor, index: Int): Boolean =
        descriptor.getElementAnnotations(index).any { it is EditorBone }

    fun isMultiline(descriptor: SerialDescriptor, index: Int): Boolean =
        descriptor.getElementAnnotations(index).any { it is EditorMultiline }

    fun asset(descriptor: SerialDescriptor, index: Int): List<String> =
        descriptor.getElementAnnotations(index).filterIsInstance<EditorAsset>().flatMap { it.extensions.toList() }
            .distinct()

    fun translate(value: String): String = translateOrNull(value) ?: value

    private fun translateOrNull(key: String): String? =
        Language.getInstance().let { if (it.has(key)) it.getOrDefault(key) else null }

    fun prettify(name: String): String {
        val spaced = buildString {
            name.forEachIndexed { index, char ->
                if (char == '_' || char == '-') {
                    append(' ')
                    return@forEachIndexed
                }
                if (index > 0 && char.isUpperCase() && !name[index - 1].isUpperCase()) {
                    append(' ')
                    append(char.lowercaseChar())
                    return@forEachIndexed
                }
                append(char)
            }
        }
        return spaced.trim().replaceFirstChar { it.uppercase() }
    }
}
