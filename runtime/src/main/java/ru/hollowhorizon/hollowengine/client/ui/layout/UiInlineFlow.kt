package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.*
import ru.hollowhorizon.hollowengine.client.ui.text.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiInlineStyle

/** Sub-pixel slack for the wrap decision so a word that fits its line exactly isn't wrapped by
 *  float rounding (fit-content width round-trips accumulate a fraction of a pixel of error). */
private const val WrapEpsilon = 0.5f

/**
 * Per-line box-decoration geometry for an inline group (a [SpanNode] or a nested inline flow).
 * [lines] are the group's per-line boxes relative to the node's own rect origin; the renderer
 * paints background/border per line, honouring [decorationBreak] (slice vs clone).
 */
class InlineGroupDecoration(
    val lines: List<UiRect>,
    val decorationBreak: UiBoxDecorationBreak,
)

/** How a parent inline flow wants one of its (possibly transitive) descendants placed. */
internal class InlinePlacement(
    val node: UiNode,
    val rect: UiRect,
    val textLayout: UiTextLayout? = null,
    val decoration: InlineGroupDecoration? = null,
)

internal class InlineFlowLayout(
    val width: Float,
    val height: Float,
    /** Direct-child placements keyed by each inline group node in the subtree (incl. the root). */
    val childLayouts: Map<UiNode, List<InlinePlacement>>,
)

internal fun UiNode.isInlineFlow(): Boolean =
    (measurePolicy as? UiBuiltInMeasurePolicy)?.kind == UiBuiltInMeasurePolicyKind.INLINE_FLOW

/** The container-level horizontal alignment of an inline flow (unifies UiAlign and textAlign). */
internal fun inlineFlowAlign(style: UiComputedStyle): UiAlign {
    style.alignItemsHorizontal.takeUnless { it == UiAlign.AUTO }?.let { return it }
    return when (style.textAlign) {
        UiTextAlign.LEFT -> UiAlign.START
        UiTextAlign.CENTER -> UiAlign.CENTER
        UiTextAlign.RIGHT -> UiAlign.END
        UiTextAlign.JUSTIFY -> UiAlign.JUSTIFY
    }
}

/** An inline decoration group: a span or nested inline flow that owns pieces and draws per line. */
private class GroupCtx(
    val node: UiNode,
    val parent: UiNode,
    val enclosing: GroupCtx?,
    val leadPad: Float,
    val trailPad: Float,
    val padTop: Float,
    val padBottom: Float,
    val decorationBreak: UiBoxDecorationBreak,
    val isSpan: Boolean,
) {
    val lines = ArrayList<GroupLine?>()

    fun include(piece: Piece) {
        while (lines.size <= piece.lineIndex) lines += null
        val line = lines[piece.lineIndex] ?: GroupLine(isSpan).also { lines[piece.lineIndex] = it }
        line.include(piece)
    }
}

private class GroupLine(collectText: Boolean) {
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var top = 0f
    var height = 0f
    var reference: Piece? = null
    var sourceStart = 0
    var sourceEnd = 0
    var minSourceStart = Int.MAX_VALUE
    val textPieces: MutableList<Piece>? = if (collectText) ArrayList() else null

    fun include(piece: Piece) {
        if (reference == null) {
            reference = piece
            top = piece.lineTop
            height = piece.lineHeight
        }
        left = minOf(left, piece.x)
        right = maxOf(right, piece.x + piece.width)
        val sourceStart = piece.spanSourceStart()
        if (sourceStart != null) minSourceStart = minOf(minSourceStart, sourceStart)
        if (piece is WordPiece || piece is SpacePiece) textPieces?.add(piece)
    }
}

private sealed class Piece {
    var x = 0f
    var lineIndex = 0
    var lineTop = 0f
    var lineHeight = 0f
    abstract val width: Float
    abstract val height: Float

    /** Innermost enclosing group; [GroupCtx.enclosing] links towards the outer flow. */
    abstract val group: GroupCtx?
}

private class WordPiece(
    val text: String,
    val style: UiInlineStyle,
    override val width: Float,
    override val height: Float,
    override val group: GroupCtx?,
    /** Offset of this word in its span's source text, lets the span layout map caret offsets. */
    val sourceStart: Int = 0,
    /** Resolved font family the word was measured with (needed to re-measure when splitting). */
    val fontFamily: String? = null,
    /** The size it was measured at; [height] is the font's line, which is taller. */
    val fontSize: Float = height,
) : Piece()

