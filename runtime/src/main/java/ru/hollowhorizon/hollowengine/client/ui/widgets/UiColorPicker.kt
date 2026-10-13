package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** How wide a [ColorPicker] is, for whatever is laid out under it to match. */
const val ColorPickerWidth = 180f

/** HSV color picker with an optional alpha channel, controlled by a single [UiColor] value. */
@Composable
fun ColorPicker(
    value: UiColor,
    onValueChange: (UiColor) -> Unit,
    showAlpha: Boolean = true,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
) {
    var hsv by remember { mutableStateOf(value.toHsv()) }
    var lastExternal by remember { mutableStateOf(value.clamped()) }
    val external = value.clamped()
    if (external != lastExternal) {
        hsv = external.toHsv(hsv.hue)
        lastExternal = external
    }

    fun update(next: HsvColor) {
        hsv = next.normalized()
        val color = hsv.toUiColor()
        lastExternal = color
        onValueChange(color)
    }

    Column(
        id = id,
        tags = tags + "color-picker",
        modifier = Modifier.size(ColorPickerWidth.px, 154.px).gap(4.px).then(modifier ?: Modifier),
    ) {
        Box(
            mode = UiBoxMode.STACK,
            tags = listOf("color-picker-saturation-value"),
            modifier = Modifier.size(100.percent, 112.px)
                .background(HsvColor(hsv.hue, 1f, 1f, 1f).toUiColor())
                .border(1.px, PickerBorderColor, 3f)
                .borderRadius(3f)
                .clip(true),
        ) {
            Box(
                modifier = Modifier.size(100.percent, 100.percent).background(
                    90f,
                    listOf(
                        UiGradientStop(0f, UiColor.White),
                        UiGradientStop(1f, UiColor(1f, 1f, 1f, 0f)),
                    ),
                ),
            )
            Box(
                modifier = Modifier.size(100.percent, 100.percent).background(
                    180f,
                    listOf(
                        UiGradientStop(0f, UiColor(0f, 0f, 0f, 0f)),
                        UiGradientStop(1f, UiColor.Black),
                    ),
                ),
            )
            Box(
                tags = listOf("color-picker-cursor"),
                modifier = Modifier.size(10.px, 10.px)
                    .position((hsv.saturation * 100f).percent - 5.px, ((1f - hsv.value) * 100f).percent - 5.px)
                    .background(hsv.toUiColor().copy(alpha = 1f))
                    .border(2.px, UiColor.White, 5f)
                    .borderRadius(5f),
            )
            PickerInputLayer(UiCursorShape.CROSSHAIR) { event ->
                updateSaturationValue(event, hsv, ::update)
            }
        }

        Box(
            mode = UiBoxMode.STACK,
            tags = listOf("color-picker-hue"),
            modifier = Modifier.size(100.percent, 14.px)
                .background(90f, HueStops)
                .border(1.px, PickerBorderColor, 3f)
                .borderRadius(3f),
        ) {
            Box(
                tags = listOf("color-picker-hue-cursor"),
                modifier = Modifier.size(6.px, 16.px)
                    .position((hsv.hue * 100f).percent - 3.px, (-1).px)
                    .background(UiColor.White)
                    .border(1.px, UiColor.Black, 2f)
                    .borderRadius(2f),
            )
            PickerInputLayer(UiCursorShape.HAND) { event -> updateHue(event, hsv, ::update) }
        }

        if (showAlpha) {
            Row(modifier = Modifier.size(100.percent, 20.px).alignItems(vertical = UiAlign.CENTER)) {
                Box(
                    mode = UiBoxMode.STACK,
                    tags = listOf("color-picker-preview"),
                    modifier = Modifier.size(18.px, 18.px)
                        .checkerboard(4f)
                        .border(1.px, UiColor(0.65f, 0.68f, 0.74f, 1f), 3f)
                        .borderRadius(3f)
                        .clip(true),
                ) {
                    Box(modifier = Modifier.size(100.percent, 100.percent).background(hsv.toUiColor()))
                }
                Box(
                    mode = UiBoxMode.STACK,
                    tags = listOf("color-picker-alpha"),
                    modifier = Modifier.size(0.px, 14.px).grow(1f)
                        .margin(6.px, 0.px, 0.px, 0.px)
                        .checkerboard(4f)
                        .border(1.px, PickerBorderColor, 3f)
                        .borderRadius(3f),
                ) {
                    val opaque = hsv.toUiColor().copy(alpha = 1f)
                    Box(
                        modifier = Modifier.size(100.percent, 100.percent).background(
                            90f,
                            listOf(
                                UiGradientStop(0f, opaque.copy(alpha = 0f)),
                                UiGradientStop(1f, opaque),
                            ),
                        ),
                    )
                    Box(
                        tags = listOf("color-picker-alpha-cursor"),
                        modifier = Modifier.size(6.px, 16.px)
                            .position((hsv.alpha * 100f).percent - 3.px, (-1).px)
                            .background(UiColor.White)
                            .border(1.px, UiColor.Black, 2f)
                            .borderRadius(2f),
                    )
                    PickerInputLayer(UiCursorShape.HAND) { event -> updateAlpha(event, hsv, ::update) }
                }
            }
        }
    }
}

