package ru.hollowhorizon.hollowengine.client.ui.text

import ru.hollowhorizon.hollowengine.client.ui.UiTextAlign
import ru.hollowhorizon.hollowengine.client.ui.UiVec3
import ru.hollowhorizon.hollowengine.client.ui.layout.LayoutSize
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import kotlin.math.abs

data class UiTextLayout(
    val lines: List<UiTextLine>,
    val width: Float,
    val height: Float,
    val maxNaturalLineWidth: Float = lines.maxOfOrNull { it.naturalWidth } ?: width,
)

data class UiTextLine(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val naturalWidth: Float,
    val height: Float,
    val justify: Boolean = false,
    val extraSpace: Float = 0f,
    val sourceStart: Int = 0,
    val sourceLength: Int = text.length,
    val fragments: List<UiTextFragment> = emptyList(),
)

sealed interface UiTextFragment {
    val x: Float
    val y: Float
    val width: Float
    val height: Float
}

data class UiTextRun(
    val text: String,
    val style: UiInlineStyle,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
) : UiTextFragment

data class UiTextSpaceRun(
    val style: UiInlineStyle,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
) : UiTextFragment

data class UiInlineImageRun(
    val image: UiInlineItem.Image,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
) : UiTextFragment

data class UiInlineWidgetRun(
    val widget: UiInlineItem.Widget,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
) : UiTextFragment

internal object UiTextLayouter {
    private const val MaxCachedLayouts = 512
    private const val MaxCachedTextWidths = 2048
    private val lineCache = lruCache<LineCacheKey, List<RawTextLine>>(MaxCachedLayouts)
    private val layoutCache = lruCache<LayoutCacheKey, UiTextLayout>(MaxCachedLayouts)
    // Immutable keys are stored; the mutable probe performs allocation-free lookups on cache hits.
    private val widthCache = lruCache<Any, Float>(MaxCachedTextWidths)
    private val widthCacheProbe = TextWidthCacheProbe()

    fun measure(
        text: String,
        availableWidth: Float,
        knownWidth: Float?,
        wrap: Boolean,
        fontSize: Float,
        fontFamily: String? = null,
        preserveWhitespace: Boolean = false,
        lineSpacing: Float = 0f,
        spaceWidth: Float? = null,
    ): LayoutSize = measure(
        UiRichText.plain(text),
        availableWidth,
        knownWidth,
        wrap,
        fontSize,
        fontFamily,
        preserveWhitespace,
        lineSpacing,
        spaceWidth,
    )

    fun measure(
        richText: UiRichText,
        availableWidth: Float,
        knownWidth: Float?,
        wrap: Boolean,
        fontSize: Float,
        fontFamily: String? = null,
        preserveWhitespace: Boolean = false,
        lineSpacing: Float = 0f,
        spaceWidth: Float? = null,
    ): LayoutSize {
        if (knownWidth != null) {
            val layout = layout(
                richText,
                knownWidth,
                Float.POSITIVE_INFINITY,
                wrap,
                UiTextAlign.LEFT,
                fontSize,
                fontFamily,
                preserveWhitespace,
                lineSpacing,
                spaceWidth,
            )
            return LayoutSize(knownWidth, layout.height)
        }
        val naturalWidth = buildLines(
            richText,
            Float.POSITIVE_INFINITY,
            wrap = false,
            fontSize,
            fontFamily,
            preserveWhitespace,
            lineSpacing,
            spaceWidth,
        )
            .maxOfOrNull { it.width } ?: 0f
        val width = knownWidth
            ?: availableWidth.takeIf { wrap && it > 0f }?.let { minOf(naturalWidth, it) }
            ?: naturalWidth
        val layout = layout(
            richText,
            width,
            Float.POSITIVE_INFINITY,
            wrap,
            UiTextAlign.LEFT,
            fontSize,
            fontFamily,
            preserveWhitespace,
            lineSpacing,
            spaceWidth,
        )
        val measuredWidth = if (wrap && width.isFinite()) layout.maxNaturalLineWidth else width
        return LayoutSize(measuredWidth, layout.height)
    }

    fun layout(
        text: String,
        width: Float,
        height: Float,
        wrap: Boolean,
        align: UiTextAlign,
        fontSize: Float,
        fontFamily: String? = null,
        preserveWhitespace: Boolean = false,
        lineSpacing: Float = 0f,
        spaceWidth: Float? = null,
    ): UiTextLayout = layout(
        UiRichText.plain(text),
        width,
        height,
        wrap,
        align,
        fontSize,
        fontFamily,
        preserveWhitespace,
        lineSpacing,
        spaceWidth,
    )