private class SpacePiece(
    override val width: Float,
    override val height: Float,
    override val group: GroupCtx?,
    /** Preserved spaces (white-space: pre) keep their advance at line edges and never collapse. */
    val preserve: Boolean = false,
    /** Offset of this space in its span's source text. */
    val sourceStart: Int = 0,
) : Piece()

/** Offset in the owning span's source text, or null for pieces that carry no text (atoms, pads). */
private fun Piece.spanSourceStart(): Int? = when (this) {
    is WordPiece -> sourceStart
    is SpacePiece -> sourceStart
    else -> null
}

private class AtomPiece(
    val child: MeasuredChild,
    val parent: UiNode,
    override val width: Float,
    override val height: Float,
    override val group: GroupCtx?,
) : Piece()

/** A group's leading/trailing padding advance - zero-height, part of the group's extent. */
private class PadPiece(
    override val width: Float,
    override val group: GroupCtx?,
) : Piece() {
    override val height: Float get() = 0f
}

/** A container gap. It may be discarded at a line edge, unlike preserved whitespace. */
private class GapPiece(
    override val width: Float,
    override val group: GroupCtx?,
) : Piece() {
    override val height: Float get() = 0f
}

private class BreakPiece(override val group: GroupCtx?) : Piece() {
    override val width: Float get() = 0f
    override val height: Float get() = 0f
}


internal fun UiLayoutPipeline.computeInlineFlow(
    container: UiNode,
    resolved: UiNode,
    /** The container's content width - the reference alignment lays lines out against. */
    availableWidth: Float,
    availableHeight: Float,
    align: UiAlign,
    lineSpacing: Float,
    /** Whether lines break at [availableWidth]; when false, only an explicit '\n' breaks. */
    wrap: Boolean,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
): InlineFlowLayout {
    val pieces = ArrayList<Piece>()
    val groups = ArrayList<GroupCtx>()
    tokenizeInline(
        layoutChildren(container),
        resolved,
        container,
        enclosingGroup = null,
        availableWidth,
        availableHeight,
        scrollbarReserves,
        pieces,
        groups,
    )
    val lines = breakIntoLines(pieces, availableWidth, wrap)
    // Alignment still uses the container width even with wrap off, so a single line can sit
    // centered/right/justified when the container is wider than the text (a fit container just
    // hugs the text, making alignment a natural no-op).
    positionLines(lines, availableWidth, align, lineSpacing)

    val width = lines.maxOfOrNull { it.naturalWidth } ?: 0f
    val height = lines.sumOf { it.height.toDouble() }.toFloat() +
            (if (lines.size > 1) lineSpacing * (lines.size - 1) else 0f)

    val childLayouts = assembleChildLayouts(groups, lines)
    return InlineFlowLayout(width, height, childLayouts)
}

private fun UiLayoutPipeline.tokenizeInline(
    nodes: List<UiNode>,
    resolved: UiNode,
    parent: UiNode,
    enclosingGroup: GroupCtx?,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    out: MutableList<Piece>,
    groups: MutableList<GroupCtx>,
) {
    val gap = resolved[parent].gap.resolve(availableWidth).coerceAtLeast(0f)
    var hasPreviousChild = false
    for (child in nodes) {
        if (hasPreviousChild && gap > 0f) out += GapPiece(gap, enclosingGroup)
        val style = resolved[child]
        when {
            child is SpanNode -> {
                val group = groupCtx(
                    child, parent, enclosingGroup, style, availableWidth, availableHeight, isSpan = true,
                )
                groups += group
                tokenizeSpanWords(child, style, group, out)
            }

            child.isInlineFlow() -> {
                val group = groupCtx(
                    child, parent, enclosingGroup, style, availableWidth, availableHeight, isSpan = false,
                )
                groups += group
                if (group.leadPad > 0f) out += PadPiece(group.leadPad, group)
                tokenizeInline(
                    layoutChildren(child), resolved, child, group,
                    availableWidth, availableHeight, scrollbarReserves, out, groups,
                )
                if (group.trailPad > 0f) out += PadPiece(group.trailPad, group)
            }

            else -> {
                val margin = style.margin.resolve(availableWidth, availableHeight)
                val size = measureNode(child, resolved, availableWidth, availableHeight, scrollbarReserves)
                out += AtomPiece(
                    MeasuredChild(child, style, size, margin),
                    parent,
                    size.width + margin.horizontal,
                    size.height + margin.vertical,
                    enclosingGroup,
                )
            }
        }
        hasPreviousChild = true
    }
}

