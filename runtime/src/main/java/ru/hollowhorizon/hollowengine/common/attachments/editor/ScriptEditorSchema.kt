@file:OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)

package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.Json

@Serializable
data class ScriptEditorInfo(
    val path: String,
    val name: String = "",
    val description: String = "",
    val icon: String = "",
    val fields: List<ScriptField> = emptyList(),
    val values: String = "{}",
)

@Serializable
data class ScriptField(
    val name: String,
    val type: ScriptType,
    val label: String = "",
    val description: String = "",
    val icon: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val slider: Boolean = false,
    val multiline: Boolean = false,
    val assets: List<String> = emptyList(),
    val hidden: Boolean = false,
)

@Serializable
data class ScriptType(
    val kind: ScriptTypeKind,
    val serialName: String = "",
    val nullable: Boolean = false,
    val elements: List<ScriptField> = emptyList(),
)

enum class ScriptTypeKind {
    BOOLEAN, CHAR, STRING, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, ENUM, LIST, MAP, CLASS, OBJECT, UNSUPPORTED
}

val ScriptEditorJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

object ScriptSchema {
    private const val MaxDepth = 8

    fun typeOf(descriptor: SerialDescriptor, depth: Int = 0): ScriptType {
        val inner = descriptor.nonNullOriginal
        val nullable = descriptor.isNullable
        if (depth > MaxDepth) return ScriptType(ScriptTypeKind.UNSUPPORTED, inner.serialName, nullable)

        val kind = when (inner.kind) {
            PrimitiveKind.BOOLEAN -> ScriptTypeKind.BOOLEAN
            PrimitiveKind.CHAR -> ScriptTypeKind.CHAR
            PrimitiveKind.STRING -> ScriptTypeKind.STRING
            PrimitiveKind.BYTE -> ScriptTypeKind.BYTE
            PrimitiveKind.SHORT -> ScriptTypeKind.SHORT
            PrimitiveKind.INT -> ScriptTypeKind.INT
            PrimitiveKind.LONG -> ScriptTypeKind.LONG
            PrimitiveKind.FLOAT -> ScriptTypeKind.FLOAT
            PrimitiveKind.DOUBLE -> ScriptTypeKind.DOUBLE
            SerialKind.ENUM -> ScriptTypeKind.ENUM
            StructureKind.LIST -> ScriptTypeKind.LIST
            StructureKind.MAP -> ScriptTypeKind.MAP
            StructureKind.CLASS -> ScriptTypeKind.CLASS
            StructureKind.OBJECT -> ScriptTypeKind.OBJECT
            else -> ScriptTypeKind.UNSUPPORTED
        }

        val elements = when (kind) {
            ScriptTypeKind.ENUM -> inner.elementNames.map { entry ->
                ScriptField(entry, ScriptType(ScriptTypeKind.OBJECT, "${inner.serialName}.$entry"))
            }

            ScriptTypeKind.LIST, ScriptTypeKind.MAP, ScriptTypeKind.CLASS -> (0 until inner.elementsCount).map { index ->
                fieldOf(
                    inner,
                    index,
                    depth + 1
                )
            }

            else -> emptyList()
        }

        return ScriptType(kind, inner.serialName, nullable, elements)
    }

    fun fieldOf(owner: SerialDescriptor, index: Int, depth: Int): ScriptField {
        val annotations = owner.getElementAnnotations(index)
        val range = annotations.filterIsInstance<EditorRange>().firstOrNull()
        return ScriptField(
            name = owner.getElementName(index),
            type = typeOf(owner.getElementDescriptor(index), depth),
            label = annotations.filterIsInstance<EditorName>().firstOrNull()?.name.orEmpty(),
            description = annotations.filterIsInstance<EditorDescription>().firstOrNull()?.description.orEmpty(),
            icon = annotations.filterIsInstance<EditorIcon>().firstOrNull()?.icon.orEmpty(),
            min = range?.min?.takeIf(Double::isFinite),
            max = range?.max?.takeIf(Double::isFinite),
            slider = range?.slider ?: false,
            multiline = annotations.any { it is EditorMultiline },
            assets = annotations.filterIsInstance<EditorAsset>().flatMap { it.extensions.toList() }.distinct(),
            hidden = annotations.any { it is EditorHidden },
        )
    }
}

fun List<ScriptField>.toDescriptor(serialName: String): SerialDescriptor = buildClassSerialDescriptor(serialName) {
    forEach { field -> element(field.name, field.type.toDescriptor(), field.annotations()) }
}

fun ScriptType.toDescriptor(): SerialDescriptor {
    val base = when (kind) {
        ScriptTypeKind.BOOLEAN -> Boolean.serializer().descriptor
        ScriptTypeKind.CHAR -> Char.serializer().descriptor
        ScriptTypeKind.STRING -> String.serializer().descriptor
        ScriptTypeKind.BYTE -> Byte.serializer().descriptor
        ScriptTypeKind.SHORT -> Short.serializer().descriptor
        ScriptTypeKind.INT -> Int.serializer().descriptor
        ScriptTypeKind.LONG -> Long.serializer().descriptor
        ScriptTypeKind.FLOAT -> Float.serializer().descriptor
        ScriptTypeKind.DOUBLE -> Double.serializer().descriptor

        ScriptTypeKind.ENUM -> buildSerialDescriptor(name(), SerialKind.ENUM) {
            elements.forEach { entry ->
                element(entry.name, buildSerialDescriptor("${name()}.${entry.name}", StructureKind.OBJECT))
            }
        }

        ScriptTypeKind.LIST -> listSerialDescriptor(elementDescriptor(0))
        ScriptTypeKind.MAP -> mapSerialDescriptor(elementDescriptor(0), elementDescriptor(1))
        ScriptTypeKind.CLASS -> elements.toDescriptor(name())
        ScriptTypeKind.OBJECT -> buildSerialDescriptor(name(), StructureKind.OBJECT)
        ScriptTypeKind.UNSUPPORTED -> buildSerialDescriptor(name(), SerialKind.CONTEXTUAL)
    }
    return if (nullable) base.nullable else base
}

private fun ScriptType.elementDescriptor(index: Int): SerialDescriptor =
    elements.getOrNull(index)?.type?.toDescriptor() ?: String.serializer().descriptor

private fun ScriptType.name(): String = serialName.ifBlank { "hollowengine.script.${kind.name.lowercase()}" }

private fun ScriptField.annotations(): List<Annotation> = buildList {
    if (label.isNotBlank()) add(EditorName(label))
    if (description.isNotBlank()) add(EditorDescription(description))
    if (icon.isNotBlank()) add(EditorIcon(icon))
    if (min != null || max != null || slider) {
        add(EditorRange(min ?: Double.NEGATIVE_INFINITY, max ?: Double.POSITIVE_INFINITY, slider))
    }
    if (multiline) add(EditorMultiline())
    if (assets.isNotEmpty()) add(EditorAsset(*assets.toTypedArray()))
    if (hidden) add(EditorHidden())
}
