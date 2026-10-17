package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPath
import ru.hollowhorizon.hollowengine.client.ui.shape.UiShapeSize
import ru.hollowhorizon.hollowengine.client.ui.shape.path
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextFonts
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayout
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter
import ru.hollowhorizon.hollowengine.client.ui.text.caretPosition
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** A smooth wave, one crest or trough per [halfWave], through the middle of its box. */
internal data class WavyUnderlineShape(val halfWave: Float) : Shape {
    override fun createPath(size: UiShapeSize): UiPath = path {
        val middle = size.height / 2f
        moveTo(0f, middle)
        var x = 0f
        var crest = true
        while (x < size.width) {
            val next = min(x + halfWave, size.width)
            quadraticBezierTo((x + next) / 2f, if (crest) 0f else size.height, next, middle)
            crest = !crest
            x = next
        }
    }
}

/**
 * The squiggle's proportions for [fontSize], so it scales with the text. Half-waves are rounded to
 * half a pixel: the stroke mesh is cached per shape, and every zoom step must not mint a new one.
 */
internal class DiagnosticUnderline(fontSize: Float, fontFamily: String?) {
    val halfWave: Float = (fontSize * 0.28f * 2f).roundToInt().coerceAtLeast(4) / 2f
    val amplitude: Float = (fontSize * 0.1f).coerceIn(1f, 3f)
    val thickness: Float = (fontSize / 12f).coerceIn(0.9f, 2f)
    val shape = WavyUnderlineShape(halfWave)

    val middle: Float = UiTextFonts.resolve(fontFamily).underlineY(fontSize)
}

internal fun UiTextDiagnosticSeverity.diagnosticUnderlineColor(): UiColor = when (this) {
    UiTextDiagnosticSeverity.ERROR -> UiColor(1f, 0.33f, 0.33f, 0.9f)
    UiTextDiagnosticSeverity.WARNING -> UiColor(1f, 0.72f, 0.26f, 0.88f)
    UiTextDiagnosticSeverity.INFO -> UiColor(0.38f, 0.66f, 1f, 0.84f)
}

internal fun bucketDiagnosticsByLine(
    lines: List<EditableFieldLine>,
    diagnostics: List<UiTextDiagnostic>,
): Array<List<UiTextDiagnostic>> {
    if (diagnostics.isEmpty()) return Array(lines.size) { emptyList() }
    val buckets = arrayOfNulls<MutableList<UiTextDiagnostic>>(lines.size)
    var lineIndex = 0
    for (diagnostic in diagnostics.sortedBy { it.start }) {
        val end = diagnostic.end.coerceAtLeast(diagnostic.start + 1)
        while (lineIndex < lines.size && lines[lineIndex].end < diagnostic.start) lineIndex++
        var index = lineIndex
        while (index < lines.size && lines[index].start < end) {
            val line = lines[index]
            val start = maxOf(diagnostic.start, line.start)
            val clipped = minOf(end, line.end)
            if (start <= clipped) {
                val bucket = buckets[index] ?: ArrayList<UiTextDiagnostic>().also { buckets[index] = it }
                bucket += diagnostic.copy(start = start - line.start, end = clipped - line.start)
            }
            index++
        }
    }
    return Array(lines.size) { buckets[it] ?: emptyList() }
}

@Composable
internal fun EditableFieldRowDiagnostics(
    line: EditableFieldLine,
    lineLayout: UiTextLayout?,
    rowDiagnostics: List<UiTextDiagnostic>,
    top: Float,
    fontSize: Float,
    fontFamily: String?,
    contentWidth: Float,
) {
    val underline = DiagnosticUnderline(fontSize, fontFamily)
    rowDiagnostics.forEachIndexed { diagnosticIndex, diagnostic ->
        val localStart = diagnostic.start.coerceIn(0, line.text.length)
        val localEnd = diagnostic.end.coerceIn(localStart, line.text.length)
        val rects = selectionRectsForRow(
            line, lineLayout, localStart, localEnd,
            crossesNewline = false,
            fontSize = fontSize,
            fontFamily = fontFamily,
            fullWidth = contentWidth,
        ).ifEmpty {
            val caret = lineLayout?.caretPosition(localStart, fontSize, fontFamily)
            val x = caret?.x ?: UiTextLayouter.measureTextWidth(line.text.take(localStart), fontSize, fontFamily)
            listOf(UiRect(x, caret?.y ?: 0f, 0f, fontSize))
        }
        val color = diagnostic.severity.diagnosticUnderlineColor()
        rects.forEachIndexed { rectIndex, rect ->
            val width = (ceil(rect.width / underline.halfWave) * underline.halfWave)
                .coerceAtLeast(underline.halfWave * 2f)
            val middle = top + rect.y + underline.middle
            key("diag", diagnosticIndex, rectIndex) {
                Box(
                    modifier = Modifier
                        .position(rect.x.px, (middle - underline.amplitude).px)
                        .size(width.px, (underline.amplitude * 2f).px)
                        .shape(
                            underline.shape,
                            fill = UiPaint.None,
                            stroke = UiPaint.Color(color),
                            strokeWidth = underline.thickness.px,
                        ),
                )
            }
        }
    }
}

