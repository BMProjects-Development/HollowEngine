package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.style.UiCaretBlinkKeyframes
import ru.hollowhorizon.hollowengine.client.ui.style.UiCaretBlinkPeriodMillis
import ru.hollowhorizon.hollowengine.client.ui.style.UiComputedStyle
import ru.hollowhorizon.hollowengine.client.ui.style.margin
import ru.hollowhorizon.hollowengine.client.ui.text.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold
import kotlin.math.abs

/** One logical line of the field's text: its offset in the document and its text (no newline). */
internal data class EditableFieldLine(val start: Int, val text: String) {
    val end: Int get() = start + text.length
}

internal fun editableFieldLines(text: String): List<EditableFieldLine> {
    val lines = ArrayList<EditableFieldLine>()
    var start = 0
    while (true) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) {
            lines += EditableFieldLine(start, text.substring(start))
            return lines
        }
        lines += EditableFieldLine(start, text.substring(start, newline))
        start = newline + 1
    }
}

internal class EditableFieldLayout(
    val lines: List<EditableFieldLine>,
    private val offsets: FloatArray, // offsets[i] = top of line i; offsets[lines.size] = bottom
    val lineLayouts: Array<UiTextLayout?>,
    val fontSize: Float,
    val fontFamily: String?,
    val contentWidth: Float,
    val naturalWidth: Float,
    /** Widget ids are line-local, allowing unchanged line layouts to be reused after edits. */
    val inlayHints: Array<Map<String, UiInlayHint>> = Array(lines.size) { emptyMap() },
    /** Measurement revision the reservations were made with; see [EditableFieldInlayMetrics]. */
    internal val inlayRevision: Long = 0L,
    private val inlayOffsets: Set<Int> = emptySet(),
    internal val layoutWidth: Float = Float.POSITIVE_INFINITY,
    internal val lineInputs: Array<EditableFieldLineInput?> = arrayOfNulls(lines.size),
    /** Multi-line editors keep one line of overscroll past the last row; single-line fields don't. */
    private val verticalOverscroll: Boolean = true,
    /** Height of one unwrapped line in the field's font; the caret spans it. */
    val lineHeight: Float = fontSize,
    val hiddenLines: Set<Int> = emptySet(),
) {
    private val trailingMargin = if (verticalOverscroll) fontSize else 0f
    val height: Float get() = (offsets.lastOrNull() ?: 0f) + trailingMargin

    fun lineTop(index: Int): Float = offsets[index]

    private fun lineAtY(y: Float): Int {
        if (lines.isEmpty()) return 0
        var lo = 0
        var hi = lines.size - 1
        var result = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid + 1] > y) {
                result = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        while (result in hiddenLines && result > 0) result--
        return result.coerceIn(0, lines.size - 1)
    }

    /**
     * The inclusive index band whose rows fall within the scrolled viewport (plus overscan),
     * snapped outwards to a block boundary.
     */
    fun visibleRange(scrollY: Float, viewportHeight: Float, overscan: Float): IntRange {
        if (lines.isEmpty()) return IntRange.EMPTY
        val first = lineAtY(scrollY - overscan).floorToBlock()
        val last = lineAtY(scrollY + viewportHeight + overscan).ceilToBlock()
        return first..last.coerceAtMost(lines.lastIndex)
    }

    private fun Int.floorToBlock() = this - this % EditableFieldRowBlock

    private fun Int.ceilToBlock() = this + (EditableFieldRowBlock - 1 - this % EditableFieldRowBlock)

    /** Content-space point -> document offset. */
    fun offsetAt(x: Float, y: Float): Int = caretHitAt(x, y).offset

    fun caretHitAt(x: Float, y: Float): EditableFieldCaretHit {
        if (lines.isEmpty()) return EditableFieldCaretHit(0)
        val index = lineAtY(y)
        val line = lines[index]
        val local = lineLayouts[index]
        val localY = y - offsets[index]
        local?.inlayCaretHitAt(x, localY)?.let { hit ->
            return hit.copy(offset = line.start + hit.offset)
        }
        val column = local?.caretIndexAt(x, localY, fontSize, fontFamily)?.coerceIn(0, line.text.length)
            ?: nearestColumn(line.text, x, fontSize, fontFamily)
        return EditableFieldCaretHit(line.start + column)
    }

    /** Document offset -> content-space caret top-left. */
    fun caretAt(offset: Int, inlayAffinity: UiInlayCaretAffinity = UiInlayCaretAffinity.AFTER): UiVec3 {
        if (lines.isEmpty()) return UiVec3()
        var index = lines.lineIndex(offset).coerceIn(0, lines.size - 1)
        while (index in hiddenLines && index > 0) index--
        val line = lines[index]
        val column = (offset - line.start).coerceIn(0, line.text.length)
        val local = lineLayouts[index]
        return if (local != null) {
            val p = if (inlayAffinity == UiInlayCaretAffinity.BEFORE) {
                local.caretPositionBeforeInlay(column) ?: local.caretPosition(column, fontSize, fontFamily)
            } else {
                local.caretPosition(column, fontSize, fontFamily)
            }
            UiVec3(p.x, offsets[index] + p.y)
        } else {
            UiVec3(UiTextLayouter.measureTextWidth(line.text.take(column), fontSize, fontFamily), offsets[index])
        }
    }

    fun hasInlayAt(offset: Int): Boolean = offset in inlayOffsets

    fun visualCaretMove(offset: Int, delta: Int): Int {
        if (delta == 0 || lines.isEmpty()) return offset.coerceIn(0, lines.last().end)
        val visualLines = visualLines()
        if (visualLines.isEmpty()) return offset.coerceIn(0, lines.last().end)
        val currentIndex = visualLines.lineIndexAtCaret(offset)
        val current = visualLines[currentIndex]
        val targetIndex = (currentIndex + delta).coerceIn(0, visualLines.lastIndex)
        val target = visualLines[targetIndex]
        if (targetIndex == currentIndex) return if (delta < 0) target.start else target.end
        return target.offsetNearestX(current.xAt(offset, fontSize, fontFamily), fontSize, fontFamily)
    }

    private fun visualLines(): List<EditableFieldVisualLine> = buildList {
        for (lineIndex in lines.indices) {
            if (lineIndex in hiddenLines) continue
            val line = lines[lineIndex]
            val layout = lineLayouts[lineIndex]
            if (layout == null || layout.lines.isEmpty()) {
                add(EditableFieldVisualLine.Plain(line.start, line.end, line.text, 0f))
            } else {
                layout.lines.forEach { visual ->
                    add(
                        EditableFieldVisualLine.Rich(
                            start = line.start + visual.sourceStart,
                            end = line.start + visual.sourceStart + visual.sourceLength,
                            lineStart = line.start,
                            layout = layout,
                            visual = visual,
                        ),
                    )
                }
            }
        }
    }
}

internal data class EditableFieldCaretHit(
    val offset: Int,
    val inlayAffinity: UiInlayCaretAffinity = UiInlayCaretAffinity.AFTER,
)

