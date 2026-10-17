package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.ExpressionEditing
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.client.vfx.VfxExpressionLanguage
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurveInput
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxValue
import ru.hollowhorizon.hollowengine.common.vfx.VfxVec3Value
import kotlin.math.abs

/**
 * What the inspector calls when a field of an animatable property takes the focus, with the id of
 * that property.
 */
val LocalVfxFieldFocus = staticCompositionLocalOf<(VfxProperty) -> Unit> { {} }

/**
 * What the timeline currently writes into a property, or null when it has no keys for it.
 */
val LocalVfxDriven = staticCompositionLocalOf<(VfxProperty) -> VfxDrivenValue?> { { null } }

/**
 * The value a timeline track holds right now, and which of its channels track has keys on. While the
 * timeline records, [write] keys new values (channel to value) at the playhead, so the fields stay editable.
 */
class VfxDrivenValue(
    val values: FloatArray,
    private val channels: Set<Int>,
    /** Whether a write becomes keys at the playhead; otherwise it is held there until the playhead moves. */
    val recording: Boolean,
    private val write: (Map<Int, Float>) -> Unit,
) {
    fun drives(channel: Int): Boolean = channel in channels

    fun value(channel: Int): Float = values.getOrElse(channel) { 0f }

    fun write(changes: Map<Int, Float>) {
        if (changes.isNotEmpty()) write.invoke(changes)
    }
}

/**
 * The shapes a number can take.
 *
 * Switching between them keeps the row where it is: a constant is one field, a range two, an
 * expression one wide field, and a curve, the only one that cannot be typed, shows its shape.
 */
private enum class VfxValueKind(val key: String, val icon: String) {
    CONST("kind_const", "hollowengine:textures/gui/icons/vfx/value_const.svg"),
    RANGE("kind_range", "hollowengine:textures/gui/icons/vfx/value_range.svg"),
    CURVE("kind_curve", "hollowengine:textures/gui/icons/vfx/value_curve.svg"),
    EXPR("kind_expr", "hollowengine:textures/gui/icons/vfx/value_expr.svg");
}

private fun VfxValue.kind(): VfxValueKind = when (this) {
    is VfxValue.Const -> VfxValueKind.CONST
    is VfxValue.Range -> VfxValueKind.RANGE
    is VfxValue.OverTime -> VfxValueKind.CURVE
    is VfxValue.Expr -> VfxValueKind.EXPR
}

/** A sample of the value, for carrying something over when the shape changes. */
private fun VfxValue.sample(): Float = when (this) {
    is VfxValue.Const -> value
    is VfxValue.Range -> from
    is VfxValue.OverTime -> curve.valueAt(0f)
    is VfxValue.Expr -> source.trim().toFloatOrNull() ?: 1f
}

private fun VfxValue.asKind(kind: VfxValueKind): VfxValue {
    if (kind() == kind) return this
    val sample = sample()
    return when (kind) {
        VfxValueKind.CONST -> VfxValue.Const(sample)
        VfxValueKind.RANGE -> VfxValue.Range(sample, sample)
        VfxValueKind.CURVE -> VfxValue.OverTime(VfxCurve.ramp(sample, 0f))
        VfxValueKind.EXPR -> VfxValue.Expr(formatNumber(sample))
    }
}

internal fun formatNumber(value: Float): String =
    if (value == value.toInt().toFloat() && abs(value) < 1.0e7f) value.toInt().toString()
    else "%.4f".format(value).replace(',', '.').trimEnd('0').trimEnd('.')

/**
 * The label of a row. What the field means is its tooltip rather than a line of text under it, so a
 * section stays one row per value.
 */
@Composable
fun VfxFieldLabel(label: String, hint: String? = null, width: Float = FieldLabelWidth) {
    if (label.isEmpty()) return
    val hinted = !hint.isNullOrBlank()
    Text(
        label,
        tags = if (hinted) listOf("insp-inline-label", "hinted") else listOf("insp-inline-label"),
        modifier = Modifier.size(width.px, UiLength.Fit).textWrap(false).textOverflow(UiTextOverflow.DOTS)
            .then(if (hinted) Modifier.tooltipOnHover(hint.orEmpty()) else Modifier),
    )
}

/**
 * One authored number on one line.
 */