private fun groupCtx(
    node: UiNode,
    parent: UiNode,
    enclosing: GroupCtx?,
    style: UiComputedStyle,
    availableWidth: Float,
    availableHeight: Float,
    isSpan: Boolean,
): GroupCtx {
    // Spans are pure text runs: no box padding (wrap a fragment in a nested inline flow to pad it).
    val padding =
        if (isSpan) ResolvedUiInsets(0f, 0f, 0f, 0f) else style.padding.resolve(availableWidth, availableHeight)
    val margin = if (isSpan) ResolvedUiInsets(0f, 0f, 0f, 0f) else style.margin.resolve(availableWidth, availableHeight)
    // A decorated group gets at least its corner radius as horizontal padding so text never runs
    // into the rounded corners / right up to the background edge.
    val decorated = !isSpan && (style.background != UiPaint.None || style.border.width != UiInsets.Zero)
    val minSidePad = if (decorated) style.border.radius else 0f
    return GroupCtx(
        node = node,
        parent = parent,
        enclosing = enclosing,
        leadPad = maxOf(padding.left, minSidePad) + margin.left,
        trailPad = maxOf(padding.right, minSidePad) + margin.right,
        padTop = padding.top,
        padBottom = padding.bottom,
        decorationBreak = style.boxDecorationBreak,
        isSpan = isSpan,
    )
}

private fun tokenizeSpanWords(span: SpanNode, style: UiComputedStyle, group: GroupCtx, out: MutableList<Piece>) {
    val fontSize = style.fontSize
    val fontFamily = style.fontFamily
    // The span's effects (bold/italic/underline/strike/wave/etc.) ride on each run's inline style,
    // which is where the renderer reads glyph-level flags and per-run effects from. Measure with
    // them too, so a bold word reserves its wider advance and the following word keeps its space.
    val effects = style.textEffects
    val inlineStyle = UiInlineStyle(effects = effects)
    val spaceWidth = style.spaceWidth ?: UiTextLayouter.measureStyledTextWidth(" ", fontSize, fontFamily, inlineStyle)
    val height = UiTextFonts.resolve(fontFamily).lineHeight(fontSize)
    val preserve = style.whitespace == UiWhitespace.PRESERVE
    val text = span.text
    var wordStart = -1
    fun flush(end: Int) {
        if (wordStart < 0) return
        val wordText = text.substring(wordStart, end)
        val width = UiTextLayouter.measureStyledTextWidth(wordText, fontSize, fontFamily, inlineStyle)
        out += WordPiece(
            wordText, inlineStyle, width, height, group,
            sourceStart = wordStart, fontFamily = fontFamily, fontSize = fontSize,
        )
        wordStart = -1
    }
    for (index in text.indices) {
        val ch = text[index]
        when {
            ch == '\n' -> {
                flush(index)
                out += BreakPiece(group)
            }

            ch.isInlineWhitespace() -> {
                flush(index)
                if (preserve) {
                    out += SpacePiece(spaceWidth, height, group, preserve = true, sourceStart = index)
                } else if (out.lastOrNull() !is SpacePiece) {
                    out += SpacePiece(spaceWidth, height, group, sourceStart = index)
                }
            }

            wordStart < 0 -> wordStart = index
        }
    }
    flush(text.length)
}

private fun Char.isInlineWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || isWhitespace()

private class FlowLine {
    val pieces = ArrayList<Piece>()
    var hardBreak = false
    var width = 0f
    var height = 0f
    var top = 0f
    val naturalWidth: Float get() = width

    fun trimTrailingBreakables() {
        while (pieces.lastOrNull().let { it is GapPiece || it is SpacePiece && !it.preserve }) {
            width -= pieces.removeLast().width
        }
    }
}