private fun UiTextLayout.inlayCaretHitAt(x: Float, y: Float): EditableFieldCaretHit? {
    val visual = lines.minByOrNull { line ->
        when {
            y < line.y -> line.y - y
            y > line.y + line.height -> y - (line.y + line.height)
            else -> 0f
        }
    } ?: return null
    var sourceOffset = visual.sourceStart
    for (fragment in visual.fragments) {
        when (fragment) {
            is UiInlineWidgetRun -> {
                if (x >= visual.x + fragment.x && x <= visual.x + fragment.x + fragment.width) {
                    return if (x < visual.x + fragment.x + fragment.width / 2f) {
                        EditableFieldCaretHit(sourceOffset, UiInlayCaretAffinity.BEFORE)
                    } else {
                        EditableFieldCaretHit(sourceOffset + fragment.widget.sourceLength, UiInlayCaretAffinity.AFTER)
                    }
                }
                sourceOffset += fragment.widget.sourceLength
            }

            is UiInlineImageRun -> sourceOffset += fragment.image.alt.ifBlank { "\uFFFC" }.length
            is UiTextRun -> sourceOffset += fragment.text.length
            is UiTextSpaceRun -> sourceOffset++
        }
    }
    return null
}

private fun UiTextLayout.caretPositionBeforeInlay(offset: Int): UiVec3? {
    for (visual in lines) {
        var sourceOffset = visual.sourceStart
        for (fragment in visual.fragments) {
            when (fragment) {
                is UiInlineWidgetRun -> {
                    if (fragment.widget.sourceLength == 0 && sourceOffset == offset) {
                        return UiVec3(visual.x + fragment.x, visual.y)
                    }
                    sourceOffset += fragment.widget.sourceLength
                }

                is UiInlineImageRun -> sourceOffset += fragment.image.alt.ifBlank { "\uFFFC" }.length
                is UiTextRun -> sourceOffset += fragment.text.length
                is UiTextSpaceRun -> sourceOffset++
            }
        }
    }
    return null
}

private sealed class EditableFieldVisualLine(
    val start: Int,
    val end: Int,
) {
    abstract fun xAt(offset: Int, fontSize: Float, fontFamily: String?): Float

    abstract fun offsetNearestX(targetX: Float, fontSize: Float, fontFamily: String?): Int

    class Plain(
        start: Int,
        end: Int,
        private val text: String,
        private val x: Float,
    ) : EditableFieldVisualLine(start, end) {
        override fun xAt(offset: Int, fontSize: Float, fontFamily: String?): Float {
            val column = (offset - start).coerceIn(0, text.length)
            return x + UiTextLayouter.measureTextWidth(text.take(column), fontSize, fontFamily)
        }

        override fun offsetNearestX(targetX: Float, fontSize: Float, fontFamily: String?): Int {
            var bestOffset = 0
            var bestDistance = Float.POSITIVE_INFINITY
            for (offset in 0..(end - start).coerceAtLeast(0)) {
                val distance =
                    abs(x + UiTextLayouter.measureTextWidth(text.take(offset), fontSize, fontFamily) - targetX)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestOffset = offset
                }
            }
            return start + bestOffset
        }
    }

    class Rich(
        start: Int,
        end: Int,
        private val lineStart: Int,
        private val layout: UiTextLayout,
        private val visual: UiTextLine,
    ) : EditableFieldVisualLine(start, end) {
        override fun xAt(offset: Int, fontSize: Float, fontFamily: String?): Float {
            val localOffset = (offset - lineStart).coerceIn(0, visual.sourceStart + visual.sourceLength)
            return layout.caretPosition(localOffset, fontSize, fontFamily).x
        }

        override fun offsetNearestX(targetX: Float, fontSize: Float, fontFamily: String?): Int {
            val y = visual.y + visual.height / 2f
            return lineStart + layout.caretIndexAt(targetX, y, fontSize, fontFamily)
        }
    }
}

private fun List<EditableFieldVisualLine>.lineIndexAtCaret(offset: Int): Int {
    val target = offset.coerceAtLeast(0)
    indexOfFirst { it.start == target && it.end == target }.takeIf { it >= 0 }?.let { return it }
    indexOfFirst { it.start == target && it.end > it.start }.takeIf { it >= 0 }?.let { return it }
    indexOfFirst { it.start < target && target <= it.end }.takeIf { it >= 0 }?.let { return it }
    return indexOfLast { it.start <= target }.coerceIn(0, lastIndex)
}

internal data class EditableFieldLineInput(
    val text: String,
    val highlights: List<UiTextHighlight>,
    val inlays: List<UiInlayHint>,
)

internal fun computeEditableFieldLayout(
    text: String,
    fontSize: Float,
    fontFamily: String?,
    wrap: Boolean,
    viewportWidth: Float,
    highlights: List<UiTextHighlight> = emptyList(),
    inlayHints: List<UiInlayHint> = emptyList(),
    inlayMetrics: EditableFieldInlayMetrics = EditableFieldInlayMetrics(),
    previous: EditableFieldLayout? = null,
    multiline: Boolean = true,
    hiddenLines: Set<Int> = emptySet(),
    projectedLines: List<EditableFieldLine>? = null,
): EditableFieldLayout {
    val lines = projectedLines ?: editableFieldLines(text)
    val offsets = FloatArray(lines.size + 1)
    val layouts = arrayOfNulls<UiTextLayout>(lines.size)
    val lineInputs = arrayOfNulls<EditableFieldLineInput>(lines.size)
    val hintsById = Array<Map<String, UiInlayHint>>(lines.size) { emptyMap() }
    val uniformHeight = UiTextLayouter.layout(
        "X", Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, false, UiTextAlign.LEFT, fontSize, fontFamily,
        preserveWhitespace = true,
    ).height
    val wrapping = wrap && viewportWidth > 0f
    val layoutWidth = if (wrapping) (viewportWidth - TextFieldCaretWidth).coerceAtLeast(1f) else Float.POSITIVE_INFINITY
    val highlightBuckets = bucketHighlightsByLine(lines, highlights)
    val inlayBuckets = bucketInlaysByLine(lines, inlayHints)
    val inlayRevision = inlayMetrics.revision
    val reusable = previous?.takeIf {
        it.fontSize == fontSize && it.fontFamily == fontFamily && it.layoutWidth == layoutWidth && it.inlayRevision == inlayRevision
    }?.reusableLineLayouts()
    val deferPlainLines = !wrapping && highlights.isEmpty() && inlayHints.isEmpty()
    var maxWidth = 0f
    var y = 0f

    for (index in lines.indices) {
        val line = lines[index]
        offsets[index] = y
        if (index in hiddenLines) continue

        val localHighlights = highlightBuckets[index]
        val localInlays = inlayBuckets[index]
        if (localInlays.isNotEmpty()) hintsById[index] = buildMap(localInlays.size) {
            localInlays.forEachIndexed { hintIndex, hint -> put(textFieldInlayWidgetId(hint, hintIndex), hint) }
        }

        val input = EditableFieldLineInput(line.text, localHighlights, localInlays)
        lineInputs[index] = input
        val layout = if (deferPlainLines) null else reusable?.get(input) ?: run {
            val richText = line.text.toHighlightedRichText(
                highlights = localHighlights,
                inlayHints = localInlays,
                inlayWidgetMetrics = inlayMetrics.of(localInlays),
            )
            UiTextLayouter.layout(
                richText,
                layoutWidth,
                Float.POSITIVE_INFINITY,
                wrapping,
                UiTextAlign.LEFT,
                fontSize,
                fontFamily,
                preserveWhitespace = true,
            )
        }
        layouts[index] = layout
        maxWidth = maxOf(
            maxWidth,
            layout?.maxNaturalLineWidth ?: UiTextLayouter.measureTextWidth(line.text, fontSize, fontFamily),
        )
        y += maxOf(layout?.height ?: uniformHeight, uniformHeight)
    }

    offsets[lines.size] = y
    val trailingWidth = if (multiline) fontSize else TextFieldCaretWidth
    val naturalWidth = if (wrapping) viewportWidth else maxWidth + trailingWidth
    val contentWidth = maxOf(naturalWidth, viewportWidth)
    return EditableFieldLayout(
        lines,
        offsets,
        layouts,
        fontSize,
        fontFamily,
        contentWidth,
        naturalWidth,
        hintsById,
        inlayRevision,
        inlayHints.filter { it.sourceLength == 0 }.mapTo(HashSet(inlayHints.size)) { it.offset },
        layoutWidth,
        lineInputs,
        verticalOverscroll = multiline,
        lineHeight = uniformHeight,
        hiddenLines = hiddenLines,
    )
}

