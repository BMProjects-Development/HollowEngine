package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.toUiColor
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.scroll.rememberScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.UiTransition
import ru.hollowhorizon.hollowengine.client.ui.style.parseColor
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.generated.Assets
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import kotlin.math.floor

/** Completion popup placement in viewport coordinates (relative to the field's content box). */
internal data class EditableFieldCompletionGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val listHeight: Float,
    val rowHeight: Float,
    val visibleRows: Int,
    val footerHeight: Float,
)

internal fun editableFieldCompletionGeometry(
    layout: EditableFieldLayout,
    anchor: Int,
    items: List<UiTextCompletion>,
    scrollX: Float,
    scrollY: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    caretOffsetX: Float = 0f,
    caretOffsetY: Float = 0f,
    scale: Float = 1f,
): EditableFieldCompletionGeometry? {
    if (items.isEmpty() || viewportWidth <= 0f || viewportHeight <= 0f) return null
    val fontSize = layout.fontSize
    val fontFamily = layout.fontFamily
    val textHeight = UiTextLayouter.measure(
        text = "Mg",
        availableWidth = Float.POSITIVE_INFINITY,
        knownWidth = null,
        wrap = false,
        fontSize = fontSize,
        fontFamily = fontFamily,
    ).height.coerceAtLeast(fontSize)
    val rowHeight = (textHeight + CompletionPopupRowVerticalChrome).coerceAtLeast(14f)
    val footerHeight = (textHeight + CompletionPopupFooterVerticalChrome).coerceAtLeast(rowHeight)
    val caret = layout.caretAt(anchor)
    val caretX = caretOffsetX + (caret.x - scrollX) * scale
    val caretY = caretOffsetY + (caret.y - scrollY) * scale
    val belowY = caretY + textHeight * scale + CompletionPopupAnchorGap
    val belowSpace = viewportHeight - belowY - CompletionPopupViewportMargin
    val aboveSpace = caretY - CompletionPopupAnchorGap - CompletionPopupViewportMargin
    val minimumHeight = popupMinimumHeight(rowHeight, footerHeight)
    val useViewportHeight = belowSpace < minimumHeight && aboveSpace < minimumHeight
    val preferBelow = caretY + textHeight / 2f <= viewportHeight / 2f
    val useBelow = !useViewportHeight && when {
        preferBelow && belowSpace >= minimumHeight -> true
        !preferBelow && aboveSpace >= minimumHeight -> false
        belowSpace >= minimumHeight -> true
        else -> false
    }
    val availableHeight = when {
        useViewportHeight -> viewportHeight - CompletionPopupViewportMargin * 2f
        useBelow -> belowSpace
        else -> aboveSpace
    }.coerceAtLeast(0f)
    val rowsThatFit = floor(
        (availableHeight - footerHeight - CompletionPopupDividerHeight - CompletionPopupVerticalPadding * 2f) /
                rowHeight,
    ).toInt().coerceAtLeast(1)
    val visibleRows = items.size.coerceAtMost(CompletionPopupMaxVisibleRows).coerceAtMost(rowsThatFit)
    val listHeight = rowHeight * visibleRows + CompletionPopupVerticalPadding * 2f
    val height = (listHeight + footerHeight + CompletionPopupDividerHeight)
        .coerceAtMost(availableHeight)
    val measured = items.asSequence().take(CompletionPopupMeasuredItems)
    val labelWidth = measured.maxOfOrNull { item ->
        var width = UiTextLayouter.measureTextWidth(item.label, fontSize, fontFamily)
        if (item.detail.isNotBlank()) {
            width += UiTextLayouter.measureTextWidth(item.detail, fontSize, fontFamily) + CompletionPopupTextGap
        }
        if (item.tail.isNotBlank()) {
            width += UiTextLayouter.measureTextWidth(item.tail, fontSize * CompletionTailScale, fontFamily) +
                CompletionPopupTextGap
        }
        width
    } ?: 0f
    val availableWidth = (viewportWidth - CompletionPopupViewportMargin * 2f).coerceIn(1f, CompletionPopupMaxWidth)
    val minWidth = minOf(CompletionPopupMinWidth, availableWidth)
    val width = (labelWidth + CompletionPopupRowChrome).coerceIn(minWidth, availableWidth)
    val x = caretX.coerceIn(
        CompletionPopupViewportMargin,
        (viewportWidth - width - CompletionPopupViewportMargin).coerceAtLeast(CompletionPopupViewportMargin),
    )
    val y = when {
        useViewportHeight -> CompletionPopupViewportMargin
        useBelow -> belowY
        else -> caretY - height - CompletionPopupAnchorGap
    }
    return EditableFieldCompletionGeometry(x, y, width, height, listHeight, rowHeight, visibleRows, footerHeight)
}