@Composable
fun VfxValueRow(
    label: String,
    value: VfxValue,
    property: VfxProperty? = null,
    hint: String? = null,
    channel: Int = 0,
    onChange: (VfxValue) -> Unit,
) {
    val driven = property?.let { LocalVfxDriven.current(it) }?.takeIf { it.drives(channel) }

    Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
        VfxFieldLabel(label, hint)
        if (driven != null) {
            DrivenCell(driven, property, channel)
            DrivenMark(property)
        } else {
            ValueCells(value, property, onChange)
            KindPicker(value, onChange)
        }
    }
}

/**
 * Three values that make one vector.
 */
@Composable
fun VfxVec3Row(
    label: String,
    value: VfxVec3Value,
    property: VfxProperty? = null,
    hint: String? = null,
    uniform: Boolean = false,
    onChange: (VfxVec3Value) -> Unit,
) {
    if (uniform) {
        VfxValueRow(label, value.x, property, hint) { onChange(VfxVec3Value(it, it, it)) }
        return
    }

    val compact = value.x is VfxValue.Const && value.y is VfxValue.Const && value.z is VfxValue.Const
    if (!compact) {
        Column(modifier = Modifier.size(100.percent).gap(2.px)) {
            Row(modifier = Modifier.size(100.percent).alignItems(vertical = UiAlign.CENTER)) {
                VfxFieldLabel(
                    label,
                    hint
                )
            }
            VfxValueRow("    X", value.x, property, channel = 0) { onChange(value.copy(x = it)) }
            VfxValueRow("    Y", value.y, property, channel = 1) { onChange(value.copy(y = it)) }
            VfxValueRow("    Z", value.z, property, channel = 2) { onChange(value.copy(z = it)) }
        }
        return
    }

    val driven = property?.let { LocalVfxDriven.current(it) }
    Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
        VfxFieldLabel(label, hint, VectorLabelWidth)
        AxisNumber(
            "X",
            value.x.value,
            property,
            driven,
            0
        ) { onChange(value.copy(x = VfxValue.of(it))) }
        AxisNumber(
            "Y",
            value.y.value,
            property,
            driven,
            1
        ) { onChange(value.copy(y = VfxValue.of(it))) }
        AxisNumber(
            "Z",
            value.z.value,
            property,
            driven,
            2
        ) { onChange(value.copy(z = VfxValue.of(it))) }
        if (driven != null) {
            DrivenMark(property)
        } else {
            KindPicker(value.x) { picked ->
                onChange(VfxVec3Value(picked, value.y.asKind(picked.kind()), value.z.asKind(picked.kind())))
            }
        }
    }
}

/** Three plain numbers on one line: a position, a direction, an axis. */
@Composable
fun VfxFloat3Row(
    label: String,
    value: Vec3f,
    property: VfxProperty? = null,
    hint: String? = null,
    onChange: (Vec3f) -> Unit,
) {
    val driven = property?.let { LocalVfxDriven.current(it) }
    Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
        VfxFieldLabel(label, hint, VectorLabelWidth)
        AxisNumber("X", value.x, property, driven, 0) { onChange(Vec3f(it, value.y, value.z)) }
        AxisNumber("Y", value.y, property, driven, 1) { onChange(Vec3f(value.x, it, value.z)) }
        AxisNumber("Z", value.z, property, driven, 2) { onChange(Vec3f(value.x, value.y, it)) }
        if (driven != null) DrivenMark(property)
    }
}

/** One plain number, for the settings that are not values: a duration, a limit, a step. */
@Composable
fun VfxNumberRow(
    label: String,
    value: Float,
    hint: String? = null,
    min: Float = -Float.MAX_VALUE,
    max: Float = Float.MAX_VALUE,
    onChange: (Float) -> Unit,
) {
    Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
        VfxFieldLabel(label, hint)
        NumberCell(value, null, whole = false) { onChange(it.coerceIn(min, max)) }
    }
}

@Composable
fun VfxIntRow(
    label: String,
    value: Int,
    hint: String? = null,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    onChange: (Int) -> Unit,
) {
    Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
        VfxFieldLabel(label, hint)
        NumberCell(value.toFloat(), null, whole = true) { onChange(it.toInt().coerceIn(min, max)) }
    }
}