private fun EditableFieldLayout.reusableLineLayouts(): Map<EditableFieldLineInput, UiTextLayout> {
    val map = HashMap<EditableFieldLineInput, UiTextLayout>(lineInputs.size * 2)
    for (index in lineInputs.indices) {
        val input = lineInputs[index] ?: continue
        val layout = lineLayouts[index] ?: continue
        map.putIfAbsent(input, layout)
    }
    return map
}

private fun bucketHighlightsByLine(
    lines: List<EditableFieldLine>,
    highlights: List<UiTextHighlight>,
): Array<List<UiTextHighlight>> {
    val buckets = arrayOfNulls<MutableList<UiTextHighlight>>(lines.size)
    // Highlights are sorted by start, so a single forward pass distributes them.
    var lineIndex = 0
    for (highlight in highlights) {
        while (lineIndex < lines.size && lines[lineIndex].end < highlight.start) lineIndex++
        var index = lineIndex
        while (index < lines.size && lines[index].start < highlight.end) {
            val line = lines[index]
            val start = maxOf(highlight.start, line.start)
            val end = minOf(highlight.end, line.end)
            if (start < end) {
                val bucket = buckets[index] ?: ArrayList<UiTextHighlight>().also { buckets[index] = it }
                bucket += highlight.copy(start = start - line.start, end = end - line.start)
            }
            index++
        }
    }
    return Array(lines.size) { buckets[it] ?: emptyList() }
}

private fun bucketInlaysByLine(
    lines: List<EditableFieldLine>,
    inlayHints: List<UiInlayHint>,
): Array<List<UiInlayHint>> {
    if (inlayHints.isEmpty()) return Array(lines.size) { emptyList() }
    val buckets = arrayOfNulls<MutableList<UiInlayHint>>(lines.size)
    var lineIndex = 0
    for (hint in inlayHints.sortedBy { it.offset }) {
        while (lineIndex < lines.size && lines[lineIndex].end < hint.offset) lineIndex++
        if (lineIndex == lines.size) break
        val line = lines[lineIndex]
        if (hint.offset >= line.start) {
            val bucket = buckets[lineIndex] ?: ArrayList<UiInlayHint>().also { buckets[lineIndex] = it }
            bucket += hint.copy(offset = hint.offset - line.start)
        }
    }
    return Array(lines.size) { buckets[it] ?: emptyList() }
}

internal class EditableFieldInlayMetrics {
    private val sizes = HashMap<Int, UiInlineWidgetMetrics>()

    /** Bumped whenever a measurement changed, so layouts built with stale sizes are dropped. */
    var revision: Long by mutableStateOf(0L)
        private set

    operator fun get(hint: UiInlayHint): UiInlineWidgetMetrics? = sizes[hint.contentKey]

    /** Reserved footprints of [inlays], by widget id; unmeasured hints reserve nothing. */
    fun of(inlays: List<UiInlayHint>): Map<String, UiInlineWidgetMetrics> {
        if (inlays.isEmpty()) return emptyMap()
        val metrics = HashMap<String, UiInlineWidgetMetrics>(inlays.size)
        inlays.forEachIndexed { index, hint ->
            metrics[textFieldInlayWidgetId(hint, index)] = get(hint) ?: UiInlineWidgetMetrics(0f, 0f)
        }
        return metrics
    }

    /** Records a drawn inlay's size; an unchanged size does not disturb the layout. */
    fun record(hint: UiInlayHint, width: Float, height: Float) {
        val measured = UiInlineWidgetMetrics(width, height)
        if (sizes.put(hint.contentKey, measured) == measured) return
        revision++
    }
}

/** Nearest caret column to [x] within [text] (prefix-width scan, non-wrapped lines). */
private fun nearestColumn(text: String, x: Float, fontSize: Float, fontFamily: String?): Int {
    var best = 0
    var bestDistance = Float.POSITIVE_INFINITY
    for (column in 0..text.length) {
        val distance = abs(UiTextLayouter.measureTextWidth(text.take(column), fontSize, fontFamily) - x)
        if (distance < bestDistance) {
            bestDistance = distance
            best = column
        }
    }
    return best
}

/**
 * A text field measured and rendered with one consistent layout ([EditableFieldLayout]): a single
 * sized content box holds only the rows in the scrolled viewport (manual virtualization), each row
 * carrying its own glyphs plus that line's selection and caret(s). Editing lives in [TextFieldState].
 *
 * @param insight what the field offers on top of its text.
 * @param view the gutter and indent guides of a code field; null shows the text alone.
 * @param search the search hits to mark.
 */