    fun layout(
        richText: UiRichText,
        width: Float,
        height: Float,
        wrap: Boolean,
        align: UiTextAlign,
        fontSize: Float,
        fontFamily: String? = null,
        preserveWhitespace: Boolean = false,
        lineSpacing: Float = 0f,
        spaceWidth: Float? = null,
    ): UiTextLayout {
        val resolvedLineSpacing = lineSpacing.coerceAtLeast(0f)
        val resolvedSpaceWidth = spaceWidth?.coerceAtLeast(0f)
        val key = LayoutCacheKey(
            richText,
            width.cacheValue(),
            height.cacheValue(),
            wrap,
            align,
            fontSize.cacheValue(),
            fontFamily,
            preserveWhitespace,
            resolvedLineSpacing.cacheValue(),
            resolvedSpaceWidth?.cacheValue(),
            fontSignature(fontFamily),
        )
        layoutCache[key]?.let { return it }
        val rawLines = buildLines(
            richText,
            width,
            wrap,
            fontSize,
            fontFamily,
            preserveWhitespace,
            resolvedLineSpacing,
            resolvedSpaceWidth,
        )
        val lines = ArrayList<UiTextLine>(rawLines.size)
        var y = 0f
        var sourceStart = 0
        var maxNaturalLineWidth = 0f
        for (index in rawLines.indices) {
            val raw = rawLines[index]
            val lineAdvance = if (index == rawLines.lastIndex) raw.contentHeight else raw.advanceHeight
            if (height.isFinite() && lines.isNotEmpty() && y + lineAdvance > height) break
            val justify = align == UiTextAlign.JUSTIFY && !raw.lastInParagraph && raw.justifyGapCount > 0
            val textBoxWidth = width
            val extra = if (justify) {
                (textBoxWidth - raw.width).coerceAtLeast(0f) / raw.justifyGapCount.coerceAtLeast(1)
            } else {
                0f
            }
            val x = if (justify) 0f else align.lineOffset(textBoxWidth, raw.width)
            lines += raw.toLine(x, y, textBoxWidth, justify, extra, sourceStart)
            maxNaturalLineWidth = maxOf(maxNaturalLineWidth, raw.naturalWidth)
            sourceStart += raw.sourceLength
            y += lineAdvance
            if (height.isFinite() && y >= height) break
        }
        return UiTextLayout(lines, width, y, maxNaturalLineWidth).also { layoutCache[key] = it }
    }

    fun measureTextWidth(text: String, fontSize: Float, fontFamily: String? = null): Float {
        return measureTextWidth(text, fontSize, fontFamily, UiInlineStyle.Empty)
    }

    fun measureStyledTextWidth(text: String, fontSize: Float, fontFamily: String?, style: UiInlineStyle): Float {
        return measureTextWidth(text, fontSize, fontFamily, style)
    }

    fun visibleTextPrefix(
        layout: UiTextLayout,
        visibleCharacters: Int,
        fontSize: Float,
        fontFamily: String? = null,
    ): UiTextLayout {
        var remaining = visibleCharacters.coerceAtLeast(0)
        val lines = layout.lines.mapNotNull { line ->
            val fragments = mutableListOf<UiTextFragment>()
            val lineText = StringBuilder()
            var consumedOnLine = 0
            fragmentLoop@ for (fragment in line.fragments) {
                when (fragment) {
                    is UiInlineImageRun -> fragments += fragment
                    is UiInlineWidgetRun -> fragments += fragment
                    is UiTextSpaceRun -> {
                        if (remaining <= 0) break@fragmentLoop
                        fragments += fragment
                        lineText.append(' ')
                        remaining -= 1
                        consumedOnLine += 1
                    }

                    is UiTextRun -> {
                        if (remaining <= 0) break@fragmentLoop
                        val visible = fragment.text.take(remaining)
                        if (visible.isEmpty()) continue@fragmentLoop
                        val size = fragment.style.resolvedFontSize(fontSize)
                        fragments += fragment.copy(
                            text = visible,
                            width = measureTextWidth(visible, size, fontFamily, fragment.style),
                        )
                        lineText.append(visible)
                        remaining -= visible.length
                        consumedOnLine += visible.length
                        if (visible.length < fragment.text.length) break@fragmentLoop
                    }
                }
            }
            remaining -= (line.sourceLength - consumedOnLine).coerceAtLeast(0)
            if (fragments.isEmpty()) return@mapNotNull null
            line.copy(text = lineText.toString(), fragments = fragments)
        }
        val height = lines.lastOrNull()?.let { it.y + it.height } ?: 0f
        return layout.copy(
            lines = lines,
            height = height,
            maxNaturalLineWidth = lines.maxOfOrNull { it.naturalWidth } ?: layout.width,
        )
    }