private fun breakIntoLines(pieces: List<Piece>, wrapWidth: Float, wrap: Boolean): List<FlowLine> {
    val wrapping = wrap && wrapWidth.isFinite() && wrapWidth > 0f
    val lines = ArrayList<FlowLine>()
    var line = FlowLine()

    fun commit(hard: Boolean) {
        line.trimTrailingBreakables()
        line.hardBreak = hard
        lines += line
        line = FlowLine()
    }

    fun append(piece: Piece) {
        line.pieces += piece
        line.width += piece.width
    }

    var breakBefore = false
    var index = 0
    while (index < pieces.size) {
        when (val piece = pieces[index]) {
            is BreakPiece -> {
                commit(hard = true)
                breakBefore = false
                index++
            }

            is SpacePiece, is GapPiece -> {
                val preserve = piece is SpacePiece && piece.preserve
                if (line.pieces.isNotEmpty() || preserve) {
                    append(piece)
                }
                breakBefore = !preserve
                index++
            }

            is PadPiece -> {
                append(piece)
                index++
            }

            is WordPiece -> {
                val wordStart = index
                var wordWidth = 0f
                while (index < pieces.size) {
                    val word = pieces[index] as? WordPiece ?: break
                    wordWidth += word.width
                    index++
                }

                if (!wrapping || wordWidth <= wrapWidth + WrapEpsilon) {
                    if (wrapping && breakBefore && line.pieces.isNotEmpty() &&
                        line.width + wordWidth > wrapWidth + WrapEpsilon
                    ) {
                        commit(hard = false)
                    }
                    for (wordIndex in wordStart until index) append(pieces[wordIndex])
                } else {
                    var mayBreakBefore = breakBefore
                    for (wordIndex in wordStart until index) {
                        val word = pieces[wordIndex] as WordPiece
                        if (word.width > wrapWidth + WrapEpsilon) {
                            if (line.pieces.isNotEmpty()) commit(hard = false)
                            splitOversizedWordPiece(word, wrapWidth).forEachIndexed { chunkIndex, chunk ->
                                if (chunkIndex > 0) commit(hard = false)
                                append(chunk)
                            }
                        } else {
                            if (mayBreakBefore && line.pieces.isNotEmpty() &&
                                line.width + word.width > wrapWidth + WrapEpsilon
                            ) {
                                commit(hard = false)
                            }
                            append(word)
                        }
                        mayBreakBefore = false
                    }
                }
                breakBefore = false
            }

            is AtomPiece -> {
                if (wrapping && breakBefore && line.pieces.isNotEmpty() &&
                    line.width + piece.width > wrapWidth + WrapEpsilon
                ) {
                    commit(hard = false)
                }
                append(piece)
                breakBefore = false
                index++
            }
        }
    }
    if (line.pieces.isNotEmpty() || lines.isEmpty()) commit(hard = true)
    return lines
}

/** Splits a word wider than [width] into glyph-boundary chunks, keeping source offsets intact. */
private fun splitOversizedWordPiece(word: WordPiece, width: Float): List<WordPiece> {
    fun measure(text: String) = UiTextLayouter.measureStyledTextWidth(text, word.fontSize, word.fontFamily, word.style)
    val chunks = mutableListOf<WordPiece>()
    val buffer = StringBuilder()
    var chunkStart = word.sourceStart
    fun flush() {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        chunks += WordPiece(
            text, word.style, measure(text), word.height, word.group, chunkStart, word.fontFamily, word.fontSize,
        )
        chunkStart += text.length
        buffer.setLength(0)
    }
    for (char in word.text) {
        if (buffer.isNotEmpty() && measure(buffer.toString() + char) > width + WrapEpsilon) flush()
        buffer.append(char)
    }
    flush()
    return chunks.ifEmpty { listOf(word) }
}


private fun positionLines(lines: List<FlowLine>, wrapWidth: Float, align: UiAlign, lineSpacing: Float) {
    val hasWidth = wrapWidth.isFinite() && wrapWidth > 0f
    var y = 0f
    lines.forEachIndexed { lineIndex, line ->
        val lineHeight = line.pieces.maxOfOrNull { it.height } ?: 0f
        line.height = lineHeight
        line.top = y

        val justify = align == UiAlign.JUSTIFY && !line.hardBreak && hasWidth
        val spaceCount = line.pieces.count { it is SpacePiece }
        val extraPerSpace = if (justify && spaceCount > 0) (wrapWidth - line.width) / spaceCount else 0f

        val offset = if (!hasWidth) 0f else when (align) {
            UiAlign.CENTER -> (wrapWidth - line.width) / 2f
            UiAlign.END -> wrapWidth - line.width
            else -> 0f
        }

        var x = offset
        for (piece in line.pieces) {
            piece.x = x
            piece.lineIndex = lineIndex
            piece.lineTop = y
            piece.lineHeight = lineHeight
            x += piece.width
            if (piece is SpacePiece) x += extraPerSpace
        }
        y += lineHeight
        if (lineIndex != lines.lastIndex) y += lineSpacing
    }
}