@Composable
fun EditableTextField(
    state: TextFieldState,
    modifier: Modifier? = null,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    scrollState: UiScrollHandle = state.scroll,
    insight: UiCodeInsight = UiCodeInsight(),
    view: UiCodeView? = null,
    search: UiTextSearch? = null,
    placeholder: String = "",
    attributes: Map<String, String> = emptyMap(),
) {
    val diagnostics = insight.diagnostics
    val searchMatches = search?.matches.orEmpty()
    val activeSearchMatch = search?.active
    val folds = view?.folds.orEmpty()
    val gutterMarkers = view?.markers.orEmpty()
    val text = state.text
    val fontSize = state.fontSize
    val fontFamily = state.fontFamily
    val wrap = state.wrap
    val caret = if (state.focused) state.caret else UiNoCaretOffset
    state.caretVisibilityRevision
    val presentation = rememberEditableTextPresentation(
        text = text,
        caret = caret,
        highlighter = insight.highlighter,
        inlayHints = insight.inlayHints,
        inlayHintsProvider = insight.inlayHintsProvider,
        inlayRevision = insight.inlayRevision,
    )
    state.folding.update(text, folds)
    val foldProjection = state.folding.projection()
    val hiddenLines = foldProjection.hiddenLines
    val displayedHints = presentation.inlayHints + foldProjection.placeholders
    val uiViewport = LocalUiViewport.current
    val actionGutterWidth = remember(text, fontSize, folds, gutterMarkers) {
        state.gutter.width(gutterMarkers, foldProjection.lines, fontSize)
    }
    val gutterPanelWidth = if (view != null && state.multiline) {
        editableFieldGutterWidth(text, fontSize) + actionGutterWidth
    } else {
        0f
    }
    val gutterWidth = if (gutterPanelWidth > 0f) gutterPanelWidth + fontSize * 0.25f else 0f
    val viewportWidth = (scrollState.viewport.width - gutterWidth).coerceAtLeast(0f)
    val multiline = state.multiline
    val layoutHolder = remember { EditableFieldLayoutHolder() }
    val inlayMetrics = remember { EditableFieldInlayMetrics() }
    val layout = remember(
        text, fontSize, fontFamily, wrap, viewportWidth, presentation.highlights, presentation.inlayHints, multiline,
        inlayMetrics.revision, hiddenLines, displayedHints,
    ) {
        computeEditableFieldLayout(
            text = text,
            fontSize = fontSize,
            fontFamily = fontFamily,
            wrap = wrap,
            viewportWidth = viewportWidth,
            highlights = presentation.highlights,
            inlayHints = displayedHints,
            inlayMetrics = inlayMetrics,
            previous = layoutHolder.last,
            multiline = multiline,
            hiddenLines = hiddenLines,
            projectedLines = foldProjection.lines,
        ).also { layoutHolder.last = it }
    }
    val completion = remember(state) { TextFieldCompletionState(state) }
    val codeInsight = remember(state) { EditableFieldCodeInsightState() }
    val diagnosticsByLine = remember(layout, diagnostics) { bucketDiagnosticsByLine(layout.lines, diagnostics) }
    val matchesByLine = remember(layout, searchMatches, activeSearchMatch) {
        bucketSearchMatchesByLine(layout.lines, searchMatches, activeSearchMatch)
    }
    var hoverTooltip by remember { mutableStateOf<EditableFieldDiagnosticTooltip?>(null) }
    var codeHoverTarget by remember { mutableStateOf<EditableFieldHoverTarget?>(null) }
    val pointerState = remember { EditableFieldPointerState() }
    DisposableEffect(state) {
        onDispose {
            state.offsetAtPoint = null
            state.clearDropPreview()
            pointerState.cancelDrag()
        }
    }
    SideEffect {
        completion.contributor = insight.completions
        completion.onFrame(insight.completionRevision)
        state.offsetAtPoint = { rootX, rootY ->
            val viewport = scrollState.viewport
            layout.offsetAt(
                rootX - viewport.x - gutterWidth + scrollState.offsetX,
                rootY - viewport.y + scrollState.offsetY,
            )
        }
        state.dropPreview.refresh(state.offsetAtPoint)
        state.autoScroll.follow(state, layout, scrollState, gutterWidth)
    }

    val visible = layout.visibleRange(scrollState.offsetY, scrollState.viewport.height, overscan = fontSize * 4f)

    Box(
        id = id,
        tags = listOf("text-field", "editable-text-field") + tags,
        mode = UiBoxMode.STACK,
        attributes = attributes,
        modifier = Modifier.focus().input(hoverable = true, clickable = true, draggable = true)
            .cursor(UiCursorShape.TEXT).whitespace(UiWhitespace.PRESERVE).fontSize(fontSize)
            .let { if (fontFamily != null) it.fontFamily(fontFamily) else it }.onFocus { state.focus() }.onUnfocus {
                state.unfocus()
                completion.close()
                codeInsight.clearSignature()
                pointerState.cancelDrag()
                state.clearDropPreview()
            }.onCharTyped { event ->
                val char = event.codePoint.toChar()
                if (event.codePoint != 0 && !char.isISOControl() && state.typeCharacter(char)) {
                    completion.onCharTyped(char)
                    event.consume()
                }
            }
            // Low priority: user onKeyInput handlers (default priority 0) get first refusal.
            .onKeyInput(TextFieldDefaultKeyPriority) { input ->
                if (input.key == GLFW.GLFW_KEY_ESCAPE && pointerState.movingSelection) {
                    pointerState.cancelDrag()
                    state.clearDropPreview()
                    input.consume()
                    return@onKeyInput
                }
                val handled =
                    handleEditableFieldIntentionKey(state, input, completion, diagnostics) || handleEditableFieldKey(
                        state, input, layout, completion, codeInsight
                    )
                if (handled || !state.readOnly && input.typesCharacter()) input.consume()
            }.onPress { event ->
                state.clearDropPreview()
                state.focus()
                val hit = clickHit(event, layout, gutterWidth)
                val clickCount = pointerState.clickCount(hit.offset)
                val altPressed = event.modifiers and GLFW.GLFW_MOD_ALT != 0
                pointerState.beginPress(state.text, hit.offset, clickCount, altPressed)
                handleEditableFieldPress(
                    state, hit.offset, clickCount, event.modifiers, pointerState, hit.inlayAffinity,
                    allowSelectionMove = event.isLeftClick(),
                )
            }.onDrag { event ->
                if (!event.isLeftClick()) return@onDrag
                if (pointerState.movingSelection && abs(event.dragTotalX) < 3f && abs(event.dragTotalY) < 3f) return@onDrag
                handleEditableFieldDrag(state, clickOffset(event, layout, gutterWidth), pointerState)
                if (pointerState.movingSelection) state.previewDropAt(event.rootLocalX, event.rootLocalY)
            }.onRelease { event ->
                if (!event.isLeftClick()) return@onRelease
                val offset = state.offsetAtPoint?.invoke(event.rootLocalX, event.rootLocalY) ?: state.caret
                val hit = event.frame?.hitTest(event.x, event.y)?.node
                val inside = generateSequence(hit) { it.layoutState.parentNode }.any { it === event.node }
                if (handleEditableFieldRelease(state, offset, event.modifiers, pointerState, inside)) event.consume()
            }.onHover { event ->
                hoverTooltip = if (diagnostics.isEmpty()) null else {
                    val viewport = scrollState.viewport
                    val surface = uiViewport.takeIf { it.width > 0f && it.height > 0f } ?: viewport
                    editableFieldDiagnosticTooltipAt(
                        diagnostics = diagnostics,
                        layout = layout,
                        pointerX = event.x - viewport.x,
                        pointerY = event.y - viewport.y,
                        scrollX = scrollState.offsetX,
                        scrollY = scrollState.offsetY,
                        viewportWidth = surface.width,
                        viewportHeight = surface.height,
                        contentOffsetX = gutterWidth,
                        originX = viewport.x - surface.x,
                        originY = viewport.y - surface.y,
                    )
                }
                codeHoverTarget = if (hoverTooltip == null && insight.hover != null) {
                    EditableFieldHoverTarget(clickHit(event, layout, gutterWidth).offset)
                } else {
                    null
                }
            }.onExit {
                hoverTooltip = null
                codeHoverTarget = null
            }.then(modifier ?: Modifier).scrollable(
                vertical = multiline,
                horizontal = !wrap,
                hasVerticalScrollbar = multiline,
                hasHorizontalScrollbar = multiline,
                state = scrollState,
            ),
    ) {
        val placeholderWidth = if (text.isEmpty() && placeholder.isNotEmpty()) {
            UiTextLayouter.measureTextWidth(placeholder, fontSize, fontFamily) + fontSize
        } else {
            0f
        }
        val innerWidth = maxOf(layout.naturalWidth, placeholderWidth)
        val contentModifier = Modifier.size(innerWidth.px, layout.height.px).let { base ->
            if (multiline) base.position(gutterWidth.px, 0.px) else base.align(UiAlign.START, UiAlign.CENTER)
        }
        Box(
            mode = UiBoxMode.STACK,
            modifier = contentModifier,
        ) {
            if (text.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    placeholder,
                    tags = listOf("editable-text-field-placeholder"),
                    modifier = Modifier.position(0.px, 0.px).foreground(EditableFieldPlaceholderColor)
                        .let { state.textShadow?.let { shadow -> it.textEffects(shadow) } ?: it }.textWrap(wrap),
                )
            }
            for (index in visible) {
                if (index in layout.hiddenLines) continue
                key(index) {
                    EditableFieldRow(
                        state = state,
                        index = index,
                        layout = layout,
                        rowDiagnostics = diagnosticsByLine.getOrNull(index).orEmpty(),
                        rowSearchMatches = matchesByLine.getOrNull(index).orEmpty(),
                        indentGuides = view?.indentGuides == true,
                        indentGuideColor = view?.indentGuideColor ?: EditableFieldIndentGuideColor,
                        inlayMetrics = inlayMetrics,
                        onInlayAction = { action ->
                            if (!state.folding.activate(action)) insight.onInlayAction?.invoke(action)
                        },
                    )
                }
            }
        }
        if (gutterWidth > 0f) {
            EditableFieldGutter(
                layout,
                visible,
                gutterPanelWidth,
                view?.lineNumberColor ?: EditableFieldLineNumberColor,
                state,
                folds,
                gutterMarkers,
                id ?: "text-field-${System.identityHashCode(state)}",
                view?.indentGuideColor ?: EditableFieldIndentGuideColor
            )
        }
        if (completion.items.isNotEmpty()) {
            EditableFieldCompletionPopup(completion, layout, scrollState, gutterWidth)
        }
        hoverTooltip?.let { tooltip ->
            EditableFieldDiagnosticTooltipOverlay(tooltip, visible = completion.items.isEmpty())
        }
        EditableFieldCodeInsight(
            state = codeInsight,
            text = text,
            caret = state.caret,
            focused = state.focused,
            completionVisible = completion.items.isNotEmpty(),
            hoverTarget = codeHoverTarget,
            signatureProvider = insight.signatureHelp,
            hoverProvider = insight.hover,
            revision = insight.revision,
            layout = layout,
            scrollState = scrollState,
            contentOffsetX = gutterWidth,
        )
    }
}