/** An axis name in its color, then the number, or what the timeline holds for it. */
@Composable
private fun AxisNumber(
    axis: String,
    value: Float,
    property: VfxProperty?,
    driven: VfxDrivenValue?,
    channel: Int,
    onChange: (Float) -> Unit,
) {
    Row(modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).gap(2.px).alignItems(vertical = UiAlign.CENTER)) {
        Text(axis, modifier = Modifier.fontSize(8f).foreground(axisColor(axis)))
        if (driven != null && driven.drives(channel)) {
            DrivenCell(driven, property, channel)
        } else {
            NumberCell(value, property, whole = false, onChange = onChange)
        }
    }
}

private fun axisColor(axis: String): UiColor = when (axis) {
    "X", "U" -> UiColor(0.91f, 0.42f, 0.42f, 1f)
    "Y", "V" -> UiColor(0.52f, 0.82f, 0.45f, 1f)
    else -> UiColor(0.45f, 0.62f, 0.95f, 1f)
}

/**
 * What a keyed channel holds at the playhead. A typed value becomes a key while the timeline records, and is held
 * there until the playhead moves otherwise.
 */
@Composable
private fun DrivenCell(driven: VfxDrivenValue, property: VfxProperty?, channel: Int) {
    NumberCell(driven.value(channel), property, whole = false) { driven.write(mapOf(channel to it)) }
}

@Composable
private fun DrivenMark(property: VfxProperty?) {
    val focus = LocalVfxFieldFocus.current
    InspectorIcon(KeyframeIcon, vfxText("driven_show")) { if (property != null) focus(property) }
}

@Composable
private fun NumberCell(value: Float, property: VfxProperty?, whole: Boolean, onChange: (Float) -> Unit) {
    val focus = LocalVfxFieldFocus.current
    var draft by remember { mutableStateOf(formatNumber(value)) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal) {
        lastExternal = value
        if (draft.toFloatOrNull() != value) draft = formatNumber(value)
    }

    TextField(
        value = draft,
        fontSize = 9f,
        filter = if (whole) UiTextInputFilter.INTEGER else UiTextInputFilter.DECIMAL,
        tags = listOf("insp-input", "insp-inline-input", "numeric"),
        modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).onFocus { if (property != null) focus(property) },
        onChange = { text ->
            draft = text
            text.toFloatOrNull()?.let { number ->
                lastExternal = number
                onChange(number)
            }
        },
    )
}

@Composable
internal fun VfxNumberCellInline(value: Float, onChange: (Float) -> Unit) =
    NumberCell(value, null, whole = false, onChange = onChange)

@Composable
private fun TextCell(text: String, property: VfxProperty?, onChange: (String) -> Unit) {
    val focus = LocalVfxFieldFocus.current
    var draft by remember { mutableStateOf(text) }
    var lastExternal by remember { mutableStateOf(text) }
    if (text != lastExternal) {
        lastExternal = text
        draft = text
    }

    val diagnostics = remember(draft) { VfxExpressionEditing.diagnostics(draft) }
    TextField(
        value = draft,
        fontSize = 9f,
        placeholder = vfxText("expression_placeholder"),
        completionContributor = VfxExpressionEditing.completions,
        syntaxHighlighter = VfxExpressionEditing.highlighter,
        diagnostics = diagnostics,
        tags = listOf("insp-input", "insp-inline-input", "vfx-expression"),
        modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).onFocus { if (property != null) focus(property) }
            .then(if (diagnostics.isEmpty()) Modifier.tooltipOnHover(vfxText("expression_hint")) else Modifier),
        onChange = { typed ->
            draft = typed
            lastExternal = typed
            onChange(typed)
        },
    )
}

@Composable
private fun ValueCells(value: VfxValue, property: VfxProperty?, onChange: (VfxValue) -> Unit) {
    when (value) {
        is VfxValue.Const -> NumberCell(value.value, property, whole = false) { onChange(VfxValue.Const(it)) }

        is VfxValue.Range -> {
            NumberCell(value.from, property, whole = false) { onChange(value.copy(from = it)) }
            Text("—", modifier = Modifier.fontSize(8f).foreground(HintTint))
            NumberCell(value.to, property, whole = false) { onChange(value.copy(to = it)) }
        }

        is VfxValue.Expr -> TextCell(value.source, property) { onChange(VfxValue.Expr(it)) }
        is VfxValue.OverTime -> CurveCell(value, property, onChange)
    }
}