private fun assembleChildLayouts(
    groups: List<GroupCtx>,
    lines: List<FlowLine>,
): Map<UiNode, List<InlinePlacement>> {
    for (line in lines) {
        for (piece in line.pieces) {
            if (!piece.contributesToGroupBounds()) continue
            var group = piece.group
            while (group != null) {
                group.include(piece)
                group = group.enclosing
            }
        }
    }

    val result = LinkedHashMap<UiNode, MutableList<InlinePlacement>>()
    for (group in groups) {
        val placement = group.toPlacement() ?: continue
        result.getOrPut(group.parent) { ArrayList() } += placement
    }

    for (line in lines) {
        for (piece in line.pieces) {
            if (piece !is AtomPiece) continue
            val margin = piece.child.margin
            val verticalOffset = when (piece.child.style.alignVertical) {
                UiAlign.START -> 0f
                UiAlign.END -> piece.lineHeight - piece.height
                else -> (piece.lineHeight - piece.height) / 2f
            }
            val rect = UiRect(
                piece.x + margin.left,
                piece.lineTop + verticalOffset + margin.top,
                piece.child.size.width,
                piece.child.size.height,
            )
            result.getOrPut(piece.parent) { ArrayList() } += InlinePlacement(piece.child.node, rect)
        }
    }
    return result
}

private fun Piece.contributesToGroupBounds(): Boolean =
    this is WordPiece || this is AtomPiece || this is SpacePiece && preserve

private fun GroupCtx.toPlacement(): InlinePlacement? {
    var firstLine = -1
    var lastLine = -1
    for (index in lines.indices) {
        if (lines[index] == null) continue
        if (firstLine < 0) firstLine = index
        lastLine = index
    }
    if (firstLine < 0) return null

    var boundLeft = Float.MAX_VALUE
    var boundTop = Float.MAX_VALUE
    var boundRight = -Float.MAX_VALUE
    var boundBottom = -Float.MAX_VALUE
    var lineCount = 0
    for (index in firstLine..lastLine) {
        val line = lines[index] ?: continue
        val left = line.left - if (index == firstLine) leadPad else 0f
        val right = line.right + if (index == lastLine) trailPad else 0f
        val top = line.top - padTop
        val bottom = line.top + line.height + padBottom
        boundLeft = minOf(boundLeft, left)
        boundTop = minOf(boundTop, top)
        boundRight = maxOf(boundRight, right)
        boundBottom = maxOf(boundBottom, bottom)
        lineCount++
    }

    val lineBoxes = ArrayList<UiRect>(lineCount)
    for (index in firstLine..lastLine) {
        val line = lines[index] ?: continue
        val left = line.left - if (index == firstLine) leadPad else 0f
        val right = line.right + if (index == lastLine) trailPad else 0f
        val top = line.top - padTop
        val height = line.height + padTop + padBottom
        lineBoxes += UiRect(left - boundLeft, top - boundTop, right - left, height)
    }

    val bounds = UiRect(boundLeft, boundTop, boundRight - boundLeft, boundBottom - boundTop)
    val decoration = InlineGroupDecoration(lineBoxes, decorationBreak)
    val textLayout = if (isSpan) {
        spanTextLayout(lines, lineCount, boundLeft, boundTop, (node as SpanNode).text.length)
    } else {
        null
    }
    return InlinePlacement(node, bounds, textLayout, decoration)
}

/**
 * Builds a span's [UiTextLayout] from its placed pieces. Preserved spaces become [UiTextSpaceRun]
 * fragments and each line carries its source offset, so `caretPosition`/`caretIndexAt` over the
 * layout are exact (indentation, columns, multi-line)
 */