    private fun measureTextWidth(text: String, fontSize: Float, fontFamily: String?, style: UiInlineStyle): Float {
        val resolvedFamily = style.fontFamily ?: fontFamily
        val cachedFontSize = fontSize.cacheValue()
        val signature = fontSignature(resolvedFamily)
        widthCacheProbe.set(text, style, cachedFontSize, resolvedFamily, signature)
        widthCache[widthCacheProbe]?.let { return it }
        val width = UiTextFonts.resolve(resolvedFamily).width(text, fontSize, style)
        val key = TextWidthCacheKey(text, style, cachedFontSize, resolvedFamily, signature)
        return width.also { widthCache[key] = it }
    }

    private fun buildLines(
        richText: UiRichText,
        width: Float,
        wrap: Boolean,
        fontSize: Float,
        fontFamily: String?,
        preserveWhitespace: Boolean,
        lineSpacing: Float,
        spaceWidth: Float?,
    ): List<RawTextLine> {
        val key = LineCacheKey(
            richText,
            width.cacheValue(),
            wrap,
            fontSize.cacheValue(),
            fontFamily,
            preserveWhitespace,
            lineSpacing.cacheValue(),
            spaceWidth?.cacheValue(),
            fontSignature(fontFamily),
        )
        lineCache[key]?.let { return it }
        val units = richText.toUnits(fontSize, fontFamily, spaceWidth)
        val result = mutableListOf<RawTextLine>()
        val current = mutableListOf<InlineUnit>()
        var currentWidth = 0f
        var pendingSpace: InlineUnit.Space? = null
        var endedWithNewline = false
        var y = 0f

        fun commit(lastInParagraph: Boolean, trailingSourceCharacters: Int = 0) {
            val line = RawTextLine(
                current.toList(),
                lastInParagraph,
                trailingSourceCharacters,
                fontSize,
                lineSpacing,
            )
            result += line
            y += line.advanceHeight
            current.clear()
            currentWidth = 0f
            pendingSpace = null
        }

        unitLoop@ for (unit in units) {
            when (unit) {
                InlineUnit.Newline -> {
                    commit(lastInParagraph = true, trailingSourceCharacters = 1)
                    endedWithNewline = true
                }

                is InlineUnit.Space -> {
                    endedWithNewline = false
                    if (preserveWhitespace) {
                        if (wrap && current.isNotEmpty() && currentWidth + unit.width > width) {
                            commit(lastInParagraph = false)
                        }
                        current += unit
                        currentWidth += unit.width
                    } else if (current.isNotEmpty()) {
                        pendingSpace = unit
                    }
                }

                is InlineUnit.Word -> {
                    endedWithNewline = false
                    val space = pendingSpace
                    val availableWidth = width.takeIf { it > 0f } ?: width
                    if (wrap && availableWidth.isFinite() && availableWidth > 0f && unit.width > availableWidth) {
                        if (current.isNotEmpty()) {
                            commit(lastInParagraph = false, trailingSourceCharacters = if (space == null) 0 else 1)
                        }
                        val chunks = splitOversizedWord(unit, availableWidth, fontFamily)
                        chunks.forEachIndexed { index, chunk ->
                            current += chunk
                            currentWidth = chunk.width
                            if (index < chunks.lastIndex) commit(lastInParagraph = false)
                        }
                        pendingSpace = null
                        continue@unitLoop
                    }

                    val candidateWidth = currentWidth + (space?.width ?: 0f) + unit.width
                    if (wrap && current.isNotEmpty() && candidateWidth > width) {
                        commit(lastInParagraph = false, trailingSourceCharacters = if (space == null) 0 else 1)
                    } else if (space != null) {
                        current += space
                        currentWidth += space.width
                    }
                    current += unit
                    currentWidth += unit.width
                    pendingSpace = null
                }

                is InlineUnit.Image -> {
                    endedWithNewline = false
                    val space = pendingSpace
                    val candidateWidth = currentWidth + (space?.width ?: 0f) + unit.width
                    if (wrap && current.isNotEmpty() && candidateWidth > width) {
                        commit(lastInParagraph = false, trailingSourceCharacters = if (space == null) 0 else 1)
                    } else if (space != null) {
                        current += space
                        currentWidth += space.width
                    }
                    current += unit
                    currentWidth += unit.width
                    pendingSpace = null
                }

                is InlineUnit.Widget -> {
                    endedWithNewline = false
                    val space = pendingSpace
                    val candidateWidth = currentWidth + (space?.width ?: 0f) + unit.width
                    if (wrap && current.isNotEmpty() && candidateWidth > width) {
                        commit(lastInParagraph = false, trailingSourceCharacters = if (space == null) 0 else 1)
                    } else if (space != null) {
                        current += space
                        currentWidth += space.width
                    }
                    current += unit
                    currentWidth += unit.width
                    pendingSpace = null
                }
            }
        }
        if (current.isNotEmpty() || result.isEmpty() || endedWithNewline) {
            commit(lastInParagraph = true)
        }
        return result.also { lineCache[key] = it }
    }