@Composable
private fun PickerInputLayer(cursor: UiCursorShape, update: (UiEvent) -> Unit) {
    Box(
        modifier = Modifier.size(100.percent, 100.percent).cursor(cursor)
            .onPress(update)
            .onDrag { event ->
                update(event)
                event.consume()
            },
    )
}

private fun updateSaturationValue(event: UiEvent, current: HsvColor, update: (HsvColor) -> Unit) {
    update(
        current.copy(
            saturation = (event.localX / event.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            value = (1f - event.localY / event.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        ),
    )
}

private fun updateHue(event: UiEvent, current: HsvColor, update: (HsvColor) -> Unit) {
    update(current.copy(hue = (event.localX / event.width.coerceAtLeast(1f)).coerceIn(0f, 1f)))
}

private fun updateAlpha(event: UiEvent, current: HsvColor, update: (HsvColor) -> Unit) {
    update(current.copy(alpha = (event.localX / event.width.coerceAtLeast(1f)).coerceIn(0f, 1f)))
}

private data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float,
) {
    fun normalized() = HsvColor(
        hue = hue.mod(1f),
        saturation = saturation.coerceIn(0f, 1f),
        value = value.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )

    fun toUiColor(): UiColor {
        val normalized = normalized()
        val scaledHue = normalized.hue * 6f
        val sector = floor(scaledHue).toInt()
        val fraction = scaledHue - floor(scaledHue)
        val p = normalized.value * (1f - normalized.saturation)
        val q = normalized.value * (1f - normalized.saturation * fraction)
        val t = normalized.value * (1f - normalized.saturation * (1f - fraction))
        val (red, green, blue) = when (sector % 6) {
            0 -> Triple(normalized.value, t, p)
            1 -> Triple(q, normalized.value, p)
            2 -> Triple(p, normalized.value, t)
            3 -> Triple(p, q, normalized.value)
            4 -> Triple(t, p, normalized.value)
            else -> Triple(normalized.value, p, q)
        }
        return UiColor(red, green, blue, normalized.alpha)
    }
}

private fun UiColor.toHsv(fallbackHue: Float = 0f): HsvColor {
    val color = clamped()
    val maximum = max(color.red, max(color.green, color.blue))
    val minimum = min(color.red, min(color.green, color.blue))
    val delta = maximum - minimum
    val hue = when {
        delta == 0f -> fallbackHue
        maximum == color.red -> ((color.green - color.blue) / delta).mod(6f) / 6f
        maximum == color.green -> ((color.blue - color.red) / delta + 2f) / 6f
        else -> ((color.red - color.green) / delta + 4f) / 6f
    }
    return HsvColor(hue, if (maximum == 0f) 0f else delta / maximum, maximum, color.alpha).normalized()
}

private fun UiColor.clamped() = UiColor(
    red.coerceIn(0f, 1f),
    green.coerceIn(0f, 1f),
    blue.coerceIn(0f, 1f),
    alpha.coerceIn(0f, 1f),
)

private val HueStops = listOf(
    UiGradientStop(0f, UiColor(1f, 0f, 0f)),
    UiGradientStop(1f / 6f, UiColor(1f, 1f, 0f)),
    UiGradientStop(2f / 6f, UiColor(0f, 1f, 0f)),
    UiGradientStop(3f / 6f, UiColor(0f, 1f, 1f)),
    UiGradientStop(4f / 6f, UiColor(0f, 0f, 1f)),
    UiGradientStop(5f / 6f, UiColor(1f, 0f, 1f)),
    UiGradientStop(1f, UiColor(1f, 0f, 0f)),
)

private val PickerBorderColor = UiColor(0.42f, 0.45f, 0.51f, 1f)