private fun spanTextLayout(
    lines: List<GroupLine?>,
    linesCount: Int,
    boundLeft: Float,
    boundTop: Float,
    spanLength: Int,
): UiTextLayout {
    if (linesCount == 0) return UiTextLayout(emptyList(), 0f, 0f)

    var nextKnownStart = spanLength
    for (index in lines.indices.reversed()) {
        val line = lines[index] ?: continue
        line.sourceEnd = nextKnownStart
        if (line.minSourceStart != Int.MAX_VALUE) nextKnownStart = line.minSourceStart
        line.sourceStart = nextKnownStart
    }

    var totalLayoutHeight = 0f
    var maxLayoutWidth = 0f
    val textLines = ArrayList<UiTextLine>(linesCount)

    for (line in lines) {
        line ?: continue
        val linePieces = line.textPieces.orEmpty()
        val reference = line.reference ?: continue
        val lineHeight = reference.lineHeight
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var hasSpaces = false
        val fragments = ArrayList<UiTextFragment>(linePieces.size)

        for (piece in linePieces) {
            val y = ((lineHeight - piece.height) / 2f).coerceAtLeast(0f)
            val fragmentX = piece.x - boundLeft
            val fragmentRight = fragmentX + piece.width

            if (fragmentX < left) left = fragmentX
            if (fragmentRight > right) right = fragmentRight

            if (piece is SpacePiece) {
                hasSpaces = true
                fragments.add(UiTextSpaceRun(UiInlineStyle.Empty, fragmentX, y, piece.width, piece.height))
            } else {
                val word = piece as WordPiece
                fragments.add(UiTextRun(word.text, word.style, fragmentX, y, word.width, word.height))
            }
        }

        if (left == Float.MAX_VALUE) left = 0f
        if (right == -Float.MAX_VALUE) right = 0f

        val text = buildString {
            for (i in linePieces.indices) {
                val piece = linePieces[i]
                if (piece is SpacePiece) {
                    append(" ")
                } else {
                    append((piece as WordPiece).text)
                    if (!hasSpaces && i < linePieces.lastIndex) {
                        append(" ")
                    }
                }
            }
        }

        val lineWidth = right - left

        if (lineWidth > maxLayoutWidth) {
            maxLayoutWidth = lineWidth
        }
        totalLayoutHeight += lineHeight

        textLines.add(
            UiTextLine(
                text = text,
                x = 0f,
                y = reference.lineTop - boundTop,
                width = lineWidth,
                naturalWidth = lineWidth,
                height = lineHeight,
                sourceStart = line.sourceStart,
                sourceLength = (line.sourceEnd - line.sourceStart).coerceAtLeast(0),
                fragments = fragments,
            )
        )
    }

    return UiTextLayout(textLines, maxLayoutWidth, totalLayoutHeight, maxLayoutWidth)
}

internal fun UiLayoutPipeline.placeInlineFlowChildren(scope: ChildPlacementScope) {
    val node = scope.node
    val directChildren = if (node in inlineFlowFlattened) {
        inlineFlowChildLayouts[node].orEmpty()
    } else {
        val flow = computeInlineFlow(
            node,
            scope.resolved,
            scope.content.width,
            scope.content.height,
            inlineFlowAlign(scope.style),
            scope.style.lineSpacing,
            wrap = scope.style.textWrap,
            scope.scrollbarReserves,
        )
        val originX = scope.content.x
        val originY = scope.content.y
        for ((groupNode, placements) in flow.childLayouts) {
            inlineFlowChildLayouts[groupNode] = placements.map { it.shifted(originX, originY) }
            if (groupNode !== node) inlineFlowFlattened += groupNode
        }
        inlineFlowChildLayouts[node].orEmpty()
    }

    val singleSpan = node.children.singleOrNull() as? SpanNode
    for (placement in directChildren) {
        val target = placement.node
        if (target is SpanNode) target.lineLayout = placement.textLayout
        (target as? BaseUiNode)?.inlineDecoration = placement.decoration
        val rect = if (target === singleSpan && scope.style.textOverflow != UiTextOverflow.SHOW) {
            val left = placement.rect.x.coerceAtLeast(scope.content.x)
            val room = (scope.content.x + scope.content.width - left).coerceAtLeast(0f)
            placement.rect.copy(x = left, width = minOf(placement.rect.width, room))
        } else {
            placement.rect
        }
        placeScopedNode(scope, target, rect)
    }
}

private fun InlinePlacement.shifted(dx: Float, dy: Float) =
    InlinePlacement(node, UiRect(rect.x + dx, rect.y + dy, rect.width, rect.height), textLayout, decoration)
