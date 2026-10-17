package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.ColorPicker
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.ui.widgets.NumberField
import ru.hollowhorizon.hollowengine.common.vfx.*

/**
 * Color property: color, gradient over time, or a value per channel.
 */
@Composable
fun VfxColorRow(
    label: String,
    value: VfxColorValue,
    property: VfxProperty? = null,
    hint: String? = null,
    onChange: (VfxColorValue) -> Unit,
) {
    val focus = LocalVfxFieldFocus.current
    val driven = property?.let { LocalVfxDriven.current(it) }
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    val current = when (value) {
        is VfxColorValue.Solid -> "solid"
        is VfxColorValue.Gradient -> "gradient"
        is VfxColorValue.Channels -> "channels"
    }

    if (driven != null) {
        val authored = value.constants()
        val shown = VfxRgba(
            if (driven.drives(0)) driven.value(0) else authored[0],
            if (driven.drives(1)) driven.value(1) else authored[1],
            if (driven.drives(2)) driven.value(2) else authored[2],
            if (driven.drives(3)) driven.value(3) else authored[3],
        )
        val recording = driven.recording
        Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
            VfxFieldLabel(label, hint)
            Box(
                modifier = Modifier.size(0.px, CurveCellHeight.px).grow(1f).onPlaced { anchor = it }
                    .input(hoverable = true, clickable = true)
                    .cursor(UiCursorShape.HAND)
                    .tooltipOnHover(vfxText(if (recording) "driven_record_hint" else "driven_hint"))
                    .drawBehind(key = shown) {
                        drawChecker(size.width, size.height)
                        drawRect(UiPaint.Color(shown.toUi()), radius = 3f)
                    }.onClick { event ->
                        open = !open
                        focus(property)
                        event.consume()
                    },
            )
            InspectorIconButton(KeyframeIcon, vfxText("driven_show"), tags = listOf("insp-inline-icon")) { focus(property) }
        }
        if (open) {
            Popup(anchorBounds = anchor, id = "vfx-color-popup", tags = listOf("vfx-popup"), onDismiss = { open = false }) {
                ColorPicker(
                    value = shown.toUi(),
                    onValueChange = { picked ->
                        val was = shown.channels()
                        val now = picked.toRgba().channels()
                        driven.write(now.indices.filter { now[it] != was[it] }.associateWith { now[it] })
                    },
                )
            }
        }
        return
    }

    Column(modifier = Modifier.size(100.percent).gap(2.px)) {
        Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
            VfxFieldLabel(label, hint)
            if (value is VfxColorValue.Channels) {
                Text(
                    vfxText("color_channels"),
                    modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).fontSize(9f).foreground(HintTint),
                )
            } else {
                ColorPalette(
                    value,
                    modifier = Modifier.size(0.px, CurveCellHeight.px).grow(1f).onPlaced { anchor = it },
                ) {
                    open = !open
                    if (property != null) focus(property)
                }
            }
            ColorKindPicker(current, onChange)
        }

        if (value is VfxColorValue.Channels) {
            VfxValueRow("    R", value.r, property, channel = 0) { onChange(value.copy(r = it)) }
            VfxValueRow("    G", value.g, property, channel = 1) { onChange(value.copy(g = it)) }
            VfxValueRow("    B", value.b, property, channel = 2) { onChange(value.copy(b = it)) }
            VfxValueRow("    A", value.a, property, channel = 3) { onChange(value.copy(a = it)) }
        }
    }

    if (!open || value is VfxColorValue.Channels) return
    Popup(
        anchorBounds = anchor,
        id = "vfx-color-popup",
        tags = listOf("vfx-popup"),
        onDismiss = { open = false },
    ) {
        when (value) {
            is VfxColorValue.Solid -> ColorPicker(
                value = value.color.toUi(),
                onValueChange = { onChange(VfxColorValue.Solid(it.toRgba())) },
            )

            is VfxColorValue.Gradient -> GradientEditor(value, onChange)
            is VfxColorValue.Channels -> Unit
        }
    }
}

