@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextFieldMode
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The generic component editor. One composable per serial kind, recursing the way the descriptor does.
 */
@Composable
internal fun ComponentFields(
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    value: JsonObject,
    path: String,
    query: String = "",
    onChange: (JsonObject) -> Unit,
) {
    for (index in 0 until descriptor.elementsCount) {
        if (ComponentLabels.isHidden(descriptor, index)) continue

        val name = descriptor.getElementName(index)
        val label = ComponentLabels.fieldName(owner, descriptor, index)
        if (!matchesQuery(query, label, name)) continue

        val element = descriptor.getElementDescriptor(index)
        val current = value[name] ?: ComponentJson.defaultJson(element)

        key(name) {
            ValueEditor(
                label = label,
                description = ComponentLabels.fieldDescription(descriptor, index),
                owner = owner,
                descriptor = element,
                hints = FieldHints(
                    range = ComponentLabels.range(descriptor, index),
                    multiline = ComponentLabels.isMultiline(descriptor, index),
                    asset = ComponentLabels.asset(descriptor, index),
                    bone = ComponentLabels.isBone(descriptor, index),
                ),
                value = current,
                path = "$path/$name",
                onChange = { onChange(value.withField(name, it)) },
            )
        }
    }
}

@Composable
internal fun FieldLabel(label: String?, description: String?) {
    if (label == null) return FieldHelp(description)

    Row(tags = listOf("ee-field-head")) {
        Text(label, tags = listOf("ee-label"), modifier = Modifier.grow(1f))
        FieldHelp(description)
    }
}

@Composable
internal fun FieldHelp(description: String?) {
    val text = description ?: return

    Image(
        HelpIcon,
        tags = listOf("ee-help"),
        modifier = Modifier.size(9.px, 9.px).input(hoverable = true).tooltipOnHover(text),
    )
}

private const val HelpIcon = "hollowengine:textures/gui/icons/docs.svg"

internal data class FieldRange(val min: Double, val max: Double, val slider: Boolean)

internal data class FieldHints(
    val range: FieldRange? = null,
    val multiline: Boolean = false,
    val asset: List<String> = emptyList(),
    val bone: Boolean = false,
)

private fun matchesQuery(query: String, vararg candidates: String): Boolean {
    if (query.isBlank()) return true
    return candidates.any { it.contains(query, ignoreCase = true) }
}

@Composable
internal fun ValueEditor(
    label: String?,
    description: String?,
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    hints: FieldHints,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    if (descriptor.isNullable) {
        NullableEditor(label, description, owner, descriptor, hints, value, path, onChange)
        return
    }

    when (val kind = descriptor.kind) {
        PrimitiveKind.BOOLEAN -> BooleanField(label, description, value, onChange)

        PrimitiveKind.STRING, PrimitiveKind.CHAR -> StringField(label, description, hints, value, path, onChange)

        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
            -> NumberField(label, description, kind as PrimitiveKind, hints, value, path, onChange)

        SerialKind.ENUM -> EnumField(label, description, descriptor, value, onChange)

        StructureKind.LIST -> ListField(label, owner, descriptor, hints, value, path, onChange)

        StructureKind.MAP -> MapField(label, owner, descriptor, value, path, onChange)

        StructureKind.CLASS, StructureKind.OBJECT -> {
            if (isCompactVector(descriptor)) VectorField(label, description, descriptor, value, path, onChange)
            else NestedField(label, owner, descriptor, value, path, onChange)
        }

        PolymorphicKind.SEALED, PolymorphicKind.OPEN -> PolymorphicField(
            label, owner, descriptor, value, path, onChange
        )

        else -> UnsupportedField(label, descriptor)
    }
}

@Composable
private fun NullableEditor(
    label: String?,
    description: String?,
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    hints: FieldHints,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val present = value !is JsonNull
    val inner = descriptor.nonNullOriginal

    Column(tags = listOf("ee-field")) {
        Row(tags = listOf("ee-field-head")) {
            if (label != null) Text(label, tags = listOf("ee-label"), modifier = Modifier.grow(1f))
            FieldHelp(description)
            Checkbox(
                checked = present,
                onCheckedChange = { checked ->
                    onChange(if (checked) ComponentJson.defaultJson(inner) else JsonNull)
                },
                tags = listOf("ee-checkbox"),
            )
        }
        if (present) {
            ValueEditor(null, description, owner, inner, hints, value, path, onChange)
        } else {
            Text(EntityEditorLang.notSet, tags = listOf("ee-hint"))
        }
    }
}