internal data class EditableFieldDiagnosticTooltip(
    val message: String,
    val severity: UiTextDiagnosticSeverity,
    val x: Float,
    val y: Float,
    val width: Float,
    val fontSize: Float,
)

internal fun editableFieldDiagnosticTooltipAt(
    diagnostics: List<UiTextDiagnostic>,
    layout: EditableFieldLayout,
    pointerX: Float,
    pointerY: Float,
    scrollX: Float,
    scrollY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    contentOffsetX: Float = 0f,
    originX: Float = 0f,
    originY: Float = 0f,
): EditableFieldDiagnosticTooltip? {
    if (diagnostics.isEmpty()) return null
    val contentX = pointerX - contentOffsetX + scrollX
    val contentY = pointerY + scrollY
    if (contentX < 0f || contentY < 0f || contentY > layout.height) return null
    val index = layout.offsetAt(contentX, contentY)
    val diagnostic = diagnostics.firstOrNull { candidate ->
        index in candidate.start until candidate.end.coerceAtLeast(candidate.start + 1)
    } ?: return null
    val message = diagnostic.message.take(220)
    val maxWidth = (viewportWidth - 12f).coerceIn(140f, 420f)
    val measured = UiTextLayouter.measure(
        text = message,
        availableWidth = maxWidth - DiagnosticTooltipHorizontalPadding,
        knownWidth = null,
        wrap = true,
        fontSize = layout.fontSize,
        fontFamily = layout.fontFamily,
    )
    val width = (measured.width + DiagnosticTooltipHorizontalPadding).coerceIn(140f, maxWidth)
    val height = (measured.height + DiagnosticTooltipVerticalPadding)
        .coerceIn(DiagnosticTooltipMinHeight, DiagnosticTooltipMaxHeight)
    val x = (originX + pointerX + 12f).coerceIn(4f, (viewportWidth - width - 4f).coerceAtLeast(4f))
    val y = (originY + pointerY + 16f).coerceIn(4f, (viewportHeight - height - 4f).coerceAtLeast(4f))
    return EditableFieldDiagnosticTooltip(
        message = message,
        severity = diagnostic.severity,
        x = x,
        y = y,
        width = width,
        fontSize = layout.fontSize,
    )
}

/**
 * The message of the diagnostic under the pointer.
 */
@Composable
internal fun EditableFieldDiagnosticTooltipOverlay(tooltip: EditableFieldDiagnosticTooltip, visible: Boolean = true) {
    Popup(
        anchorBounds = UiRect(tooltip.x, tooltip.y, 0f, 0f),
        alignment = UiPopupAlignment(anchorVertical = UiAlign.START),
        layer = 31,
        visible = visible,
        tags = listOf(
            "editable-text-field-diagnostic-tooltip",
            "ide-diagnostic-tooltip",
            tooltip.severity.name.lowercase(),
        ),
        modifier = Modifier.size(tooltip.width.px, UiLength.Auto).inputTransparent(),
        dismissOnOutside = false,
    ) {
        Text(
            tooltip.message,
            tags = listOf("ide-diagnostic-tooltip-message"),
            modifier = Modifier.size(UiLength.Fill, UiLength.Auto)
                .whitespace(UiWhitespace.COLLAPSE)
                .textWrap(true)
                .fontSize(tooltip.fontSize),
        )
    }
}

private const val DiagnosticTooltipHorizontalPadding = 18f
private const val DiagnosticTooltipVerticalPadding = 10f
private const val DiagnosticTooltipMinHeight = 24f
private const val DiagnosticTooltipMaxHeight = 128f