internal fun editableFieldGutterWidth(text: String, fontSize: Float): Float {
    val lines = text.count { it == '\n' } + 1
    val digits = lines.toString().length.coerceAtLeast(2)
    return digits * fontSize * 0.62f + EditableFieldLineNumberGap
}


@Composable
private fun EditableFieldRow(
    state: TextFieldState,
    index: Int,
    layout: EditableFieldLayout,
    rowDiagnostics: List<UiTextDiagnostic> = emptyList(),
    rowSearchMatches: List<EditableFieldSearchMatch> = emptyList(),
    indentGuides: Boolean = false,
    indentGuideColor: UiColor = EditableFieldIndentGuideColor,
    inlayMetrics: EditableFieldInlayMetrics,
    onInlayAction: ((UiInlayAction) -> Unit)? = null,
) {
    val line = layout.lines[index]
    val top = layout.lineTop(index)
    val lineLayout = layout.lineLayouts[index]
    val fontSize = layout.fontSize
    val fontFamily = layout.fontFamily
    val ranges = state.caretRanges.filter { it.selectionStart <= line.end && it.selectionEnd >= line.start }

    rowSearchMatches.forEachIndexed { matchIndex, match ->
        val padding = if (match.active) ActiveSearchMatchPadding else SearchMatchPadding
        selectionRectsForRow(
            line, lineLayout, match.start, match.end, false, fontSize, fontFamily, layout.contentWidth,
        ).forEachIndexed { rectIndex, rect ->
            key("match", matchIndex, rectIndex) {
                Box(
                    modifier = Modifier.position((rect.x - padding).px, (top + rect.y - padding).px)
                        .size((rect.width + padding * 2f).px, (rect.height + padding * 2f).px)
                        .background(if (match.active) ActiveSearchMatchColor else SearchMatchColor).border(
                            1.px,
                            if (match.active) ActiveSearchMatchBorderColor else SearchMatchBorderColor,
                            radius = SearchMatchRadius,
                        ),
                )
            }
        }
    }

    if (indentGuides) state.indentSize?.let { indentSize ->
        val guideHeight = layout.lineTop(index + 1) - top
        editableFieldIndentGuideColumns(layout.lines, index, indentSize).forEachIndexed { guideIndex, column ->
            val x = UiTextLayouter.measureTextWidth(" ".repeat(column), fontSize, fontFamily)
            key("guide", guideIndex) {
                Box(
                    modifier = Modifier.position(x.px, top.px).size(1.px, guideHeight.px).background(indentGuideColor),
                )
            }
        }
    }

    ranges.filter { it.hasSelection }.forEachIndexed { rangeIndex, range ->
        val localStart = (range.selectionStart - line.start).coerceIn(0, line.text.length)
        val localEnd = (range.selectionEnd - line.start).coerceIn(0, line.text.length)
        val crossesNewline = range.selectionEnd > line.end
        selectionRectsForRow(
            line, lineLayout, localStart, localEnd, crossesNewline, fontSize, fontFamily, layout.contentWidth
        ).forEachIndexed { rectIndex, rect ->
            key("sel", rangeIndex, rectIndex) {
                Box(
                    modifier = Modifier.position(rect.x.px, (top + rect.y).px).size(rect.width.px, rect.height.px)
                        .background(state.selectionColor),
                )
            }
        }
    }

    if (lineLayout != null) {
        EditableFieldLineFragments(
            lineLayout,
            layout.inlayHints[index],
            top,
            fontSize,
            fontFamily,
            state.textShadow,
            inlayMetrics,
            onInlayAction,
            inlayIdPrefix = "text-field-${System.identityHashCode(state)}",
        )
    } else if (line.text.isNotEmpty()) {
        val effects = listOfNotNull(state.textShadow)
        Text(
            line.text,
            modifier = Modifier.position(0.px, top.px).fontSize(fontSize)
                .let { if (fontFamily != null) it.fontFamily(fontFamily) else it }.textEffects(*effects.toTypedArray())
                .textWrap(false),
        )
    }

    if (rowDiagnostics.isNotEmpty()) {
        EditableFieldRowDiagnostics(line, lineLayout, rowDiagnostics, top, fontSize, fontFamily, layout.contentWidth)
    }

    state.dropPreview.offset?.takeIf { it in line.start..line.end }?.let { offset ->
        val dropCaret = layout.caretAt(offset)
        Box(
            tags = listOf("editable-text-field-drop-caret"),
            modifier = Modifier.position(dropCaret.x.px, dropCaret.y.px)
                .size(TextFieldCaretWidth.px, layout.lineHeight.px).background(state.caretColor).layer(1)
                .inputTransparent()
        )
    }
    if (state.focused && state.dropPreview.offset == null) key(state.caretVisibilityRevision) {
        ranges.filter { it.position in line.start..line.end }.forEachIndexed { caretIndex, range ->
            val caret = layout.caretAt(range.position, range.inlayAffinity)
            key("caret", caretIndex) {
                Box(
                    modifier = Modifier.position(caret.x.px, caret.y.px)
                        .size(TextFieldCaretWidth.px, layout.lineHeight.px).background(state.caretColor).layer(1)
                        .animation(
                            UiCaretBlinkKeyframes, UiCaretBlinkPeriodMillis, iterationCount = Float.POSITIVE_INFINITY
                        ),
                )
            }
        }
    }
}