@Composable
private fun BooleanField(
    label: String?,
    description: String?,
    value: JsonElement,
    onChange: (JsonElement) -> Unit,
) {
    val checked = (value as? JsonPrimitive)?.booleanOrNull ?: false
    Column(tags = listOf("ee-field")) {
        Row(
            tags = listOf("ee-check-row"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                .onClick { onChange(JsonPrimitive(!checked)) },
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onChange(JsonPrimitive(it)) },
                tags = listOf("ee-checkbox"),
            )
            if (label != null) Text(label, tags = listOf("ee-check-label"), modifier = Modifier.grow(1f))
            FieldHelp(description)
        }
    }
}

@Composable
private fun StringField(
    label: String?,
    description: String?,
    hints: FieldHints,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (hints.bone) return BoneField(label, description, text) { onChange(JsonPrimitive(it)) }

    val session = LocalEntityEditorSession.current
    val candidates = if (hints.asset.isEmpty()) emptyList() else remember(hints.asset, session) {
        session?.assets(hints.asset).orEmpty()
    }

    Column(tags = listOf("ee-field")) {
        FieldLabel(label, description)
        Row(tags = listOf("ee-input-row")) {
            TextField(
                value = text,
                id = "ee-input$path",
                mode = if (hints.multiline) UiTextFieldMode.MULTI_LINE else UiTextFieldMode.SINGLE_LINE,
                fontSize = 9f,
                completionContributor = if (candidates.isEmpty()) null else assetCompletions(candidates),
                onChange = { typed ->
                    onChange(JsonPrimitive(if (hints.asset.isEmpty()) typed else sanitizeAssetPath(typed)))
                },
                tags = listOf("ee-input"),
                modifier = if (hints.multiline) Modifier.size(100.percent, 66.px) else Modifier.grow(1f),
            )
            hints.asset.takeIf { it.isNotEmpty() }?.let { extension ->
                AssetPickerButton(extension, text, label ?: extension.first()) { picked ->
                    onChange(JsonPrimitive(picked))
                }
            }
        }
    }
}


internal fun sanitizeAssetPath(text: String): String = buildString {
    text.forEach { char ->
        val folded = char.lowercaseChar()
        val accepted =
            folded in 'a'..'z' || folded in '0'..'9' || folded == '_' || folded == '.' || folded == '-' || folded == '/' || folded == ':'
        if (accepted) append(folded)
    }
}

@Composable
private fun NumberField(
    label: String?,
    description: String?,
    kind: PrimitiveKind,
    hints: FieldHints,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val number = (value as? JsonPrimitive)?.doubleOrNull ?: 0.0
    val whole =
        kind == PrimitiveKind.BYTE || kind == PrimitiveKind.SHORT || kind == PrimitiveKind.INT || kind == PrimitiveKind.LONG
    val range = hints.range

    Column(tags = listOf("ee-field")) {
        FieldLabel(label, description)
        if (range != null && range.slider && range.min.isFinite() && range.max.isFinite()) {
            Row(tags = listOf("ee-input-row")) {
                Slider(
                    value = number.toFloat(),
                    min = range.min.toFloat(),
                    max = range.max.toFloat(),
                    step = if (whole) 1f else 0f,
                    onValueChange = { onChange(numberJson(kind, it.toDouble())) },
                    modifier = Modifier.grow(1f),
                    tags = listOf("ee-slider"),
                )
                Text(formatNumber(number, whole), tags = listOf("ee-slider-value"))
            }
        } else {
            NumberInput(path, number, whole) { next ->
                val clamped = if (range == null) next else next.coerceIn(
                    range.min,
                    range.max,
                )
                onChange(numberJson(kind, clamped))
            }
        }
    }
}