    private fun fontSignature(fontFamily: String?): Int = UiTextFonts.signature(fontFamily)

    private fun splitOversizedWord(word: InlineUnit.Word, width: Float, fontFamily: String?): List<InlineUnit.Word> {
        val chunks = mutableListOf<InlineUnit.Word>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            val text = buffer.toString()
            chunks += InlineUnit.Word(
                text,
                word.style,
                measureTextWidth(text, word.height, fontFamily, word.style),
                word.height
            )
            buffer.setLength(0)
        }

        for (char in word.text) {
            val candidate = buffer.toString() + char
            val candidateWidth = measureTextWidth(candidate, word.height, fontFamily, word.style)
            if (buffer.isNotEmpty() && candidateWidth > width) flush()
            buffer.append(char)
            if (measureTextWidth(buffer.toString(), word.height, fontFamily, word.style) > width) flush()
        }
        flush()
        return chunks.ifEmpty { listOf(word) }
    }

    private fun UiRichText.toUnits(baseFontSize: Float, fontFamily: String?, spaceWidth: Float?): List<InlineUnit> {
        val units = mutableListOf<InlineUnit>()
        for (item in items) {
            when (item) {
                is UiInlineItem.Image -> units += InlineUnit.Image(item)
                is UiInlineItem.Widget -> units += InlineUnit.Widget(item)
                is UiInlineItem.Text -> item.value.toUnits(item.style, baseFontSize, fontFamily, spaceWidth, units)
            }
        }
        return units
    }

    private fun String.toUnits(
        style: UiInlineStyle,
        baseFontSize: Float,
        fontFamily: String?,
        spaceWidth: Float?,
        target: MutableList<InlineUnit>,
    ) {
        var wordStart = -1
        fun flushWord(end: Int) {
            if (wordStart < 0) return
            val text = substring(wordStart, end).replace('\u00A0', ' ')
            val size = style.resolvedFontSize(baseFontSize)
            val resolvedFamily = style.fontFamily ?: fontFamily
            val lineHeight = UiTextFonts.resolve(resolvedFamily).lineHeight(size)
            target += InlineUnit.Word(text, style, measureTextWidth(text, size, fontFamily, style), lineHeight)
            wordStart = -1
        }
        for (index in indices) {
            val char = this[index]
            when (char) {
                '\n' -> {
                    flushWord(index)
                    target += InlineUnit.Newline
                }

                ' ', '\t' -> {
                    flushWord(index)
                    val size = style.resolvedFontSize(baseFontSize)
                    val resolvedFamily = style.fontFamily ?: fontFamily
                    val lineHeight = UiTextFonts.resolve(resolvedFamily).lineHeight(size)
                    target += InlineUnit.Space(
                        style,
                        spaceWidth ?: measureTextWidth(" ", size, fontFamily, style),
                        lineHeight
                    )
                }

                '\u00A0' -> if (wordStart < 0) wordStart = index
                else -> if (wordStart < 0) wordStart = index
            }
        }
        flushWord(length)
    }

    private fun RawTextLine.toLine(
        lineX: Float,
        lineY: Float,
        textBoxWidth: Float,
        justify: Boolean,
        extraSpace: Float,
        sourceStart: Int,
    ): UiTextLine {
        var x = 0f
        val fragments = ArrayList<UiTextFragment>(units.size)
        for (unit in units) {
            when (unit) {
                InlineUnit.Newline -> Unit
                is InlineUnit.Space -> {
                    val spaceWidth = unit.width + if (justify) extraSpace else 0f
                    fragments += UiTextSpaceRun(unit.style, x, unit.y, spaceWidth, unit.height)
                    x += spaceWidth
                }

                is InlineUnit.Word -> {
                    fragments += UiTextRun(unit.text, unit.style, x, unit.y, unit.width, unit.height)
                    x += unit.width
                }

                is InlineUnit.Image -> {
                    fragments += UiInlineImageRun(unit.image, x, unit.y, unit.width, unit.height)
                    x += unit.width
                }

                is InlineUnit.Widget -> {
                    fragments += UiInlineWidgetRun(unit.widget, x, unit.y, unit.width, unit.height)
                    x += unit.width
                }
            }
        }
        return UiTextLine(
            text = text,
            x = lineX,
            y = lineY,
            width = if (justify) textBoxWidth else width,
            naturalWidth = naturalWidth,
            height = height,
            justify = justify,
            extraSpace = extraSpace,
            sourceStart = sourceStart,
            sourceLength = sourceLength,
            fragments = fragments,
        )
    }

    private fun UiTextAlign.lineOffset(width: Float, lineWidth: Float): Float = when (this) {
        UiTextAlign.LEFT, UiTextAlign.JUSTIFY -> 0f
        UiTextAlign.RIGHT -> (width - lineWidth).coerceAtLeast(0f)
        UiTextAlign.CENTER -> ((width - lineWidth) / 2f).coerceAtLeast(0f)
    }

    private class RawTextLine(
        val units: List<InlineUnit>,
        val lastInParagraph: Boolean,
        val trailingSourceCharacters: Int = 0,
        val baseFontSize: Float,
        val lineSpacing: Float = 0f,
    ) {
        val text: String
        val width: Float
        val naturalWidth: Float
        val height: Float
        val contentHeight: Float
        val advanceHeight: Float
        val sourceLength: Int
        val justifyGapCount: Int

        init {
            val textBuilder = StringBuilder()
            var measuredWidth = 0.0
            var measuredLineHeight = 0f
            var measuredTextHeight = 0f
            var baseline = 0f
            var gaps = 0
            var widgetSourceCorrection = 0
            for (unit in units) {
                measuredWidth += unit.width.toDouble()
                measuredLineHeight = maxOf(measuredLineHeight, unit.height)
                when (unit) {
                    InlineUnit.Newline -> Unit
                    is InlineUnit.Space -> {
                        textBuilder.append(' ')
                        gaps++
                    }

                    is InlineUnit.Word -> {
                        textBuilder.append(unit.text)
                        measuredTextHeight = maxOf(measuredTextHeight, unit.height)
                    }

                    is InlineUnit.Image -> {
                        textBuilder.append(unit.image.alt.ifBlank { "\uFFFC" })
                        if (unit.image.align == UiInlineAlign.BASELINE) baseline = maxOf(baseline, unit.height)
                    }

                    is InlineUnit.Widget -> {
                        if (unit.sourceLength > 0) {
                            val label = unit.widget.alt.ifBlank { "\uFFFC" }
                            textBuilder.append(label)
                            widgetSourceCorrection += unit.sourceLength - label.length
                        }
                        if (unit.widget.align == UiInlineAlign.BASELINE) baseline = maxOf(baseline, unit.height)
                    }
                }
            }
            text = textBuilder.toString()
            width = measuredWidth.toFloat()
            naturalWidth = width
            justifyGapCount = gaps
            sourceLength = text.length + trailingSourceCharacters + widgetSourceCorrection
            val textHeight = measuredTextHeight.takeIf { it > 0f }
                ?: UiTextFonts.resolve(null).lineHeight(baseFontSize)
            val lineHeight = maxOf(measuredLineHeight, textHeight)
            baseline = maxOf(baseline, textHeight)
            units.forEach { it.resolveY(lineHeight, baseline) }
            var lineBottom = lineHeight
            for (unit in units) lineBottom = maxOf(lineBottom, unit.y + unit.height)
            contentHeight = lineBottom
            height = contentHeight
            advanceHeight = contentHeight + lineSpacing
        }
    }

    private sealed interface InlineUnit {
        val width: Float
        val height: Float
        var y: Float

        data object Newline : InlineUnit {
            override val width: Float = 0f
            override val height: Float = 0f
            override var y: Float = 0f
        }

        data class Space(
            val style: UiInlineStyle,
            override val width: Float,
            override val height: Float,
        ) : InlineUnit {
            override var y: Float = 0f
        }

        data class Word(
            val text: String,
            val style: UiInlineStyle,
            override val width: Float,
            override val height: Float,
        ) : InlineUnit {
            override var y: Float = 0f
        }

        data class Image(
            val image: UiInlineItem.Image,
        ) : InlineUnit {
            override val width: Float = image.width
            override val height: Float = image.height
            override var y: Float = 0f
        }

        data class Widget(
            val widget: UiInlineItem.Widget,
        ) : InlineUnit {
            override val width: Float = widget.width
            override val height: Float = widget.height
            val sourceLength: Int = widget.sourceLength.coerceAtLeast(0)
            override var y: Float = 0f
        }
    }

    private fun InlineUnit.resolveY(lineHeight: Float, baseline: Float) {
        y = when (this) {
            InlineUnit.Newline, is InlineUnit.Space -> 0f
            is InlineUnit.Word -> (lineHeight - height) / 2f
            is InlineUnit.Image -> when (image.align) {
                UiInlineAlign.BASELINE -> baseline - height
                UiInlineAlign.MIDDLE -> (lineHeight - height) / 2f
                UiInlineAlign.TOP -> 0f
                UiInlineAlign.BOTTOM -> lineHeight - height
            }

            is InlineUnit.Widget -> when (widget.align) {
                UiInlineAlign.BASELINE -> baseline - height
                UiInlineAlign.MIDDLE -> (lineHeight - height) / 2f
                UiInlineAlign.TOP -> 0f
                UiInlineAlign.BOTTOM -> lineHeight - height
            }
        }.coerceAtLeast(0f)
    }

    private data class LineCacheKey(
        val richText: UiRichText,
        val width: Float,
        val wrap: Boolean,
        val fontSize: Float,
        val fontFamily: String?,
        val preserveWhitespace: Boolean,
        val lineSpacing: Float,
        val spaceWidth: Float?,
        val fontSignature: Int,
    )

    private data class LayoutCacheKey(
        val richText: UiRichText,
        val width: Float,
        val height: Float,
        val wrap: Boolean,
        val align: UiTextAlign,
        val fontSize: Float,
        val fontFamily: String?,
        val preserveWhitespace: Boolean,
        val lineSpacing: Float,
        val spaceWidth: Float?,
        val fontSignature: Int,
    )

    private data class TextWidthCacheKey(
        val text: String,
        val style: UiInlineStyle,
        val fontSize: Float,
        val fontFamily: String?,
        val fontSignature: Int,
    )

    private class TextWidthCacheProbe {
        private var text = ""
        private var style = UiInlineStyle.Empty
        private var fontSize = 0f
        private var fontFamily: String? = null
        private var fontSignature = 0

        fun set(text: String, style: UiInlineStyle, fontSize: Float, fontFamily: String?, fontSignature: Int) {
            this.text = text
            this.style = style
            this.fontSize = fontSize
            this.fontFamily = fontFamily
            this.fontSignature = fontSignature
        }

        override fun hashCode(): Int = textWidthKeyHash(text, style, fontSize, fontFamily, fontSignature)

        override fun equals(other: Any?): Boolean = other is TextWidthCacheKey &&
                (text === other.text || text == other.text) &&
                (style === other.style || style == other.style) &&
                fontSize.compareTo(other.fontSize) == 0 &&
                fontFamily == other.fontFamily &&
                fontSignature == other.fontSignature
    }
}