@Composable
private fun EditableFieldLineFragments(
    lineLayout: UiTextLayout,
    inlayHints: Map<String, UiInlayHint>,
    top: Float,
    fontSize: Float,
    fontFamily: String?,
    textShadow: Shadow? = null,
    inlayMetrics: EditableFieldInlayMetrics,
    onInlayAction: ((UiInlayAction) -> Unit)? = null,
    inlayIdPrefix: String,
) {
    lineLayout.lines.forEachIndexed { visualIndex, visual ->
        visual.fragments.forEachIndexed { fragmentIndex, fragment ->
            val x = visual.x + fragment.x
            val y = top + visual.y + fragment.y
            when (fragment) {
                is UiTextRun -> {
                    fragment.style.background?.let { color ->
                        key("run-bg", visualIndex, fragmentIndex) {
                            Box(
                                modifier = Modifier.position(x.px, y.px).size(fragment.width.px, fragment.height.px)
                                    .background(color),
                            )
                        }
                    }
                    if (fragment.text.isNotEmpty()) {
                        key("run", visualIndex, fragmentIndex) {
                            val family = fragment.style.fontFamily ?: fontFamily
                            val effects = fragment.style.effects + listOfNotNull(textShadow)
                            Text(
                                fragment.text,
                                modifier = Modifier.position(x.px, y.px)
                                    .fontSize(fragment.style.resolvedFontSize(fontSize))
                                    .let { if (family != null) it.fontFamily(family) else it }
                                    .textEffects(*effects.toTypedArray()).textWrap(false),
                            )
                        }
                    }
                }

                is UiTextSpaceRun -> {
                    fragment.style.background?.let { color ->
                        key("space-bg", visualIndex, fragmentIndex) {
                            Box(
                                modifier = Modifier.position(x.px, y.px).size(fragment.width.px, fragment.height.px)
                                    .background(color),
                            )
                        }
                    }
                }

                is UiInlineWidgetRun -> {
                    val hint = inlayHints[fragment.widget.id]
                    if (hint != null) {
                        key("inlay", visualIndex, fragmentIndex, fragment.widget.id) {
                            InlayHint("$inlayIdPrefix-${fragment.widget.id}", hint, x, y, inlayMetrics, onInlayAction)
                        }
                    }
                }

                is UiInlineImageRun -> Unit
            }
        }
    }
}

/**
 * One inlay, drawn as an ordinary node: its parts become children and its tags reach the
 * stylesheet, which owns the padding, colors and font. The measured bounds go back into
 * [metrics], so the line reserves exactly the room the inlay ended up taking.
 */
@Composable
private fun InlayHint(
    id: String,
    hint: UiInlayHint,
    x: Float,
    y: Float,
    metrics: EditableFieldInlayMetrics,
    onInlayAction: ((UiInlayAction) -> Unit)?,
) {
    val action = hint.action?.takeIf { onInlayAction != null }
    val footprint = remember(hint, metrics) { InlayFootprint(hint, metrics) }
    Row(
        id = id,
        tags = InlayTags + hint.tags + listOfNotNull("inlay-action".takeIf { action != null }),
        modifier = Modifier.position(x.px, y.px).size(UiLength.Fit, UiLength.Fit).onResolvedStyle(footprint::styled)
            .onPlaced(footprint::placed).let { base ->
                if (action == null) base else base.input(clickable = true, hoverable = true).cursor(UiCursorShape.HAND)
                    .onClick { event ->
                        onInlayAction?.invoke(action)
                        event.consume()
                    }
            },
    ) {
        for ((partIndex, part) in hint.content.withIndex()) {
            key("part", partIndex) {
                when (part) {
                    is UiInlayContent.Label -> Text(part.text, tags = InlayLabelTags)
                    is UiInlayContent.Icon -> Image(part.source, tags = InlayIconTags)
                    is UiInlayContent.Swatch -> Box(
                        mode = UiBoxMode.STACK,
                        tags = InlaySwatchTags,
                        modifier = Modifier.checkerboard(3f).clip(true),
                    ) {
                        Box(
                            modifier = Modifier.size(100.percent, 100.percent).background(UiColor.fromArgb(part.argb)),
                        )
                    }
                }
            }
        }
    }
}

private val InlayTags = listOf("editable-text-field-inlay", "code-editor-inlay")
private val InlayLabelTags = listOf("editable-text-field-inlay-text", "code-editor-inlay-text")
private val InlayIconTags = listOf("editable-text-field-inlay-icon", "code-editor-inlay-icon")
private val InlaySwatchTags = listOf("editable-text-field-inlay-swatch", "code-editor-inlay-swatch")

/**
 * Collects what one inlay ended up taking: the box the layout measured plus the margins
 * the stylesheet put around it, so a styled margin pushes the text along instead of
 * letting the inlay overlap it.
 */