@Composable
internal fun NumberInput(
    path: String,
    value: Double,
    whole: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit,
) {
    var draft by remember(path) { mutableStateOf(formatNumber(value, whole)) }
    var lastExternal by remember(path) { mutableStateOf(value) }
    if (value != lastExternal) {
        lastExternal = value
        if (draft.toDoubleOrNull() != value) draft = formatNumber(value, whole)
    }

    TextField(
        value = draft,
        id = "ee-number$path",
        filter = if (whole) UiTextInputFilter.INTEGER else UiTextInputFilter.DECIMAL,
        fontSize = 9f,
        onChange = { text ->
            draft = text
            text.toDoubleOrNull()?.let(onChange)
        },
        tags = listOf("ee-input"),
        modifier = modifier.grow(1f),
    )
}

@Composable
private fun EnumField(
    label: String?,
    description: String?,
    descriptor: SerialDescriptor,
    value: JsonElement,
    onChange: (JsonElement) -> Unit,
) {
    val current = (value as? JsonPrimitive)?.contentOrNull
    Column(tags = listOf("ee-field")) {
        FieldLabel(label, description)
        PillFlow {
            descriptor.elementNames.forEach { name ->
                EditorPill(ComponentLabels.prettify(name), name == current) { onChange(JsonPrimitive(name)) }
            }
        }
    }
}