@Composable
private fun GradientEditor(value: VfxColorValue.Gradient, onChange: (VfxColorValue) -> Unit) {
    var editing by remember { mutableStateOf(0) }

    Column(modifier = Modifier.size(220.px, UiLength.Fit).gap(4.px)) {
        ColorPalette(value, modifier = Modifier.size(100.percent, 14.px)) {}
        Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
            VfxSourcePicker(value.input) { onChange(value.copy(input = it)) }
            InspectorButton(vfxText("stop_add")) {
                val last = value.gradient.stops.lastOrNull()
                val position = ((last?.position ?: 0f) + 0.25f).coerceAtMost(1f)
                val stops = value.gradient.stops + VfxGradientStop(position, last?.color ?: VfxRgba.WHITE)
                onChange(value.copy(gradient = VfxGradient(stops.sortedBy { it.position })))
            }
        }
        value.gradient.stops.forEachIndexed { index, stop ->
            Row(modifier = Modifier.size(100.percent).gap(3.px).alignItems(vertical = UiAlign.CENTER)) {
                Box(
                    id = "vfx-gradient-stop-$index",
                    modifier = Modifier.size(PaletteWidth.px, CurveCellHeight.px)
                        .input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
                        .drawBehind(key = stop.color to (index == editing)) {
                            drawRect(UiPaint.Color(stop.color.toUi()), radius = 3f)
                            if (index == editing) {
                                drawRect(UiRect(0f, size.height - 2f, size.width, 2f), UiPaint.Color(Accent))
                            }
                        }.onClick { event ->
                            editing = index
                            event.consume()
                        },
                )
                Text(vfxText("stop_at"), modifier = Modifier.fontSize(8f).foreground(HintTint))
                NumberField(
                    value = stop.position.toDouble(),
                    modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
                ) { position ->
                    onChange(
                        value.copy(
                            gradient = value.gradient.withStop(
                                index, stop.copy(position = position.toFloat().coerceIn(0f, 1f))
                            )
                        )
                    )
                }
                InspectorIconButton(RemoveIcon, vfxText("key_remove"), tags = listOf("insp-inline-icon")) {
                    onChange(value.copy(gradient = value.gradient.withoutStop(index)))
                }
            }
        }
        value.gradient.stops.getOrNull(editing)?.let { stop ->
            ColorPicker(
                value = stop.color.toUi(),
                onValueChange = { picked ->
                    onChange(
                        value.copy(
                            gradient = value.gradient.withStop(
                                editing, stop.copy(color = picked.toRgba())
                            )
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun ColorPalette(value: VfxColorValue, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .drawBehind(key = value) {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@drawBehind

                drawChecker(width, height)
                when (value) {
                    is VfxColorValue.Solid -> drawRect(UiPaint.Color(value.color.toUi()), radius = 3f)

                    is VfxColorValue.Gradient -> {
                        val steps = 32
                        val columnWidth = width / steps
                        for (step in 0 until steps) {
                            val stop = value.gradient.colorAt(step.toFloat() / (steps - 1))
                            drawRect(
                                UiRect(step * columnWidth, 0f, maxOf(columnWidth, 1f), height),
                                UiPaint.Color(stop.toUi()),
                            )
                        }
                    }

                    is VfxColorValue.Channels -> drawRect(UiPaint.Color(CurveBackground))
                }
            }.onClick { event ->
                onClick()
                event.consume()
            },
    )
}

/** A checkerboard under a color, so its alpha can be seen. */
private fun UiCanvasDrawScope.drawChecker(width: Float, height: Float) {
    val cell = 4f
    var y = 0f
    var row = 0
    while (y < height) {
        var x = 0f
        var column = row
        while (x < width) {
            val color = if (column % 2 == 0) CheckerLight else CheckerDark
            drawRect(UiRect(x, y, minOf(cell, width - x), minOf(cell, height - y)), UiPaint.Color(color))
            x += cell
            column++
        }
        y += cell
        row++
    }
}

@Composable
private fun ColorKindPicker(current: String, onChange: (VfxColorValue) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }

    Box(modifier = Modifier.onPlaced { anchor = it }) {
        InspectorIconButton(colorKindIcon(current), vfxText("color_$current"), tags = listOf("insp-inline-icon")) { open = true }
    }
    if (!open) return

    ContextMenu(
        id = "vfx-color-kind",
        anchorBounds = anchor,
        items = listOf("solid", "gradient", "channels").map { kind ->
            UiDropdownItem(vfxText("color_$kind"), icon = colorKindIcon(kind), checked = kind == current) {
                onChange(
                    when (kind) {
                        "solid" -> VfxColorValue.Solid()
                        "gradient" -> VfxColorValue.Gradient()
                        else -> VfxColorValue.Channels()
                    }
                )
            }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

private fun colorKindIcon(kind: String): String = when (kind) {
    "solid" -> "hollowengine:textures/gui/icons/vfx/color_solid.svg"
    "gradient" -> "hollowengine:textures/gui/icons/vfx/color_gradient.svg"
    else -> "hollowengine:textures/gui/icons/vfx/color_channels.svg"
}

private fun VfxGradient.withStop(index: Int, stop: VfxGradientStop): VfxGradient =
    VfxGradient(stops.toMutableList().also { it[index] = stop }.sortedBy { it.position })

private fun VfxGradient.withoutStop(index: Int): VfxGradient = VfxGradient(stops.filterIndexed { at, _ -> at != index })

internal fun VfxRgba.toUi(): UiColor = UiColor(r, g, b, a)

internal fun UiColor.toRgba(): VfxRgba = VfxRgba(red, green, blue, alpha)

private fun VfxRgba.channels(): FloatArray = floatArrayOf(r, g, b, a)

private const val PaletteWidth = 26f
private const val RemoveIcon = "hollowengine:textures/gui/icons/vfx/remove.svg"
private const val KeyframeIcon = "hollowengine:textures/gui/icons/vfx/key.svg"

private val Accent = UiColor(0.84f, 0.5f, 0.11f, 1f)
private val CheckerLight = UiColor(0.32f, 0.33f, 0.36f, 1f)
private val CheckerDark = UiColor(0.2f, 0.21f, 0.23f, 1f)