@Composable
private fun CurveCell(value: VfxValue.OverTime, property: VfxProperty?, onChange: (VfxValue) -> Unit) {
    val focus = LocalVfxFieldFocus.current
    var editing by remember { mutableStateOf(false) }

    VfxCurvePreview(
        curve = value.curve,
        modifier = Modifier.size(0.px, CurveCellHeight.px).grow(1f).input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND).tooltipOnHover(vfxText("curve_open")).onPress { event ->
                editing = true
                if (property != null) focus(property)
                event.consume()
            },
    )

    if (editing) VfxCurveDialog(value, onChange = onChange, onClose = { editing = false })
}

@Composable
private fun KindPicker(value: VfxValue, onChange: (VfxValue) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    val kind = value.kind()

    Box(modifier = Modifier.onPlaced { anchor = it }) {
        InspectorIcon(kind.icon, vfxText(kind.key)) { open = true }
    }

    if (!open) return
    ContextMenu(
        id = "vfx-kind-$kind",
        anchorBounds = anchor,
        items = VfxValueKind.entries.map { entry ->
            UiDropdownItem(vfxText(entry.key), icon = entry.icon, checked = entry == kind) {
                onChange(value.asKind(entry))
            }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

@Composable
internal fun VfxSourcePicker(source: VfxCurveInput, onChange: (VfxCurveInput) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }

    Box(modifier = Modifier.onPlaced { anchor = it }) {
        InspectorButton(vfxText("source_${source.name.lowercase()}")) { open = true }
    }
    if (!open) return

    ContextMenu(
        id = "vfx-source",
        anchorBounds = anchor,
        items = VfxCurveInput.entries.map { entry ->
            UiDropdownItem(vfxText("source_${entry.name.lowercase()}"), checked = entry == source) { onChange(entry) }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

@Composable
internal fun VfxCurvePreview(curve: VfxCurve, modifier: Modifier) {
    Box(
        modifier = modifier.drawBehind(key = curve) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@drawBehind

            drawRect(UiRect(0f, 0f, width, height), UiPaint.Color(CurveBackground), radius = 3f)

            val keys = curve.keys
            if (keys.isEmpty()) return@drawBehind

            val start = keys.first().time
            val span = (keys.last().time - start).takeIf { it > 1.0e-4f } ?: 1f
            var minimum = keys.minOf { it.value }
            var maximum = keys.maxOf { it.value }
            if (maximum - minimum < 1.0e-4f) {
                minimum -= 0.5f
                maximum += 0.5f
            }

            val inset = 2f
            val usable = height - inset * 2f
            val columnWidth = width / CurvePreviewSteps
            var previousY = Float.NaN
            for (step in 0 until CurvePreviewSteps) {
                val t = step.toFloat() / (CurvePreviewSteps - 1)
                val sampled = curve.valueAt(start + span * t)
                val y = inset + usable - ((sampled - minimum) / (maximum - minimum)).coerceIn(0f, 1f) * usable
                val top = if (previousY.isNaN()) y else minOf(previousY, y)
                val bottom = if (previousY.isNaN()) y else maxOf(previousY, y)
                drawRect(
                    UiRect(step * columnWidth, top, maxOf(columnWidth, 1f), maxOf(bottom - top, 1.5f)),
                    UiPaint.Color(CurveLine),
                )
                previousY = y
            }
        },
    )
}

@Composable
internal fun InspectorIcon(icon: String, tooltip: String, onClick: () -> Unit) {
    InspectorIconButton(icon = icon, tooltip = tooltip, tags = listOf("insp-inline-icon"), onClick = onClick)
}

/** Highlighting, completion and diagnostics for the expressions of the effect dialect. */
internal val VfxExpressionEditing = ExpressionEditing(VfxExpressionLanguage)

internal fun vfxText(name: String): String = "hollowengine.gui.vfx.$name".lang

internal const val FieldLabelWidth = 84f

/** Vectors carry three fields, so their short labels ("Position") get less of the line. */
internal const val VectorLabelWidth = 48f

internal const val CurveCellHeight = 15f
private const val CurvePreviewSteps = 40
private const val KeyframeIcon = "hollowengine:textures/gui/icons/vfx/key.svg"

internal val HintTint = UiColor(0.55f, 0.58f, 0.65f, 1f)
internal val CurveBackground = UiColor(0.09f, 0.1f, 0.11f, 1f)
private val CurveLine = UiColor(0.85f, 0.75f, 0.45f, 1f)