private class InlayFootprint(
    private val hint: UiInlayHint,
    private val metrics: EditableFieldInlayMetrics,
) {
    private var width = 0f
    private var height = 0f
    private var horizontalMargin = 0f
    private var verticalMargin = 0f

    fun placed(bounds: UiRect) {
        width = bounds.width
        height = bounds.height
        publish()
    }

    fun styled(style: UiComputedStyle) {
        val margin = style.margin
        horizontalMargin = margin.left.resolve(0f) + margin.right.resolve(0f)
        verticalMargin = margin.top.resolve(0f) + margin.bottom.resolve(0f)
        publish()
    }

    private fun publish() {
        if (width <= 0f || height <= 0f) return
        metrics.record(hint, width + horizontalMargin, height + verticalMargin)
    }
}

internal fun selectionRectsForRow(
    line: EditableFieldLine,
    lineLayout: UiTextLayout?,
    localStart: Int,
    localEnd: Int,
    crossesNewline: Boolean,
    fontSize: Float,
    fontFamily: String?,
    @Suppress("UNUSED_PARAMETER") fullWidth: Float,
): List<UiRect> {
    val newlineWidth = UiTextLayouter.measureTextWidth(" ", fontSize, fontFamily).coerceAtLeast(1f)
    if (lineLayout != null) {
        val rects = mergeSelectionRects(
            lineLayout.selectionRects(localStart, localEnd, fontSize, fontFamily, fillLineGaps = false),
        )
        if (!crossesNewline) return rects
        val caret = lineLayout.caretPosition(localEnd, fontSize, fontFamily)
        val lineHeight = lineLayout.lines.lastOrNull()?.height ?: fontSize
        return rects + UiRect(caret.x, caret.y, newlineWidth, lineHeight)
    }
    val x1 = UiTextLayouter.measureTextWidth(line.text.take(localStart), fontSize, fontFamily)
    val x2 = UiTextLayouter.measureTextWidth(line.text.take(localEnd), fontSize, fontFamily)
    val right = if (crossesNewline) x2 + newlineWidth else x2
    if (right <= x1) return emptyList()
    return listOf(UiRect(x1, 0f, right - x1, fontSize))
}

private fun mergeSelectionRects(rects: List<UiRect>): List<UiRect> {
    if (rects.size < 2) return rects
    val result = ArrayList<UiRect>()
    for (rect in rects) {
        val previous = result.lastOrNull()
        if (previous != null && abs(previous.y - rect.y) <= 0.5f && abs(previous.height - rect.height) <= 0.5f) {
            val left = minOf(previous.x, rect.x)
            val right = maxOf(previous.x + previous.width, rect.x + rect.width)
            result[result.lastIndex] = UiRect(left, previous.y, right - left, previous.height)
        } else {
            result += rect
        }
    }
    return result
}

/** Maps a pointer event on the field to a document offset (padding + gutter + scroll aware). */
private fun clickHit(event: UiEvent, layout: EditableFieldLayout, gutterWidth: Float = 0f): EditableFieldCaretHit {
    val fieldLayout = event.frame?.layout?.nodes?.get(event.node) ?: return EditableFieldCaretHit(0)
    val x = event.localX - (fieldLayout.content.x - fieldLayout.rect.x) - gutterWidth + fieldLayout.scrollOffset.x
    val y = event.localY - (fieldLayout.content.y - fieldLayout.rect.y) + fieldLayout.scrollOffset.y
    return layout.caretHitAt(x, y)
}

private fun clickOffset(event: UiEvent, layout: EditableFieldLayout, gutterWidth: Float = 0f): Int =
    clickHit(event, layout, gutterWidth).offset


private class EditableFieldLayoutHolder {
    var last: EditableFieldLayout? = null
}

/**
 * Maps a key press to a [TextFieldState] edit/navigation operation (giving an open completion
 * popup first refusal on navigation/accept keys). Returns whether the field handled the key.
 */
internal fun handleEditableFieldKey(
    state: TextFieldState,
    input: UiKeyInput,
    layout: EditableFieldLayout? = null,
    completion: TextFieldCompletionState? = null,
    codeInsight: EditableFieldCodeInsightState? = null,
): Boolean {
    val text = state.text
    if (completion != null && completion.active) {
        when (input.key) {
            GLFW.GLFW_KEY_UP -> {
                completion.moveSelection(-1)
                return true
            }

            GLFW.GLFW_KEY_DOWN -> {
                completion.moveSelection(1)
                return true
            }

            GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> if (completion.accept()) return true

            GLFW.GLFW_KEY_ESCAPE -> {
                completion.close()
                return true
            }

            // Horizontal caret moves drop the popup and fall through to normal navigation.
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> completion.close()
        }
    }
    if (!state.multiline && !input.shift && (input.key == GLFW.GLFW_KEY_UP || input.key == GLFW.GLFW_KEY_DOWN)) return false
    if (input.key == GLFW.GLFW_KEY_ESCAPE && codeInsight?.dismissSignature() == true) return true
    if (completion != null && HollowKeys.COMPLETION.matches(input)) {
        completion.open()
        return true
    }
    // Ctrl+Alt+Up/Down drops an extra caret on the line above/below the primary one.
    if (input.control && input.alt && (input.key == GLFW.GLFW_KEY_UP || input.key == GLFW.GLFW_KEY_DOWN)) {
        val delta = if (input.key == GLFW.GLFW_KEY_UP) -1 else 1
        val position = layout?.visualCaretMove(state.primaryCaret.position, delta) ?: verticalCaretMove(
            text, state.primaryCaret.position, delta
        )
        state.addCaret(position)
        return true
    }
    when {
        HollowKeys.SELECT_ALL.matches(input) -> return state.selectAll().let { true }
        HollowKeys.COPY.matches(input) -> return copySelection(state)
        HollowKeys.DUPLICATE.matches(input) -> return state.duplicateSelections()
        HollowKeys.CUT.matches(input) -> return cutSelection(state)
        HollowKeys.PASTE.matches(input) -> return pasteClipboard(state)
        HollowKeys.UNDO.matches(input) -> return state.undo()
        HollowKeys.REDO.matches(input) -> return state.redo()
    }
    return when (input.key) {
        GLFW.GLFW_KEY_BACKSPACE -> state.backspace(word = input.control)
        GLFW.GLFW_KEY_DELETE -> state.deleteForward(word = input.control)

        GLFW.GLFW_KEY_LEFT -> {
            state.moveCaretsWithAffinity({ range ->
                when {
                    !input.shift && range.hasSelection -> UiTextCaret(range.selectionStart)
                    input.control -> UiTextCaret(wordLeft(text, range.position))
                    range.inlayAffinity == UiInlayCaretAffinity.AFTER && layout?.hasInlayAt(range.position) == true -> range.copy(
                        inlayAffinity = UiInlayCaretAffinity.BEFORE
                    )

                    else -> UiTextCaret(range.position - 1)
                }
            }, input.shift)
            true
        }

        GLFW.GLFW_KEY_RIGHT -> {
            state.moveCaretsWithAffinity({ range ->
                when {
                    !input.shift && range.hasSelection -> UiTextCaret(range.selectionEnd)
                    input.control -> UiTextCaret(wordRight(text, range.position))
                    range.inlayAffinity == UiInlayCaretAffinity.BEFORE && layout?.hasInlayAt(range.position) == true -> range.copy(
                        inlayAffinity = UiInlayCaretAffinity.AFTER
                    )

                    else -> {
                        val position = (range.position + 1).coerceAtMost(text.length)
                        val affinity = if (layout?.hasInlayAt(position) == true) {
                            UiInlayCaretAffinity.BEFORE
                        } else {
                            UiInlayCaretAffinity.AFTER
                        }
                        UiTextCaret(position, inlayAffinity = affinity)
                    }
                }
            }, input.shift)
            true
        }

        GLFW.GLFW_KEY_UP -> {
            state.moveCarets({
                layout?.visualCaretMove(it.position, -1) ?: verticalCaretMove(text, it.position, -1)
            }, input.shift)
            true
        }

        GLFW.GLFW_KEY_DOWN -> {
            state.moveCarets({
                layout?.visualCaretMove(it.position, 1) ?: verticalCaretMove(text, it.position, 1)
            }, input.shift)
            true
        }

        GLFW.GLFW_KEY_HOME -> {
            state.moveCarets({ if (input.control) 0 else lineStart(text, it.position) }, input.shift)
            true
        }

        GLFW.GLFW_KEY_END -> {
            state.moveCarets({ if (input.control) text.length else lineEnd(text, it.position) }, input.shift)
            true
        }

        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> state.insertNewlineWithIndent()
        GLFW.GLFW_KEY_TAB -> if (input.shift) state.unindent() else state.indent()
        else -> false
    }
}

