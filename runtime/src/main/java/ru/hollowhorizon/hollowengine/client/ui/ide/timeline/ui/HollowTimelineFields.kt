package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.CurvePreset
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInterpolation
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.RotationMode
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.utils.lang

internal fun rotationModeLabel(mode: RotationMode): String = when (mode) {
    RotationMode.EULER -> TimelineLang.ROTATION_EULER.lang
    RotationMode.QUATERNION -> TimelineLang.ROTATION_QUATERNION.lang
}

/** The shape of one easing preset, drawn so a curve can be picked by eye rather than by name. */
@Composable
internal fun CurvePreview(preset: CurvePreset) {
    Box(
        id = "curve-preview-${preset.id}",
        modifier = Modifier.size(100.percent, UiLength.Fit).minSize(height = 46.px)
            .background(TimelineColors.Background).border(1.px, TimelineColors.Border, 3f).drawBehind(key = preset.id) {
                val inset = 6f
                val width = (size.width - inset * 2f).coerceAtLeast(1f)
                val height = (size.height - inset * 2f).coerceAtLeast(1f)
                fun pointX(t: Float) = inset + t * width
                fun pointY(v: Float) = inset + (1f - v) * height
                val shape = GenericShape {
                    moveTo(pointX(0f), pointY(0f))
                    when (preset.interpolation) {
                        KeyInterpolation.CONSTANT -> {
                            lineTo(pointX(1f), pointY(0f))
                            lineTo(pointX(1f), pointY(1f))
                        }

                        KeyInterpolation.LINEAR -> lineTo(pointX(1f), pointY(1f))

                        KeyInterpolation.BEZIER -> curveTo(
                            pointX(preset.outX), pointY(preset.outY),
                            pointX(preset.inX), pointY(preset.inY),
                            pointX(1f), pointY(1f),
                        )
                    }
                }
                drawShape(shape, UiPaint.Color(TimelineColors.Accent), UiDrawStyle.Stroke(1.5f))
            },
    )
}