@Composable
private fun VectorField(
    label: String?,
    description: String?,
    descriptor: SerialDescriptor,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val body = value as? JsonObject ?: JsonObject(emptyMap())
    Column(tags = listOf("ee-field")) {
        FieldLabel(label, description)
        Row(tags = listOf("ee-vector")) {
            for (index in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(index)
                val kind = descriptor.getElementDescriptor(index).kind as? PrimitiveKind ?: continue
                val whole = kind == PrimitiveKind.INT || kind == PrimitiveKind.LONG
                val number = (body[name] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                Column(tags = listOf("ee-vector-cell")) {
                    Text(name, tags = listOf("ee-vector-label"))
                    NumberInput("$path/$name", number, whole) { next ->
                        onChange(body.withField(name, numberJson(kind, next)))
                    }
                }
            }
        }
    }
}

@Composable
private fun NestedField(
    label: String?,
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    var expanded by remember(path) { mutableStateOf(true) }
    val body = value as? JsonObject ?: JsonObject(emptyMap())
    val title = label ?: ComponentLabels.prettify(descriptor.serialName.substringAfterLast('.'))

    Column(tags = listOf("ee-nested")) {
        Row(
            tags = listOf("ee-nested-head"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                .onClick { expanded = !expanded },
        ) {
            EditorArrow(expanded)
            Text(title, tags = listOf("ee-label"))
        }
        if (expanded) Column(tags = listOf("ee-nested-body")) {
            ComponentFields(owner, descriptor, body, path, onChange = onChange)
        }
    }
}

@Composable
private fun ListField(
    label: String?,
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    hints: FieldHints,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val items = value as? JsonArray ?: JsonArray(emptyList())
    val element = descriptor.getElementDescriptor(0)

    Column(tags = listOf("ee-nested")) {
        Row(tags = listOf("ee-nested-head")) {
            Text("${label.orEmpty()} (${items.size})", tags = listOf("ee-label"), modifier = Modifier.grow(1f))
            EditorIconButton(EntityEditorIcons.ADD, EntityEditorLang.add) {
                onChange(items.withItemAdded(ComponentJson.defaultJson(element)))
            }
        }
        Column(tags = listOf("ee-nested-body")) {
            items.forEachIndexed { index, item ->
                key(index) {
                    Row(tags = listOf("ee-list-item")) {
                        Column(modifier = Modifier.grow(1f).gap(4.px)) {
                            ValueEditor(
                                label = "#${index + 1}",
                                description = null,
                                owner = owner,
                                descriptor = element,
                                hints = hints,
                                value = item,
                                path = "$path[$index]",
                                onChange = { onChange(items.withItem(index, it)) },
                            )
                        }
                        EditorIconButton(EntityEditorIcons.REMOVE, EntityEditorLang.remove) {
                            onChange(items.withoutItem(index))
                        }
                    }
                }
            }
            if (items.isEmpty()) Text(EntityEditorLang.emptyList, tags = listOf("ee-hint"))
        }
    }
}

@Composable
private fun MapField(
    label: String?,
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val body = value as? JsonObject ?: JsonObject(emptyMap())
    val valueDescriptor = descriptor.getElementDescriptor(1)

    Column(tags = listOf("ee-nested")) {
        Row(tags = listOf("ee-nested-head")) {
            Text("${label.orEmpty()} (${body.size})", tags = listOf("ee-label"), modifier = Modifier.grow(1f))
            EditorIconButton(EntityEditorIcons.ADD, EntityEditorLang.add) {
                val name = generateSequence(1) { it + 1 }.first { "key$it" !in body }
                onChange(body.withField("key$name", ComponentJson.defaultJson(valueDescriptor)))
            }
        }
        Column(tags = listOf("ee-nested-body")) {
            body.forEach { (mapKey, mapValue) ->
                key(mapKey) {
                    Row(tags = listOf("ee-list-item")) {
                        Column(modifier = Modifier.grow(1f).gap(4.px)) {
                            TextField(
                                value = mapKey,
                                id = "ee-key$path/$mapKey",
                                fontSize = 9f,
                                onChange = { renamed -> onChange(body.withKeyRenamed(mapKey, renamed)) },
                                tags = listOf("ee-input"),
                                modifier = Modifier.size(100.percent, 22.px),
                            )
                            ValueEditor(
                                label = null,
                                description = null,
                                owner = owner,
                                descriptor = valueDescriptor,
                                hints = FieldHints(),
                                value = mapValue,
                                path = "$path/$mapKey",
                                onChange = { onChange(body.withField(mapKey, it)) },
                            )
                        }
                        EditorIconButton(EntityEditorIcons.REMOVE, EntityEditorLang.remove) {
                            onChange(body.withoutField(mapKey))
                        }
                    }
                }
            }
            if (body.isEmpty()) Text(EntityEditorLang.emptyList, tags = listOf("ee-hint"))
        }
    }
}

@Composable
private fun PolymorphicField(
    label: String?,
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val alternatives = ComponentJson.subclassDescriptors(descriptor)
    val body = value as? JsonObject ?: JsonObject(emptyMap())
    val current = ComponentJson.discriminatorOf(body)
    val selected = alternatives.firstOrNull { it.serialName == current } ?: alternatives.firstOrNull()

    Column(tags = listOf("ee-nested")) {
        FieldLabel(label, description = null)
        PillFlow {
            alternatives.forEach { alternative ->
                val name = alternative.serialName
                EditorPill(ComponentLabels.prettify(name.substringAfterLast('/')), name == current) {
                    if (name != current) onChange(ComponentJson.defaultOfSubclass(alternative))
                }
            }
        }
        if (selected != null) Column(tags = listOf("ee-nested-body")) {
            ComponentFields(owner, selected, body, path, onChange = { edited ->
                onChange(ComponentJson.withDiscriminator(edited, selected.serialName))
            })
        }
    }
}

@Composable
private fun UnsupportedField(label: String?, descriptor: SerialDescriptor) {
    Column(tags = listOf("ee-field")) {
        FieldLabel(label, description = null)
        Text(EntityEditorLang.unsupported(descriptor.serialName), tags = listOf("ee-hint"))
    }
}

/** A class of two to four numbers is a vector, and reads far better as one row than as four fields. */
private fun isCompactVector(descriptor: SerialDescriptor): Boolean {
    if (descriptor.elementsCount !in 2..4) return false
    return (0 until descriptor.elementsCount).all { index ->
        val element = descriptor.getElementDescriptor(index)
        val kind = element.kind
        !element.isNullable && (kind == PrimitiveKind.FLOAT || kind == PrimitiveKind.DOUBLE || kind == PrimitiveKind.INT || kind == PrimitiveKind.LONG)
    }
}

private fun numberJson(kind: PrimitiveKind, value: Double): JsonPrimitive = when (kind) {
    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT -> JsonPrimitive(value.toInt())
    PrimitiveKind.LONG -> JsonPrimitive(value.toLong())
    PrimitiveKind.FLOAT -> JsonPrimitive(value.toFloat())
    else -> JsonPrimitive(value)
}

internal fun formatNumber(value: Double, whole: Boolean): String {
    if (whole) return value.toLong().toString()
    if (!value.isFinite()) return value.toString()
    val rounded = BigDecimal(value).setScale(DecimalPlaces, RoundingMode.HALF_UP).stripTrailingZeros()
    return rounded.toPlainString()
}

private const val DecimalPlaces = 4