/** Moves an offset [delta] logical lines up/down, keeping the column (clamped to the target line). */
internal fun verticalCaretMove(text: String, position: Int, delta: Int): Int {
    var start = lineStart(text, position)
    val column = position - start
    repeat(abs(delta)) {
        start = if (delta < 0) {
            if (start == 0) return 0
            lineStart(text, start - 1)
        } else {
            val end = lineEnd(text, start)
            if (end >= text.length) return text.length
            end + 1
        }
    }
    val length = lineEnd(text, start) - start
    return start + column.coerceAtMost(length)
}

private fun copySelection(state: TextFieldState): Boolean {
    state.selectedText()?.let { Minecraft.getInstance().keyboardHandler.clipboard = it }
    return true
}

private fun cutSelection(state: TextFieldState): Boolean {
    val selected = state.selectedText() ?: return true
    Minecraft.getInstance().keyboardHandler.clipboard = selected
    return state.backspace()
}

private fun pasteClipboard(state: TextFieldState): Boolean {
    val clipboard = Minecraft.getInstance().keyboardHandler.clipboard
    return clipboard.isNotEmpty() && state.paste(clipboard)
}

internal fun editableFieldIndentGuideColumns(line: String, indentSize: Int): List<Int> {
    val normalizedIndent = indentSize.coerceAtLeast(1)
    val leadingSpaces = line.takeWhile { it == ' ' }.length
    if (leadingSpaces < normalizedIndent * 2) return emptyList()
    val levels = leadingSpaces / normalizedIndent
    return (1 until levels).map { level -> level * normalizedIndent }
}

/** Keeps guides continuous across an empty logical line using the common surrounding indent. */
internal fun editableFieldIndentGuideColumns(
    lines: List<EditableFieldLine>,
    lineIndex: Int,
    indentSize: Int,
): List<Int> {
    val line = lines.getOrNull(lineIndex)?.text ?: return emptyList()
    if (line.isNotBlank()) return editableFieldIndentGuideColumns(line, indentSize)
    val previous = (lineIndex - 1 downTo 0).firstNotNullOfOrNull { index ->
        lines[index].text.takeIf(String::isNotBlank)
    }
    val next = (lineIndex + 1 until lines.size).firstNotNullOfOrNull { index ->
        lines[index].text.takeIf(String::isNotBlank)
    }
    if (previous == null || next == null) return emptyList()
    val commonIndent = minOf(previous.leadingSpaceCount(), next.leadingSpaceCount())
    return editableFieldIndentGuideColumns(" ".repeat(commonIndent), indentSize)
}

private fun String.leadingSpaceCount(): Int = indexOfFirst { it != ' ' }.let { if (it < 0) length else it }

internal const val TextFieldCaretWidth = 1f

private const val EditableFieldRowBlock = 16
internal const val EditableFieldLineNumberGap = 8f

/** A find-in-file match on one row, in that row's own column offsets. */
internal data class EditableFieldSearchMatch(val start: Int, val end: Int, val active: Boolean)

/** Splits [matches] across the rows they cross, marking the one equal to [active]. */
internal fun bucketSearchMatchesByLine(
    lines: List<EditableFieldLine>,
    matches: List<IntRange>,
    active: IntRange?,
): Array<List<EditableFieldSearchMatch>> {
    if (matches.isEmpty()) return Array(lines.size) { emptyList() }
    val buckets = arrayOfNulls<MutableList<EditableFieldSearchMatch>>(lines.size)
    var lineIndex = 0
    for (match in matches.sortedBy { it.first }) {
        val matchStart = match.first
        val matchEnd = match.last + 1
        val isActive = match == active
        while (lineIndex < lines.size && lines[lineIndex].end < matchStart) lineIndex++
        var index = lineIndex
        while (index < lines.size && lines[index].start < matchEnd) {
            val line = lines[index]
            val start = maxOf(matchStart, line.start) - line.start
            val end = minOf(matchEnd, line.end) - line.start
            if (end > start) {
                val bucket = buckets[index] ?: ArrayList<EditableFieldSearchMatch>().also { buckets[index] = it }
                bucket += EditableFieldSearchMatch(start, end, isActive)
            }
            index++
        }
    }
    return Array(lines.size) { buckets[it] ?: emptyList() }
}

private const val SearchMatchPadding = 1f
private const val ActiveSearchMatchPadding = 1.5f
private const val SearchMatchRadius = 2f
private val SearchMatchColor = UiColor(0.42f, 0.36f, 0.12f, 0.45f)
private val SearchMatchBorderColor = UiColor(0.78f, 0.68f, 0.32f, 0.65f)
private val ActiveSearchMatchColor = UiColor(0.86f, 0.60f, 0.16f, 0.42f)
private val ActiveSearchMatchBorderColor = UiColor(1f, 0.78f, 0.35f, 1f)

internal val EditableFieldLineNumberColor = UiColor(0.56f, 0.6f, 0.66f, 0.78f)
internal val EditableFieldIndentGuideColor = UiColor(0.56f, 0.6f, 0.66f, 0.22f)
internal val EditableFieldPlaceholderColor = UiColor(0.56f, 0.6f, 0.66f, 0.65f)

