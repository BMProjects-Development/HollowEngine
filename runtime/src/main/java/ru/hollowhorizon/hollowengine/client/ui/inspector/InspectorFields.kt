package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.*

/** A titled group of rows. [boxed] draws the group as a card instead of a ruled heading. */
@Composable
fun Section(title: String, id: String? = null, boxed: Boolean = false, help: String? = null, content: HollowUiContent) {
    Column(id = id, tags = if (boxed) listOf("insp-section", "boxed") else listOf("insp-section")) {
        if (title.isNotEmpty()) {
            if (help == null) Text(title, tags = listOf("insp-section-title"))
            else Row(tags = listOf("insp-field-head")) {
                Text(title, tags = listOf("insp-section-title"), modifier = Modifier.grow(1f))
                FieldHelp(help)
            }
            if (!boxed) Box(tags = listOf("insp-section-rule"))
        }
        Column(tags = listOf("insp-section-body"), content = content)
    }
}

/**
 * A section that folds away, headed by a bar with [icon] so it reads apart from the fields in it.
 * [expanded] stays the caller's state so it survives a rebuild; [trailing] sits at the end of the bar,
 * for the actions that belong to the whole section.
 */
@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    id: String? = null,
    icon: String? = null,
    trailing: HollowUiContent? = null,
    onToggle: () -> Unit,
    content: HollowUiContent,
) {
    Column(id = id, tags = listOf("insp-section", "insp-section-fold")) {
        Row(
            id = id?.let { "$it-head" },
            tags = listOf("insp-section-head"),
            attributes = mapOf("expanded" to expanded.toString()),
            modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onToggle()
                    event.consume()
                },
        ) {
            icon?.let { Image(it, tags = listOf("insp-section-icon")) }
            Text(title, tags = listOf("insp-section-title"))
            trailing?.invoke()
            DisclosureArrow(expanded)
        }
        if (expanded) Column(tags = listOf("insp-section-body"), content = content)
    }
}

@Composable
fun Label(text: String) = Text(text, tags = listOf("insp-label"))

@Composable
fun Hint(text: String) = Text(text, tags = listOf("insp-hint"))

@Composable
fun Readonly(label: String, value: String) {
    Row(tags = listOf("insp-readonly")) {
        Text(label, tags = listOf("insp-readonly-label"))
        Text(value, tags = listOf("insp-readonly-value"), modifier = Modifier.tooltipOnHover(value))
    }
}

@Composable
fun TextRow(
    label: String,
    value: String,
    id: String? = null,
    filter: UiTextInputFilter = UiTextInputFilter.ANY,
    completions: UiCompletionContributor? = null,
    highlighter: UiSyntaxHighlighter? = null,
    diagnostics: List<UiTextDiagnostic> = emptyList(),
    trailing: HollowUiContent? = null,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    Column(tags = listOf("insp-field")) {
        if (label.isNotEmpty()) FieldLabel(label, help)
        Row(tags = listOf("insp-input-row")) {
            TextField(
                value = value,
                id = id ?: "insp-input-$label",
                filter = filter,
                completionContributor = completions,
                syntaxHighlighter = highlighter,
                diagnostics = diagnostics,
                fontSize = 9f,
                onChange = onChange,
                tags = listOf("insp-input"),
                modifier = Modifier.grow(1f),
            )
            trailing?.invoke()
        }
    }
}

@Composable
fun NameRow(label: String, value: String, id: String? = null, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    TextRow(label, draft, id = id) { next ->
        draft = next
        val trimmed = next.trim()
        if (trimmed.isNotEmpty() && trimmed != value) onCommit(trimmed)
    }
}

@Composable
fun IntRow(
    label: String,
    value: Int,
    id: String? = null,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    onChange: (Int) -> Unit,
) {
    NumberRow(label, value.toDouble(), whole = true, id = id, min = min.toDouble(), max = max.toDouble()) {
        onChange(it.toInt())
    }
}