/** The keys that drive the list, one row under it, centered in the height the geometry gave it. */
@Composable
private fun CompletionPopupHint(height: Float) {
    Row(tags = listOf("ide-completion-hint"), modifier = Modifier.size(100.percent, height.px)) {
        Image(Assets.Hollowengine.Textures.Gui.Icons.COMPLETIONS.toString(), tags = listOf("ide-completion-hint-icon"))
        Text(CompletionHintLang.NAVIGATE.lang, modifier = Modifier.textWrap(false))
        Text("Enter", tags = listOf("ide-completion-hint-key"), modifier = Modifier.textWrap(false))
        Text(CompletionHintLang.OR.lang, modifier = Modifier.textWrap(false))
        Text("Tab", tags = listOf("ide-completion-hint-key"), modifier = Modifier.textWrap(false))
        Text(CompletionHintLang.INSERT.lang, modifier = Modifier.textWrap(false))
    }
}

private object CompletionHintLang {
    private const val ROOT = "hollowengine.gui.ide.editor.completion."

    const val NAVIGATE = ROOT + "navigate"
    const val OR = ROOT + "or"
    const val INSERT = ROOT + "insert"
}

private fun popupMinimumHeight(rowHeight: Float, footerHeight: Float): Float =
    rowHeight + footerHeight + CompletionPopupDividerHeight + CompletionPopupVerticalPadding * 2f

/**
 * The completion popup rendered inside the field's scroll container: positioned in viewport space
 * (compensating the field scroll), a windowed row list with its own scroll that follows the
 * keyboard selection, and the navigation hint footer.
 */
@Composable
internal fun EditableFieldCompletionPopup(
    completion: TextFieldCompletionState,
    layout: EditableFieldLayout,
    scrollState: UiScrollHandle,
    contentOffsetX: Float = 0f,
) {
    val items = completion.items
    val field = scrollState.viewport
    val surface = LocalUiViewport.current.takeIf { it.width > 0f && it.height > 0f } ?: field
    val scale = scrollState.screenScale
    val origin = scrollState.toScreen(0f, 0f)
    val geometry = editableFieldCompletionGeometry(
        layout = layout,
        anchor = completion.anchor,
        items = items,
        scrollX = scrollState.offsetX,
        scrollY = scrollState.offsetY,
        viewportWidth = surface.width,
        viewportHeight = surface.height,
        caretOffsetX = contentOffsetX * scale + origin.x - surface.x,
        caretOffsetY = origin.y - surface.y,
        scale = scale,
    ) ?: return

    val listScroll = rememberScrollState()
    val selectionFollow = remember { CompletionSelectionFollow() }
    val selectedIndex = completion.selectedIndex.coerceIn(0, items.lastIndex)
    val selectedChanged = selectionFollow.lastSelectedIndex != selectedIndex
    if (selectedChanged) {
        val scrollIndex = completionScrollRowIndex(items.size, listScroll.offsetY, geometry.rowHeight, geometry.visibleRows)
        val target = completionSelectionRowIndex(items.size, selectedIndex, scrollIndex, geometry.visibleRows) *
                geometry.rowHeight
        if (selectionFollow.lastSelectedIndex >= 0 && listScroll.offsetY != target) listScroll.scrollTo(y = target)
        selectionFollow.lastSelectedIndex = selectedIndex
    }
    val firstIndex = completionWindowStartIndex(
        totalCount = items.size,
        selectedIndex = selectedIndex,
        selectedChanged = selectedChanged,
        scrollOffset = listScroll.offsetY,
        rowHeight = geometry.rowHeight,
        visibleRows = geometry.visibleRows,
    )
    val windowed = items.asSequence()
        .drop(firstIndex)
        .take(CompletionPopupWindowSize)
        .toList()

    Popup(
        anchorBounds = UiRect(surface.x + geometry.x, surface.y + geometry.y, 0f, 0f),
        alignment = UiPopupAlignment(anchorVertical = UiAlign.START),
        id = "editable-text-field-completion",
        tags = listOf("editable-text-field-completion-popup", "ide-completion-popup"),
        modifier = Modifier.size(geometry.width.px, geometry.height.px).fontSize(layout.fontSize)
            .let { base -> layout.fontFamily?.let { base.fontFamily(it) } ?: base }
            .input(clickable = true, hoverable = true),
        dismissOnOutside = false,
    ) {
        Column(
            tags = listOf("ide-completion-list"),
            modifier = Modifier.size(100.percent, geometry.listHeight.px)
                .scrollable(state = listScroll),
        ) {
            if (firstIndex > 0) {
                Box(modifier = Modifier.size(100.percent, (firstIndex * geometry.rowHeight).px))
            }
            windowed.forEachIndexed { offset, item ->
                key(firstIndex + offset) {
                    CompletionPopupRow(
                        completion = completion,
                        index = firstIndex + offset,
                        item = item,
                        selected = firstIndex + offset == selectedIndex,
                        rowHeight = geometry.rowHeight,
                    )
                }
            }
            val remaining = items.size - firstIndex - windowed.size
            if (remaining > 0) {
                Box(modifier = Modifier.size(100.percent, (remaining * geometry.rowHeight).px))
            }
        }
        Box(tags = listOf("ide-completion-divider"), modifier = Modifier.size(UiLength.Fill, 1.px))
        CompletionPopupHint(geometry.footerHeight)
    }
}

