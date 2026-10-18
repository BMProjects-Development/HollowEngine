package ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderGraphNode
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderPinSpec
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.ColorPicker
import ru.hollowhorizon.hollowengine.client.ui.widgets.ColorPickerWidth
import ru.hollowhorizon.hollowengine.client.ui.widgets.NumberField
import java.util.*
import kotlin.math.roundToInt

/**
 * The value of an unlinked input: its name, then a field per component, or for a color a swatch that
 * opens a picker.
 */
@Composable
internal fun ValueFields(
    node: ShaderGraphNode,
    pin: ShaderPinSpec,
    values: List<Float>,
    components: Int,
    actions: ShaderFieldActions,
) {
    val shown = (0 until components).map { values.getOrElse(it) { values.lastOrNull() ?: 0f } }
    fun set(component: Int, value: Float) =
        actions.setValue(node.id, pin.name, shown.toMutableList().also { it[component] = value })

    if (components == 1 && !pin.color) {
        GraphNumber("sg-field-${node.id}-${pin.name}-0", shown[0], actions, label = pin.name) { set(0, it) }
        return
    }
    Text(pin.name, tags = listOf("sg-pin-label"), modifier = Modifier.size(0.px, UiLength.Auto).grow(1f))
    if (pin.color && components >= 3) {
        ColorSwatch("sg-color-${node.id}-${pin.name}", shown, actions) { actions.setValue(node.id, pin.name, it) }
        return
    }
    Row(tags = listOf("sg-fields"), modifier = Modifier.size(FieldsWidth.px, UiLength.Auto)) {
        shown.forEachIndexed { component, value ->
            GraphNumber("sg-field-${node.id}-${pin.name}-$component", value, actions) { set(component, it) }
        }
    }
}

/**
 * A number of a node: dragged, stepped or typed, as one step back. With a [label] it is the whole row of a
 * one-number input. Without [gestures], what it changes is left to whoever holds a step open around it.
 */
@Composable
internal fun GraphNumber(
    id: String,
    value: Float,
    actions: ShaderFieldActions,
    gestures: Boolean = true,
    label: String? = null,
    onChange: (Float) -> Unit,
) {
    NumberField(
        value = value.toDouble(),
        id = id,
        label = label,
        format = { formatValue(it.toFloat()) },
        tags = listOf("sg-field"),
        modifier = Modifier.size(0.px, FieldHeight.px).grow(1f),
        onGestureStart = { if (gestures) actions.beginGesture() },
        onGestureEnd = { if (gestures) actions.endGesture() },
    ) { onChange(rounded(it.toFloat())) }
}

/**
 * A color as a swatch; a press opens a picker, with a field per channel under it for values past 1,
 * such as a bright emission. Everything done while the picker is open is one step back.
 */
@Composable
private fun ColorSwatch(id: String, values: List<Float>, actions: ShaderFieldActions, onChange: (List<Float>) -> Unit) {
    var anchor by remember(id) { mutableStateOf<UiRect?>(null) }
    val color = UiColor(values[0], values[1], values[2], values.getOrElse(3) { 1f })
    Box(
        id = id,
        tags = listOf("sg-swatch"),
        modifier = Modifier.size(FieldsWidth.px, FieldHeight.px)
            .drawBehind(key = color) {
                val inner = UiRect(bounds.x + 1f, bounds.y + 1f, bounds.width - 2f, bounds.height - 2f)
                drawRect(inner, UiPaint.Color(color.clamped()), radius = 4f)
            }
            .input(hoverable = true, clickable = true).onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT && anchor == null) {
                    actions.beginGesture()
                    anchor = UiRect(event.x, event.y, 0f, 0f)
                }
                event.consume()
            },
    )
    val open = anchor ?: return
    Popup(
        anchorBounds = open,
        id = "$id-popup",
        tags = listOf("sg-color-popup"),
        onDismiss = {
            anchor = null
            actions.endGesture()
        },
    ) {
        Column(tags = listOf("sg-color-popup-body")) {
            ColorPicker(
                value = color.clamped(),
                showAlpha = values.size == 4,
                onValueChange = { picked ->
                    onChange(
                        listOf(picked.red, picked.green, picked.blue, picked.alpha).take(values.size).map(::rounded)
                    )
                },
            )
            Row(tags = listOf("sg-fields"), modifier = Modifier.size(ColorPickerWidth.px, UiLength.Auto)) {
                values.forEachIndexed { channel, value ->
                    GraphNumber("$id-channel-$channel", value, actions, gestures = false) { typed ->
                        onChange(values.toMutableList().also { it[channel] = typed })
                    }
                }
            }
        }
    }
}

private fun UiColor.clamped() =
    UiColor(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))

internal fun formatValue(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.3f".format(Locale.ROOT, value).trimEnd('0')

private fun rounded(value: Float): Float = (value * 1000f).roundToInt() / 1000f
