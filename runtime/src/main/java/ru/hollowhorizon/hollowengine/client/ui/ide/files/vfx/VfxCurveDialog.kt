package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.AnimProperty
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.FloatPropertyType
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.EmbeddedTimelineEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineFeatures
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.NumberField
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurve
import ru.hollowhorizon.hollowengine.common.vfx.VfxCurveInput
import ru.hollowhorizon.hollowengine.common.vfx.VfxValue

/**
 * The curve of a value in graph editor.
 */
@Composable
internal fun VfxCurveDialog(value: VfxValue.OverTime, onChange: (VfxValue) -> Unit, onClose: () -> Unit) {
    var current by remember { mutableStateOf(value) }
    val controller = remember { curveController(value) }

    fun publish(next: VfxValue.OverTime) {
        current = next
        onChange(next)
    }

    fun preset(curve: VfxCurve) {
        controller.replaceKeys(curve)
        publish(current.copy(curve = curve))
    }

    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = Centered,
        id = "vfx-curve-dialog",
        tags = listOf("vfx-popup", "vfx-curve-dialog"),
        layer = 100,
        modal = true,
        onDismiss = onClose,
    ) {
        Column(modifier = Modifier.size(DialogWidth.px, DialogHeight.px).gap(6.px)) {
            Row(modifier = Modifier.size(100.percent).gap(4.px).alignItems(vertical = UiAlign.CENTER)) {
                Text(vfxText("curve_title"), tags = listOf("insp-title"), modifier = Modifier.grow(1f).fontSize(10f))
                InspectorButton(vfxText("curve_fade_out")) { preset(VfxCurve.ramp(1f, 0f)) }
                InspectorButton(vfxText("curve_fade_in")) { preset(VfxCurve.ramp(0f, 1f)) }
                InspectorButton(vfxText("curve_flat")) { preset(VfxCurve.flat(1f)) }
                VfxFieldLabel(vfxText("curve_scale"), width = 58f)
                Row(modifier = Modifier.size(56.px)) {
                    NumberField(
                        value = current.scale.toDouble(),
                        modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
                    ) { publish(current.copy(scale = it.toFloat())) }
                }
                VfxSourcePicker(current.input) { input ->
                    controller.workAreaEnd = domainOf(input, current.curve)
                    publish(current.copy(input = input))
                }
                InspectorIconButton(CloseIcon, vfxText("close"), tags = listOf("insp-inline-icon")) { onClose() }
            }
            EmbeddedTimelineEditor(
                controller = controller,
                onChanged = { publish(current.copy(curve = controller.readCurve())) },
                features = CurveFeatures,
                modifier = Modifier.size(100.percent, 0.px).grow(1f),
            )
        }
    }
}

private fun curveController(value: VfxValue.OverTime): TimelineController {
    val controller = TimelineController()
    val name = vfxText("kind_curve")
    val property = AnimProperty(CurvePropertyId, name, FloatPropertyType(name), value.curve.valueAt(0f, 1f))
    controller.addProperty(listOf(name), property)
    controller.replaceKeys(value.curve)
    controller.workAreaEnd = domainOf(value.input, value.curve)
    controller.headerWidth = HeaderWidth
    controller.pixelsPerSecond = (GraphWidth / controller.workAreaEnd).coerceIn(10f, 500f)
    controller.enterCurveView()
    controller.frameCurves()
    return controller
}

private fun domainOf(input: VfxCurveInput, curve: VfxCurve): Float =
    if (input == VfxCurveInput.LIFETIME || input == VfxCurveInput.RANDOM || input == VfxCurveInput.PARENT_RANDOM) 1f else (curve.keys.maxOfOrNull { it.time } ?: 1f).coerceAtLeast(1f)

private fun TimelineController.curve() = allProperties().single().curves.single()

private fun TimelineController.replaceKeys(curve: VfxCurve) {
    val target = curve()
    target.keyframes.clear()
    curve.keys.forEach { key -> target.keyframes.add(key.toEditor()) }
    target.sort()
    clearSelection()
    frameCurves()
}

private fun TimelineController.readCurve(): VfxCurve {
    val target = curve()
    return VfxCurve(target.keyframes.map { key -> key.toStored(target) })
}

private val Centered = UiPopupAlignment(
    anchorHorizontal = UiAlign.CENTER,
    anchorVertical = UiAlign.CENTER,
    popupHorizontal = UiAlign.CENTER,
    popupVertical = UiAlign.CENTER,
)

private val CurveFeatures = TimelineFeatures(capture = false, storage = false, cameraPreview = false, playback = false)

private const val CurvePropertyId = "vfx-curve"
private const val DialogWidth = 780f
private const val DialogHeight = 440f
private const val HeaderWidth = 150f

private const val GraphWidth = DialogWidth - HeaderWidth - 220f - 80f

private const val CloseIcon = "hollowengine:textures/gui/icons/timeline/close.svg"