private fun textWidthKeyHash(
    text: String,
    style: UiInlineStyle,
    fontSize: Float,
    fontFamily: String?,
    fontSignature: Int,
): Int {
    var result = text.hashCode()
    result = 31 * result + style.hashCode()
    result = 31 * result + fontSize.hashCode()
    result = 31 * result + (fontFamily?.hashCode() ?: 0)
    return 31 * result + fontSignature
}

private fun Float.cacheValue(): Float {
    if (!isFinite()) return this
    return (this * 100f).toInt().toFloat() / 100f
}

private fun <K, V> lruCache(maxSize: Int): MutableMap<K, V> {
    return object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }
}

fun UiTextLayout.caretPosition(index: Int, fontSize: Float, fontFamily: String? = null): UiVec3 {
    if (lines.isEmpty()) return UiVec3()
    val line = lines[lineIndexAtCaret(index)]
    val local = (index - line.sourceStart).coerceIn(0, line.sourceLength)
    return UiVec3(
        line.xAt(local, fontSize, fontFamily),
        line.y
    )
}

/** The caret's box (content-space) at [index]: a [thickness]-wide bar spanning the line's height. */
fun UiTextLayout.caretRect(index: Int, fontSize: Float, fontFamily: String? = null, thickness: Float = 1f): UiRect {
    if (lines.isEmpty()) return UiRect(0f, 0f, thickness, fontSize)
    val line = lines[lineIndexAtCaret(index)]
    val local = (index - line.sourceStart).coerceIn(0, line.sourceLength)
    return UiRect(line.xAt(local, fontSize, fontFamily), line.y, thickness, line.height)
}