@Composable
private fun CompletionPopupRow(
    completion: TextFieldCompletionState,
    index: Int,
    item: UiTextCompletion,
    selected: Boolean,
    rowHeight: Float,
) {
    var hovered by remember { mutableStateOf(false) }
    val active = selected || hovered
    val fadeColor = when {
        selected && hovered -> CompletionSelectedHoverBg
        selected -> CompletionSelectedBg
        hovered -> CompletionHoverBg
        else -> CompletionPopupBg
    }
    Row(
        tags = listOf("ide-completion-row", if (selected) "selected" else "idle"),
        modifier = Modifier.size(100.percent, rowHeight.px)
            .input(clickable = true, hoverable = true)
            .onEnter { hovered = true }
            .onExit { hovered = false }
            .onClick { event ->
                completion.accept(index)
                event.consume()
            },
    ) {
        if (item.itemIcon != null) {
            Item(item.itemIcon, tags = listOf("ide-completion-item"))
        } else {
            item.icon?.let { icon ->
                Image(icon, tags = listOf("ide-completion-icon"))
            }
        }
        CompletionRowContent(item.label, item.detail, item.tail, item.matchRanges, active, fadeColor)
    }
}

@Composable
private fun CompletionRowContent(
    label: String,
    detail: String,
    tail: String,
    matchRanges: List<IntRange>,
    active: Boolean,
    fadeColor: UiColor,
) {
    var viewportWidth by remember { mutableStateOf(0f) }
    var leadingWidth by remember { mutableStateOf(0f) }
    var tailWidth by remember { mutableStateOf(0f) }
    val hasTail = tail.isNotBlank()
    val inlineTail = hasTail && !completionTailFitsSeparately(viewportWidth, leadingWidth, tailWidth)
    val textWidth = leadingWidth + if (inlineTail) CompletionTailGap + tailWidth else 0f
    val overflow = (textWidth - viewportWidth).coerceAtLeast(0f)
    val marquee = active && overflow > 0.5f
    val travelMs = (overflow * CompletionMarqueeMsPerPixel).toLong().coerceIn(700L, 6000L)
    var atEnd by remember { mutableStateOf(false) }
    LaunchedEffect(marquee, travelMs) {
        atEnd = false
        if (!marquee) return@LaunchedEffect
        val cycleMs = 2L * CompletionMarqueePauseMs + 2L * travelMs
        var baseNanos = -1L
        while (isActive) {
            withFrameNanos { now ->
                if (baseNanos < 0L) baseNanos = now
                val elapsed = (now - baseNanos) / 1_000_000L % cycleMs
                atEnd = elapsed in CompletionMarqueePauseMs until CompletionMarqueePauseMs + travelMs + CompletionMarqueePauseMs
            }
        }
    }
    val shift = if (atEnd) overflow else 0f
    Box(
        tags = listOf("ide-completion-main"),
        modifier = Modifier.grow(1f).clip().onPlaced { viewportWidth = it.width },
    ) {
        Row(
            tags = listOf("ide-completion-text"),
            modifier = Modifier
                .gap(CompletionTailGap.px)
                .translate(x = -shift)
                .transition(UiTransition("translate", durationMillis = travelMs))
                .clip(false),
        ) {
            Row(
                modifier = Modifier.onPlaced { leadingWidth = it.width }.clip(false),
            ) {
                CompletionLabel(label, matchRanges)
                if (detail.isNotBlank()) {
                    CompletionDetail(detail, active)
                }
            }
            if (inlineTail) {
                CompletionTail(tail, UiAlign.START) { tailWidth = it }
            }
        }
        if (hasTail && !inlineTail) {
            CompletionTail(tail, UiAlign.END) { tailWidth = it }
        }
        if (overflow > 0.5f) {
            Box(
                tags = listOf("ide-completion-fade"),
                modifier = Modifier.size(CompletionFadeWidth.px, 100.percent)
                    .align(UiAlign.END, UiAlign.CENTER)
                    .position(CompletionFadeOvershoot.px, 0.px)
                    .opacity(if (atEnd) 0f else 1f)
                    .transition(UiTransition("opacity", durationMillis = CompletionFadeDurationMillis))
                    .background(90f, completionFadeStops(fadeColor)),
            )
            Box(
                tags = listOf("ide-completion-fade"),
                modifier = Modifier.size(CompletionFadeWidth.px, 100.percent)
                    .align(UiAlign.START, UiAlign.CENTER)
                    .position((-CompletionFadeOvershoot).px, 0.px)
                    .opacity(if (atEnd) 1f else 0f)
                    .transition(UiTransition("opacity", durationMillis = CompletionFadeDurationMillis))
                    .background(270f, completionFadeStops(fadeColor)),
            )
        }
    }
}

