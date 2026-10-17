@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.ui.inspector

import ru.hollowhorizon.hollowengine.client.ui.widgets.NumberField
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import androidx.compose.runtime.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextFieldMode
import ru.hollowhorizon.hollowengine.client.ui.widgets.formatUiNumber
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.VectorDescriptors

/**
 * The generic component editor. One composable per serial kind, recursing the way the descriptor does.
 */
@Composable
internal fun AutoFields(
    owner: ResourceLocation?,
    descriptor: SerialDescriptor,
    value: JsonObject,
    path: String,
    query: String = "",
    onChange: (JsonObject) -> Unit,
) {
    val animated = LocalAnimatedFields.current
    for (index in 0 until descriptor.elementsCount) {
        if (ComponentLabels.isHidden(descriptor, index)) continue

        val name = descriptor.getElementName(index)
        val label = ComponentLabels.fieldName(owner, descriptor, index)
        if (!matchesQuery(query, label, name)) continue

        val element = descriptor.getElementDescriptor(index)
        val current = value[name] ?: ComponentJson.defaultJson(element)

        key(name) {
            // What lies inside the field is animated by its path, as `playback.volume`.
            CompositionLocalProvider(LocalAnimatedFields provides animated?.nested(name)) {
                AnimatedField(animated, name) {
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
                            widget = ComponentLabels.widget(descriptor, index),
                            initialValue = ComponentLabels.initialValue(descriptor, index),
                            enumKey = ComponentLabels.fieldKey(owner, descriptor, index),
                        ),
                        value = current,
                        path = "$path/$name",
                        onChange = { onChange(value.withField(name, it)) },
                    )
                }
            }
        }
    }
}

/**
 * The fields of what an inspector shows that a timeline can animate. A field it animates shows the timeline's
 * value and its key button is lit; while keys are recorded an edit of it keys it there, otherwise the edit holds
 * until the playhead moves.
 */
interface AnimatedFields {
    /** Null when [name] cannot be animated; otherwise whether it is. */
    fun isAnimated(name: String): Boolean?

    /** Starts animating [name], keyed where the timeline is, or stops and lets the authored value back. */
    fun toggle(name: String)

    /** The same, for what lies inside the field [name], named by their paths under it. */
    fun nested(name: String): AnimatedFields = PrefixedAnimatedFields(this, "$name.")
}

private class PrefixedAnimatedFields(private val parent: AnimatedFields, private val prefix: String) : AnimatedFields {
    override fun isAnimated(name: String): Boolean? = parent.isAnimated(prefix + name)
    override fun toggle(name: String) = parent.toggle(prefix + name)
    override fun nested(name: String): AnimatedFields = PrefixedAnimatedFields(parent, "$prefix$name.")
}

/** What the fields [AutoFields] shows at its top level can be animated by, if anything. */
val LocalAnimatedFields = compositionLocalOf<AnimatedFields?> { null }

@Composable
private fun AnimatedField(animated: AnimatedFields?, name: String, editor: @Composable () -> Unit) {
    val state = animated?.isAnimated(name) ?: return editor()
    KeyedField(name, state, onToggle = { animated.toggle(name) }, editor = editor)
}

/** A field with a key button beside it, lit while a timeline animates the field. */
@Composable
fun KeyedField(id: String, animated: Boolean, onToggle: () -> Unit, editor: @Composable () -> Unit) {
    Row(tags = listOf("insp-animated-field")) {
        Box(
            mode = UiBoxMode.STACK,
            tags = listOf("insp-animated-value"),
            modifier = Modifier.grow(1f),
        ) {
            Column(modifier = Modifier.size(100.percent)) { editor() }
        }
        Image(
            KeyIcon,
            id = "animate-$id",
            tags = if (animated) listOf("insp-key-toggle", "active") else listOf("insp-key-toggle"),
            modifier = Modifier.input(hoverable = true, clickable = true)
                .tooltipOnHover(if (animated) InspectorLang.stopAnimating else InspectorLang.animate)
                .onClick { event ->
                    onToggle()
                    event.consume()
                },
        )
    }
}

private const val KeyIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"

@Composable
internal fun FieldLabel(label: String?, description: String?) {
    if (label == null) return FieldHelp(description)

    Row(tags = listOf("insp-field-head")) {
        Text(label, tags = listOf("insp-label"), modifier = Modifier.grow(1f))
        FieldHelp(description)
    }
}