fun UiTextLayout.caretIndexAt(x: Float, y: Float, fontSize: Float, fontFamily: String? = null): Int {
    if (lines.isEmpty()) return 0
    val line = lines.minBy { line ->
        when {
            y < line.y -> line.y - y
            y > line.y + line.height -> y - (line.y + line.height)
            else -> 0f
        }
    }
    var bestOffset = 0
    var bestDistance = Float.POSITIVE_INFINITY
    for (offset in line.caretOffsets()) {
        val distance = abs(line.xAt(offset, fontSize, fontFamily) - x)
        if (distance < bestDistance) {
            bestDistance = distance
            bestOffset = offset
        }
    }
    return line.sourceStart + bestOffset
}

fun UiTextLayout.selectionRects(
    start: Int,
    end: Int,
    fontSize: Float,
    fontFamily: String? = null,
    fillLineGaps: Boolean = false,
): List<UiRect> {
    val selectionStart = minOf(start, end).coerceAtLeast(0)
    val selectionEnd = maxOf(start, end).coerceAtLeast(selectionStart)
    if (selectionStart == selectionEnd) return emptyList()
    val rects = mutableListOf<UiRect>()
    val firstLine = firstLineIndexEndingAfterSource(selectionStart)
    val lastLineExclusive = firstLineIndexStartingAfterSource(selectionEnd)
    for (lineIndex in firstLine until lastLineExclusive) {
        val line = lines[lineIndex]
        val lineStart = line.sourceStart
        val lineEnd = line.sourceStart + line.sourceLength
        val startOffset = (selectionStart - lineStart).coerceIn(0, line.sourceLength)
        val endOffset = (selectionEnd - lineStart).coerceIn(0, line.sourceLength)
        if (selectionStart < lineEnd && selectionEnd > lineStart && startOffset != endOffset) {
            val nextLine = lines.getOrNull(lineIndex + 1)
            val height = if (fillLineGaps && selectionEnd > lineEnd && nextLine != null) {
                (nextLine.y - line.y).coerceAtLeast(line.height)
            } else {
                line.height
            }
            val lineRects = line.selectionRects(startOffset, endOffset, height, fontSize, fontFamily)
            if (fillLineGaps && lineRects.isEmpty() && line.text.isEmpty()) {
                rects += UiRect(0f, line.y, this.width, height)
            } else {
                rects += lineRects
            }
        } else if (fillLineGaps && line.sourceLength == 0 && selectionStart <= lineStart && selectionEnd >= lineEnd) {
            rects += UiRect(0f, line.y, this.width, line.height)
        }
    }
    return rects
}

