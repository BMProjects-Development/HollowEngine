package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold

internal fun List<EditableFieldLine>.lineIndex(offset: Int): Int {
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (this[middle].start <= offset) low = middle + 1 else high = middle - 1
    }
    return high.coerceAtLeast(0)
}

data class UiGutterMarker(
    val id: String,
    val offset: Int,
    val icon: String,
    val tooltip: String,
    val tags: List<String> = emptyList(),
    val onClick: (() -> Unit)? = null,
    val tint: UiColor? = null,
)

/** Keep a marker column and a fold column available even between asynchronous analysis results. */
internal class TextFieldGutterCapacity {
    private var markerColumns by mutableIntStateOf(1)

    fun width(markers: List<UiGutterMarker>, lines: List<EditableFieldLine>, fontSize: Float): Float {
        val required = markers.groupingBy { lines.lineIndex(it.offset) }.eachCount().values.maxOrNull() ?: 0
        markerColumns = maxOf(markerColumns, required)
        return (markerColumns + 1) * gutterSlotSize(fontSize)
    }
}

@Composable
internal fun EditableFieldGutter(
    layout: EditableFieldLayout,
    visible: IntRange,
    width: Float,
    color: UiColor,
    state: TextFieldState,
    folds: List<CodeFold>,
    markers: List<UiGutterMarker>,
    fieldId: String,
    separatorColor: UiColor,
) {
    val byLine = remember(layout.lines, markers) { markers.groupBy { layout.lines.lineIndex(it.offset) } }
    val foldLines = remember(layout.lines, folds) { folds.groupBy { layout.lines.lineIndex(it.start) } }
    val slot = gutterSlotSize(layout.fontSize)
    val markerX = editableFieldGutterWidth(state.text, layout.fontSize)
    Box(
        id = "$fieldId-gutter",
        mode = UiBoxMode.STACK,
        tags = listOf("editable-text-field-gutter", "code-editor-gutter"),
        modifier = Modifier.position(0.px, 0.px, 5f).size(width.px, layout.height.px).layer(5)
            .pinnedToViewport(horizontal = true, vertical = false).input(hoverable = true),
    ) {
        Box(
            tags = listOf("gutter-separator"),
            modifier = Modifier.position((width - 1f).px, 0.px).size(1.px, layout.height.px).background(separatorColor)
                .inputTransparent()
        )
        for (index in visible) {
            if (index in layout.hiddenLines) continue
            key(index) {
                val y = layout.lineTop(index)
                Text((index + 1).toString(), modifier = Modifier.position(0.px, y.px).foreground(color).textWrap(false))
                byLine[index].orEmpty().forEachIndexed { column, marker ->
                    GutterButton(
                        "$fieldId-gutter-${marker.id}",
                        marker.icon,
                        marker.tooltip,
                        markerX + column * slot,
                        y,
                        slot,
                        layout.lineHeight,
                        marker.tags,
                        marker.tint,
                        marker.onClick
                    )
                }
                foldLines[index]?.maxByOrNull { it.end - it.start }?.let { fold ->
                    val closed = state.folding.isCollapsed(fold)
                    GutterButton(
                        "$fieldId-fold-${fold.start}",
                        "hollowengine:textures/gui/icons/gutter/fold-${if (closed) "closed" else "open"}.svg",
                        "hollowengine.gui.ide.gutter.${if (closed) "expand" else "collapse"}".lang,
                        width - slot,
                        y,
                        slot,
                        layout.lineHeight,
                        listOf("fold-marker")
                    ) {
                        state.folding.toggle(fold)
                        if (state.folding.isCollapsed(fold) && state.caret > fold.start + fold.prefixLength && state.caret < fold.end) {
                            state.moveCaret(fold.start + fold.prefixLength)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GutterButton(
    id: String, icon: String, tooltip: String, x: Float, y: Float,
    width: Float, height: Float, tags: List<String>, tint: UiColor? = null, action: (() -> Unit)?,
) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOf("gutter-marker") + tags + listOfNotNull("gutter-action".takeIf { action != null }),
        modifier = Modifier.position(x.px, y.px).size(width.px, height.px)
            .input(hoverable = true, clickable = action != null).tooltipOnHover(tooltip)
            .onClick { event -> action?.invoke(); event.consume() }) {
        val size = Modifier.size((width - 4f).px, (width - 4f).px).align(UiAlign.CENTER, UiAlign.CENTER)
        Image(icon, tags = listOf("gutter-marker-icon"), modifier = tint?.let { size.tint(it) } ?: size)
    }
}

private fun gutterSlotSize(fontSize: Float): Float = fontSize + 4f