@Composable
internal fun FieldHelp(description: String?) {
    val text = description ?: return

    Image(
        HelpIcon,
        tags = listOf("insp-help"),
        modifier = Modifier.size(9.px, 9.px).input(hoverable = true).tooltipOnHover(text),
    )
}

private const val HelpIcon = "hollowengine:textures/gui/icons/info.svg"

/** Lets a dragged slider hand on its value no more often than every [SliderIntervalNanos]. */
private class SliderPace {
    private var last = 0L

    fun due(): Boolean {
        val now = System.nanoTime()
        if (now - last < SliderIntervalNanos) return false
        last = now
        return true
    }
}

private const val SliderIntervalNanos = 100_000_000L

/** How many kinds a polymorphic field shows as a row of pills before it lists them in a dropdown. */
private const val MaxKindPills = 4

internal data class FieldRange(val min: Double, val max: Double, val slider: Boolean)

internal data class FieldHints(
    val range: FieldRange? = null,
    val multiline: Boolean = false,
    val asset: List<String> = emptyList(),
    val bone: Boolean = false,
    /** The registered editor the field asks for; see [InspectorWidgets]. */
    val widget: String? = null,
    val initialValue: String? = null,
    val enumKey: String? = null,
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
    hints.widget?.let(InspectorWidgets::find)?.let { widget ->
        widget.Content(InspectorWidgetField(label, description, descriptor, value, path, onChange))
        return
    }

    when (val kind = descriptor.kind) {
        PrimitiveKind.BOOLEAN -> BooleanField(label, description, value, onChange)

        PrimitiveKind.STRING, PrimitiveKind.CHAR -> StringField(label, description, hints, value, path, onChange)

        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
            -> AutoNumberField(label, description, kind as PrimitiveKind, hints, value, path, onChange)

        SerialKind.ENUM -> EnumField(label, description, descriptor, value, path, hints.enumKey, onChange)

        StructureKind.LIST -> {
            val size = VectorDescriptors.components(descriptor)
            if (size != null) ListVectorField(label, description, descriptor, size, value, path, onChange)
            else ListField(label, owner, descriptor, hints, value, path, onChange)
        }

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

    Column(tags = listOf("insp-field")) {
        Row(tags = listOf("insp-field-head")) {
            if (label != null) Text(label, tags = listOf("insp-label"), modifier = Modifier.grow(1f))
            FieldHelp(description)
            Checkbox(
                id = "insp-optional$path",
                checked = present,
                onCheckedChange = { checked ->
                    onChange(if (checked) ComponentJson.initialValue(inner, hints.initialValue) else JsonNull)
                },
                tags = listOf("insp-checkbox"),
                modifier = Modifier.input(hoverable = true, clickable = true),
            )
        }
        if (present) {
            ValueEditor(null, null, owner, inner, hints, value, path, onChange)
        } else {
            Text(InspectorLang.notSet, tags = listOf("insp-hint"))
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
    Column(tags = listOf("insp-field")) {
        Row(
            tags = listOf("insp-check-row"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                .onClick { onChange(JsonPrimitive(!checked)) },
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onChange(JsonPrimitive(it)) },
                tags = listOf("insp-checkbox"),
            )
            if (label != null) Text(label, tags = listOf("insp-check-label"), modifier = Modifier.grow(1f))
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

    val host = LocalInspectorHost.current
    val candidates = if (hints.asset.isEmpty()) emptyList() else remember(hints.asset, host) {
        host?.assets(hints.asset).orEmpty()
    }

    Column(tags = listOf("insp-field")) {
        FieldLabel(label, description)
        Row(tags = listOf("insp-input-row")) {
            TextField(
                value = text,
                id = "insp-input$path",
                mode = if (hints.multiline) UiTextFieldMode.MULTI_LINE else UiTextFieldMode.SINGLE_LINE,
                fontSize = 9f,
                completionContributor = if (candidates.isEmpty()) null else assetCompletions(candidates),
                onChange = { typed ->
                    onChange(JsonPrimitive(if (hints.asset.isEmpty()) typed else sanitizeAssetPath(typed)))
                },
                tags = listOf("insp-input"),
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
private fun AutoNumberField(
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
    val pace = remember(path) { SliderPace() }
    val pending = remember(path) { arrayOfNulls<Double>(1) }
    fun send(next: Double) = onChange(numberJson(kind, next))

    NumberRow(
        label = label.orEmpty(),
        value = number,
        whole = whole,
        id = path,
        min = range?.min ?: Double.NEGATIVE_INFINITY,
        max = range?.max ?: Double.POSITIVE_INFINITY,
        slider = range?.slider == true,
        help = description,
        onGestureEnd = {
            pending[0]?.let(::send)
            pending[0] = null
        },
    ) { next ->
        pending[0] = next
        if (pace.due()) {
            send(next)
            pending[0] = null
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
    NumberField(
        value = value,
        id = "insp-number$path",
        whole = whole,
        modifier = modifier.grow(1f),
        onChange = onChange,
    )
}

@Composable
private fun EnumField(
    label: String?,
    description: String?,
    descriptor: SerialDescriptor,
    value: JsonElement,
    path: String,
    fieldKey: String?,
    onChange: (JsonElement) -> Unit,
) {
    val current = (value as? JsonPrimitive)?.contentOrNull
    Column(tags = listOf("insp-field")) {
        FieldLabel(label, description)
        PillFlow {
            descriptor.elementNames.forEachIndexed { index, name ->
                Pill(ComponentLabels.enumValueName(descriptor, index, fieldKey), name == current, id = "insp-choice$path/$name") {
                    onChange(JsonPrimitive(name))
                }
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
    val components = (0 until descriptor.elementsCount).mapNotNull { index ->
        val name = descriptor.getElementName(index)
        val kind = descriptor.getElementDescriptor(index).kind as? PrimitiveKind ?: return@mapNotNull null
        VectorComponent(name, kind, (body[name] as? JsonPrimitive)?.doubleOrNull ?: 0.0) { next ->
            onChange(body.withField(name, numberJson(kind, next)))
        }
    }
    VectorRow(label, description, path, components)
}

/** A vector written as a list of numbers, such as `Vec3f`: its components side by side, never added or removed. */
@Composable
private fun ListVectorField(
    label: String?,
    description: String?,
    descriptor: SerialDescriptor,
    size: Int,
    value: JsonElement,
    path: String,
    onChange: (JsonElement) -> Unit,
) {
    val kind = descriptor.getElementDescriptor(0).kind as? PrimitiveKind ?: return UnsupportedField(label, descriptor)
    val items = value as? JsonArray ?: JsonArray(emptyList())
    val numbers = List(size) { index -> (items.getOrNull(index) as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
    val components = numbers.mapIndexed { index, number ->
        VectorComponent(VectorAxes[index], kind, number) { next ->
            onChange(JsonArray(numbers.mapIndexed { other, old -> numberJson(kind, if (other == index) next else old) }))
        }
    }
    VectorRow(label, description, path, components)
}

/** Three numbers on one row, the way a vector field of a component is shown; [step] for angles is a degree. */
@Composable
internal fun Vec3Row(
    label: String,
    value: Vec3f,
    path: String,
    description: String? = null,
    step: Double? = null,
    onChange: (Vec3f) -> Unit,
) {
    val numbers = floatArrayOf(value.x, value.y, value.z)
    VectorRow(label, description, path, numbers.indices.map { index ->
        VectorComponent(VectorAxes[index], PrimitiveKind.FLOAT, numbers[index].toDouble(), step) { next ->
            val changed = numbers.copyOf().also { it[index] = next.toFloat() }
            onChange(Vec3f(changed[0], changed[1], changed[2]))
        }
    })
}

private class VectorComponent(
    val name: String,
    val kind: PrimitiveKind,
    val value: Double,
    val step: Double? = null,
    val onChange: (Double) -> Unit,
)

private val VectorAxes = listOf("x", "y", "z", "w")

@Composable
private fun VectorRow(label: String?, description: String?, path: String, components: List<VectorComponent>) {
    Column(tags = listOf("insp-field")) {
        FieldLabel(label, description)
        Row(tags = listOf("insp-vector")) {
            components.forEach { component ->
                NumberField(
                    value = component.value,
                    id = "insp-number$path/${component.name}",
                    label = component.name,
                    whole = component.kind == PrimitiveKind.INT || component.kind == PrimitiveKind.LONG,
                    step = component.step,
                    modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
                    onChange = component.onChange,
                )
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

    Column(tags = listOf("insp-nested")) {
        Row(
            tags = listOf("insp-nested-head"),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                .onClick { expanded = !expanded },
        ) {
            DisclosureArrow(expanded)
            Text(title, tags = listOf("insp-label"))
        }
        if (expanded) Column(tags = listOf("insp-nested-body")) {
            AutoFields(owner, descriptor, body, path, onChange = onChange)
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
    val animated = LocalAnimatedFields.current

    Column(tags = listOf("insp-nested")) {
        Row(tags = listOf("insp-nested-head")) {
            Text("${label.orEmpty()} (${items.size})", tags = listOf("insp-label"), modifier = Modifier.grow(1f))
            val kinds = if (element.kind is PolymorphicKind) ComponentJson.subclassDescriptors(element) else emptyList()
            var picking by remember { mutableStateOf(false) }
            var anchor by remember { mutableStateOf(UiRect.Zero) }
            Box(modifier = Modifier.onPlaced { anchor = it }) {
                InspectorIconButton(InspectorIcons.ADD, InspectorLang.add) {
                    if (kinds.size > 1) picking = true
                    else onChange(items.withItemAdded(ComponentJson.defaultJson(element)))
                }
            }
            if (picking) ContextMenu(
                "insp-add$path", anchor,
                kinds.map { kind ->
                    UiDropdownItem(ComponentLabels.typeName(kind)) {
                        onChange(items.withItemAdded(ComponentJson.defaultOfSubclass(kind)))
                    }
                },
                onExpandedChange = { picking = it },
            )
        }
        Column(tags = listOf("insp-nested-body")) {
            items.forEachIndexed { index, item ->
                key(index) {
                    Row(tags = listOf("insp-list-item")) {
                        Column(modifier = Modifier.grow(1f).gap(4.px)) {
                            CompositionLocalProvider(LocalAnimatedFields provides animated?.nested(index.toString())) {
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
                        }
                        InspectorIconButton(InspectorIcons.REMOVE, InspectorLang.remove) {
                            onChange(items.withoutItem(index))
                        }
                    }
                }
            }
            if (items.isEmpty()) Text(InspectorLang.emptyList, tags = listOf("insp-hint"))
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

    Column(tags = listOf("insp-nested")) {
        Row(tags = listOf("insp-nested-head")) {
            Text("${label.orEmpty()} (${body.size})", tags = listOf("insp-label"), modifier = Modifier.grow(1f))
            InspectorIconButton(InspectorIcons.ADD, InspectorLang.add) {
                val name = generateSequence(1) { it + 1 }.first { "key$it" !in body }
                onChange(body.withField("key$name", ComponentJson.defaultJson(valueDescriptor)))
            }
        }
        Column(tags = listOf("insp-nested-body")) {
            body.forEach { (mapKey, mapValue) ->
                key(mapKey) {
                    Row(tags = listOf("insp-list-item")) {
                        Column(modifier = Modifier.grow(1f).gap(4.px)) {
                            TextField(
                                value = mapKey,
                                id = "insp-key$path/$mapKey",
                                fontSize = 9f,
                                onChange = { renamed -> onChange(body.withKeyRenamed(mapKey, renamed)) },
                                tags = listOf("insp-input"),
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
                        InspectorIconButton(InspectorIcons.REMOVE, InspectorLang.remove) {
                            onChange(body.withoutField(mapKey))
                        }
                    }
                }
            }
            if (body.isEmpty()) Text(InspectorLang.emptyList, tags = listOf("insp-hint"))
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

    Column(tags = listOf("insp-nested")) {
        FieldLabel(label, description = null)
        if (alternatives.size > MaxKindPills) {
            var expanded by remember { mutableStateOf(false) }
            UiDropdown(
                id = "insp-kind$path",
                label = selected?.let(ComponentLabels::typeName).orEmpty(),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                items = alternatives.map { alternative ->
                    UiDropdownItem(ComponentLabels.typeName(alternative), checked = alternative.serialName == current, mark = UiDropdownMark.RADIO) {
                        if (alternative.serialName != current) onChange(ComponentJson.defaultOfSubclass(alternative))
                    }
                },
            )
        } else PillFlow {
            alternatives.forEach { alternative ->
                val name = alternative.serialName
                Pill(ComponentLabels.typeName(alternative), name == current, id = "insp-choice$path/$name") {
                    if (name != current) onChange(ComponentJson.defaultOfSubclass(alternative))
                }
            }
        }
        if (selected != null) Column(tags = listOf("insp-nested-body")) {
            AutoFields(owner, selected, body, path, onChange = { edited ->
                onChange(ComponentJson.withDiscriminator(edited, selected.serialName))
            })
        }
    }
}

@Composable
private fun UnsupportedField(label: String?, descriptor: SerialDescriptor) {
    Column(tags = listOf("insp-field")) {
        FieldLabel(label, description = null)
        Text(InspectorLang.unsupported(descriptor.serialName), tags = listOf("insp-hint"))
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

internal fun formatNumber(value: Double, whole: Boolean): String = formatUiNumber(value, whole)