private fun UiTextLine.selectionRects(
    startOffset: Int,
    endOffset: Int,
    rectHeight: Float,
    fontSize: Float,
    fontFamily: String?,
): List<UiRect> {
    if (fragments.isEmpty()) {
        val x1 = xAt(startOffset, fontSize, fontFamily)
        val x2 = xAt(endOffset, fontSize, fontFamily)
        val width = abs(x2 - x1)
        return if (width <= 0.5f) emptyList() else listOf(UiRect(minOf(x1, x2), y, width, rectHeight))
    }

    val rects = mutableListOf<UiRect>()
    var sourceCursor = 0
    fragments.forEach { fragment ->
        when (fragment) {
            is UiTextRun -> {
                val sourceEnd = sourceCursor + fragment.text.length
                val selectedStart = maxOf(startOffset, sourceCursor)
                val selectedEnd = minOf(endOffset, sourceEnd)
                if (selectedStart < selectedEnd) {
                    val localStart = selectedStart - sourceCursor
                    val localEnd = selectedEnd - sourceCursor
                    val size = fragment.style.resolvedFontSize(fontSize)
                    val family = fragment.style.fontFamily ?: fontFamily
                    val x1 = UiTextLayouter.measureTextWidth(fragment.text.substring(0, localStart), size, family)
                    val x2 = UiTextLayouter.measureTextWidth(fragment.text.substring(0, localEnd), size, family)
                    if (x2 > x1) {
                        rects += UiRect(x + fragment.x + x1, y, x2 - x1, rectHeight)
                    }
                }
                sourceCursor = sourceEnd
            }

            is UiTextSpaceRun -> {
                val sourceEnd = sourceCursor + 1
                if (startOffset < sourceEnd && endOffset > sourceCursor) {
                    rects += UiRect(x + fragment.x, y, fragment.width, rectHeight)
                }
                sourceCursor = sourceEnd
            }

            is UiInlineImageRun -> {
                val length = fragment.image.alt.ifBlank { "\uFFFC" }.length
                val sourceEnd = sourceCursor + length
                if (startOffset < sourceEnd && endOffset > sourceCursor) {
                    rects += UiRect(x + fragment.x, y, fragment.width, rectHeight)
                }
                sourceCursor = sourceEnd
            }

            is UiInlineWidgetRun -> {
                val length = fragment.widget.sourceLength
                val sourceEnd = sourceCursor + length
                if (startOffset < sourceEnd && endOffset > sourceCursor) {
                    rects += UiRect(x + fragment.x, y, fragment.width, rectHeight)
                }
                sourceCursor = sourceEnd
            }
        }
    }
    return rects
}

/** Extra margin above/below the viewport for [visibleLineItems] to keep laid out during scroll. */
internal const val DefaultLineOverscan = 24f

internal fun UiTextLayout.visibleLineItems(
    scrollY: Float,
    viewportHeight: Float,
    overscan: Float = DefaultLineOverscan,
): Sequence<IndexedValue<UiTextLine>> {
    if (lines.isEmpty()) return emptySequence()
    val top = scrollY - overscan
    val bottom = scrollY + viewportHeight + overscan
    val first = firstLineIndexEndingAtOrAfter(top)
    val endExclusive = firstLineIndexStartingAfter(bottom)
    if (first >= endExclusive) return emptySequence()
    return (first until endExclusive).asSequence().map { index -> IndexedValue(index, lines[index]) }
}