@Composable
private fun CompletionLabel(label: String, matchRanges: List<IntRange>) {
    val ranges = matchRanges.asSequence()
        .mapNotNull { range ->
            val start = range.first.coerceIn(0, label.length)
            val end = (range.last + 1).coerceIn(start, label.length)
            if (start == end) null else start until end
        }
        .sortedBy { it.first }
        .toList()
    Text(tags = listOf("ide-completion-label"), modifier = Modifier.textWrap(false)) {
        var cursor = 0
        for (range in ranges) {
            if (cursor < range.first) Span(label.substring(cursor, range.first))
            Span(label.substring(range.first, range.last + 1), modifier = Modifier.foreground(CompletionMatchColor))
            cursor = range.last + 1
        }
        if (cursor < label.length) Span(label.substring(cursor))
    }
}

@Composable
private fun CompletionTail(tail: String, horizontalAlign: UiAlign, onWidthChanged: (Float) -> Unit) {
    Text(
        tail,
        tags = listOf("ide-completion-tail"),
        modifier = Modifier.align(horizontalAlign, UiAlign.CENTER).fontScale(CompletionTailScale)
            .onPlaced { onWidthChanged(it.width) },
    )
}

internal fun completionTailFitsSeparately(viewportWidth: Float, leadingWidth: Float, tailWidth: Float): Boolean {
    if (viewportWidth <= 0f || tailWidth <= 0f) return true
    return leadingWidth + CompletionTailGap + tailWidth <= viewportWidth + 0.5f
}

@Composable
private fun CompletionDetail(detail: String, active: Boolean) {
    val factor = if (active) 1f else 0.72f
    Row(
        tags = listOf("ide-completion-detail"),
        modifier = Modifier.textWrap(false).whitespace(UiWhitespace.PRESERVE).clip(false),
    ) {
        for (segment in completionDetailSegments(detail)) {
            Text(
                segment.text,
                tags = listOf("ide-completion-detail-seg"),
                modifier = Modifier
                    .foreground(segment.color.copy(alpha = segment.color.alpha * factor))
                    .textWrap(false),
            )
        }
    }
}

private data class CompletionSegment(val text: String, val color: UiColor)

private val CompletionTokenRegex = Regex("""[A-Za-z_][A-Za-z0-9_]*|\s+|[^A-Za-z0-9_\s]""")

private fun completionDetailSegments(detail: String): List<CompletionSegment> {
    val result = ArrayList<CompletionSegment>()
    for (match in CompletionTokenRegex.findAll(detail)) {
        val token = match.value
        val first = token.first()
        val color = when {
            first.isWhitespace() -> CompletionPunctuationColor
            first.isLetter() && first.isUpperCase() -> CompletionTypeColor
            first.isLetter() || first == '_' -> CompletionNameColor
            else -> CompletionPunctuationColor
        }
        val last = result.lastOrNull()
        if (last != null && last.color == color) {
            result[result.lastIndex] = last.copy(text = last.text + token)
        } else {
            result += CompletionSegment(token, color)
        }
    }
    return result
}