@Composable
fun FloatRow(
    label: String,
    value: Float,
    id: String? = null,
    min: Float = -Float.MAX_VALUE,
    max: Float = Float.MAX_VALUE,
    help: String? = null,
    onChange: (Float) -> Unit,
) {
    NumberRow(label, value.toDouble(), whole = false, id = id, min = min.toDouble(), max = max.toDouble(), help = help) {
        onChange(it.toFloat())
    }
}

@Composable
fun NumberRow(
    label: String,
    value: Double,
    whole: Boolean,
    id: String? = null,
    min: Double = -Double.MAX_VALUE,
    max: Double = Double.MAX_VALUE,
    help: String? = null,
    onChange: (Double) -> Unit,
) {
    Column(tags = listOf("insp-field")) {
        if (label.isNotEmpty()) FieldLabel(label, help)
        NumberInput(
            path = id ?: "-$label",
            value = value,
            whole = whole,
        ) { typed -> onChange(typed.coerceIn(min, max)) }
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    switch: Boolean = false,
    hint: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        tags = listOf("insp-check-row"),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onChange(!checked)
            event.consume()
        }.let { if (hint != null) it.tooltipOnHover(hint) else it },
    ) {
        if (switch) {
            Text(label, tags = listOf("insp-check-label"))
            Checkbox(checked = checked, variant = UiCheckboxVariant.SWITCH, onCheckedChange = onChange)
        } else {
            Checkbox(checked = checked, tags = listOf("insp-checkbox"), onCheckedChange = onChange)
            Text(label, tags = listOf("insp-check-label"))
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(tags = listOf("insp-field")) {
        if (label.isNotEmpty()) Label(label)
        Row(tags = listOf("insp-input-row")) {
            Slider(
                value = value,
                min = min,
                max = max,
                tags = listOf("insp-slider"),
                onValueChange = onChange,
            )
            Text(formatNumber(value.toDouble(), whole = false), tags = listOf("insp-slider-value"))
        }
    }
}

@Composable
fun PillFlow(id: String? = null, content: HollowUiContent) {
    Layout(
        content = content,
        id = id,
        tags = listOf("insp-pill-flow"),
        modifier = Modifier.size(100.percent, UiLength.Fit).lineSpacing(3f).textWrap(),
        measurePolicy = UiMeasurePolicies.InlineFlow,
    )
}

@Composable
fun Pill(label: String, active: Boolean, id: String? = null, onClick: () -> Unit) {
    InlineWidget(
        id = id ?: "insp-pill-$label",
        tags = if (active) listOf("insp-pill", "active") else listOf("insp-pill"),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    ) {
        Text(label, tags = listOf("insp-pill-label"))
    }
}

@Composable
fun <T> Pills(values: List<T>, current: T, label: (T) -> String, onChange: (T) -> Unit) {
    PillFlow {
        values.forEach { value ->
            Pill(label(value), value == current) { onChange(value) }
        }
    }
}

@Composable
fun InspectorButton(
    label: String,
    icon: String? = null,
    modifier: Modifier = Modifier,
    tags: List<String> = emptyList(),
    onClick: () -> Unit,
) = Button(label, icon = icon, tags = listOf("insp-button") + tags, modifier = modifier, onClick = onClick)

@Composable
fun InspectorIconButton(
    icon: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tags: List<String> = emptyList(),
    onClick: () -> Unit,
) = InspectorIconButton(null, icon, tooltip, modifier, active, tags, onClick)

/** A small icon in a row of the inspector: no fill of its own, only its color answers the pointer. */
@Composable
fun InspectorIconButton(
    id: String?,
    icon: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tags: List<String> = emptyList(),
    onClick: () -> Unit,
) = IconButton(
    icon,
    tooltip,
    id = id,
    active = active,
    tags = listOf("inline", "insp-icon-button") + tags,
    modifier = modifier,
    onClick = onClick,
)

@Composable
fun DisclosureArrow(expanded: Boolean) {
    Box(
        tags = listOf("insp-arrow"),
        attributes = mapOf("expanded" to if (expanded) "true" else "false"),
    )
}