internal fun UiTextLayout.verticalCaretIndex(index: Int, lineDelta: Int, fontSize: Float, fontFamily: String?): Int {
    if (lines.isEmpty()) return 0
    val currentLineIndex = lineIndexAtCaret(index)
    val currentLine = lines[currentLineIndex]
    val localOffset = (index - currentLine.sourceStart).coerceIn(0, currentLine.sourceLength)
    val x = currentLine.xAt(localOffset, fontSize, fontFamily)
    val targetLineIndex = (currentLineIndex + lineDelta).coerceIn(0, lines.lastIndex)
    val targetLine = lines[targetLineIndex]
    if (targetLineIndex == currentLineIndex) {
        val targetOffset = if (lineDelta < 0) 0 else targetLine.sourceLength
        return targetLine.sourceStart + targetOffset
    }
    var bestOffset = 0
    var bestDistance = Float.POSITIVE_INFINITY
    for (offset in targetLine.caretOffsets()) {
        val distance = abs(targetLine.xAt(offset, fontSize, fontFamily) - x)
        if (distance < bestDistance) {
            bestDistance = distance
            bestOffset = offset
        }
    }
    return targetLine.sourceStart + bestOffset
}

private fun UiTextLayout.lineIndexAtCaret(index: Int): Int {
    if (lines.isEmpty()) return 0
    val target = index.coerceAtLeast(0)
    var low = 0
    var high = lines.lastIndex
    var result = lines.lastIndex
    while (low <= high) {
        val mid = low + high ushr 1
        val line = lines[mid]
        if (line.sourceStart + line.sourceLength > target || mid == lines.lastIndex) {
            result = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return result
}

private fun UiTextLayout.firstLineIndexEndingAtOrAfter(y: Float): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val mid = low + high ushr 1
        val line = lines[mid]
        if (line.y + line.height >= y) high = mid else low = mid + 1
    }
    return low
}

private fun UiTextLayout.firstLineIndexStartingAfter(y: Float): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val mid = low + high ushr 1
        if (lines[mid].y > y) high = mid else low = mid + 1
    }
    return low
}

private fun UiTextLayout.firstLineIndexEndingAfterSource(offset: Int): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val mid = low + high ushr 1
        val line = lines[mid]
        if (line.sourceStart + line.sourceLength > offset || line.sourceLength == 0 && line.sourceStart >= offset) {
            high = mid
        } else {
            low = mid + 1
        }
    }
    return low
}

private fun UiTextLayout.firstLineIndexStartingAfterSource(offset: Int): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val mid = low + high ushr 1
        if (lines[mid].sourceStart > offset) high = mid else low = mid + 1
    }
    return low
}

private fun UiTextLine.xAt(offset: Int, fontSize: Float, fontFamily: String?): Float {
    val textOffset = offset.coerceIn(0, sourceLength)
    if (fragments.isEmpty()) return x + UiTextLayouter.measureTextWidth(text.take(textOffset), fontSize, fontFamily)

    var remaining = textOffset
    var cursor = 0f
    for (fragment in fragments) {
        when (fragment) {
            is UiInlineImageRun -> {
                val imageTextLength = fragment.image.alt.ifBlank { "\uFFFC" }.length
                if (remaining < imageTextLength) return x + cursor
                remaining -= imageTextLength
                cursor = fragment.x + fragment.width
            }

            is UiInlineWidgetRun -> {
                val widgetTextLength = fragment.widget.sourceLength
                if (widgetTextLength == 0) {
                    cursor = fragment.x + fragment.width
                    continue
                }
                if (remaining < widgetTextLength) return x + cursor
                remaining -= widgetTextLength
                cursor = fragment.x + fragment.width
            }

            is UiTextSpaceRun -> {
                if (remaining == 0) return x + fragment.x
                remaining -= 1
                cursor = fragment.x + fragment.width
            }

            is UiTextRun -> {
                if (remaining < fragment.text.length) {
                    val size = fragment.style.resolvedFontSize(fontSize)
                    return x + fragment.x + UiTextLayouter.measureTextWidth(
                        fragment.text.take(remaining),
                        size,
                        fontFamily,
                    )
                }
                remaining -= fragment.text.length
                cursor = fragment.x + fragment.width
            }
        }
    }
    return x + cursor
}

/** A replacement widget has only boundary carets, regardless of how much source it represents. */
private fun UiTextLine.caretOffsets(): Sequence<Int> = sequence {
    yield(0)
    if (fragments.isEmpty()) {
        yieldAll(1..sourceLength)
        return@sequence
    }
    var offset = 0
    for (fragment in fragments) {
        val length = when (fragment) {
            is UiTextRun -> fragment.text.length
            is UiTextSpaceRun -> 1
            is UiInlineImageRun -> fragment.image.alt.ifBlank { "\uFFFC" }.length
            is UiInlineWidgetRun -> fragment.widget.sourceLength
        }
        if (fragment is UiInlineWidgetRun) {
            if (length > 0) yield(offset + length)
        } else yieldAll(offset + 1..offset + length)
        offset += length
    }
    if (offset < sourceLength) yield(sourceLength)
}