private val CompletionTypeColor = TokenType.CLASS.toUiColor()
private val CompletionNameColor = TokenType.VALUE_ARGUMENT_NAME.toUiColor()
private val CompletionPunctuationColor = parseColor("#6E7686")
private val CompletionMatchColor = parseColor("#6CB6FF")

private const val CompletionFadeWidth = 16f
private const val CompletionFadeDurationMillis = 300L
private const val CompletionFadeOvershoot = 2f
/** The row colors of .ide-completion-row, flattened onto .ide-completion-popup, for the overflow fades. */
private val CompletionPopupBg = parseColor("#26282E")
private val CompletionSelectedBg = UiColor(110f / 255f, 155f / 255f, 220f / 255f, 0.24f).over(CompletionPopupBg)
private val CompletionSelectedHoverBg = UiColor(110f / 255f, 155f / 255f, 220f / 255f, 0.30f).over(CompletionPopupBg)
private val CompletionHoverBg = UiColor(1f, 1f, 1f, 0.05f).over(CompletionPopupBg)

private fun completionFadeStops(color: UiColor) = listOf(
    UiGradientStop(0f, color.copy(alpha = 0f)),
    UiGradientStop(0.82f, color),
    UiGradientStop(1f, color),
)

private fun UiColor.over(base: UiColor): UiColor {
    val a = alpha
    return UiColor(red * a + base.red * (1f - a), green * a + base.green * (1f - a), blue * a + base.blue * (1f - a), 1f)
}

private const val CompletionMarqueePauseMs = 900L
private const val CompletionMarqueeMsPerPixel = 16f
private const val CompletionTailGap = 6f

private class CompletionSelectionFollow {
    var lastSelectedIndex = -1
}

/** First row materialized by the windowed list (overscan rows above the scrolled/selected row). */
internal fun completionWindowStartIndex(
    totalCount: Int,
    selectedIndex: Int,
    selectedChanged: Boolean,
    scrollOffset: Float,
    rowHeight: Float,
    visibleRows: Int,
): Int {
    if (totalCount <= CompletionPopupWindowSize) return 0
    val scrollIndex = completionScrollRowIndex(totalCount, scrollOffset, rowHeight, visibleRows)
    val virtualStart = if (selectedChanged) {
        completionSelectionRowIndex(totalCount, selectedIndex, scrollIndex, visibleRows) - CompletionPopupOverscanRows
    } else {
        scrollIndex - CompletionPopupOverscanRows
    }
    return virtualStart.coerceIn(0, totalCount - CompletionPopupWindowSize)
}

/** The row index the list is currently scrolled to. */
internal fun completionScrollRowIndex(
    totalCount: Int,
    scrollOffset: Float,
    rowHeight: Float,
    visibleRows: Int,
): Int {
    val rows = visibleRows.coerceAtLeast(1)
    val maxScrollIndex = (totalCount - rows).coerceAtLeast(0)
    return (scrollOffset / rowHeight.coerceAtLeast(1f)).toInt().coerceIn(0, maxScrollIndex)
}

/** The scroll row that keeps [selectedIndex] visible, moving as little as possible. */
internal fun completionSelectionRowIndex(
    totalCount: Int,
    selectedIndex: Int,
    scrollIndex: Int,
    visibleRows: Int,
): Int {
    val rows = visibleRows.coerceAtLeast(1)
    val maxScrollIndex = (totalCount - rows).coerceAtLeast(0)
    return when {
        selectedIndex < scrollIndex -> selectedIndex
        selectedIndex >= scrollIndex + rows -> selectedIndex - rows + 1
        else -> scrollIndex
    }.coerceIn(0, maxScrollIndex)
}

private const val CompletionPopupMaxVisibleRows = 10
private const val CompletionPopupVerticalPadding = 3f
private const val CompletionPopupRowVerticalChrome = 6f
private const val CompletionPopupFooterVerticalChrome = 8f
private const val CompletionPopupDividerHeight = 1f
private const val CompletionPopupAnchorGap = 4f
private const val CompletionPopupViewportMargin = 4f
private const val CompletionPopupMinWidth = 160f
private const val CompletionPopupMeasuredItems = 128
private const val CompletionPopupWindowSize = 48
private const val CompletionPopupOverscanRows = 12
private const val CompletionPopupTextGap = 8f
private const val CompletionPopupRowChrome = 52f
private const val CompletionPopupMaxWidth = 560f

/** The package tail reads a step smaller than the label; measured and drawn at the same scale. */
private const val CompletionTailScale = 0.85f
