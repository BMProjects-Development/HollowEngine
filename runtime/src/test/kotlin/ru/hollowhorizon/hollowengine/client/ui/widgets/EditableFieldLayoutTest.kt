package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.TestFontFamily
import ru.hollowhorizon.hollowengine.client.ui.text.UiInlineWidgetRun
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The field's geometry model (pinned MonoCraft MSDF font: 6.667px/glyph, 10.898px line height).
 */
class EditableFieldLayoutTest {
    private val fontSize = 10f
    private fun layout(text: String, wrap: Boolean = false, viewportWidth: Float = 0f) = computeEditableFieldLayout(
        text,
        fontSize,
        fontFamily = TestFontFamily,
        wrap = wrap,
        viewportWidth = viewportWidth
    )

    @Test
    fun `non-wrapped lines stack at a uniform height and size to the widest line`() {
        val l = layout("abc\nxy\nlonger")
        assertEquals(0f, l.lineTop(0), 0.5f)
        assertEquals(10.898f, l.lineTop(1), 0.5f)
        assertEquals(21.796f, l.lineTop(2), 0.5f)
        assertEquals(40f + fontSize, l.contentWidth, 0.5f, "widest line 'longer' (6*6.667=40px) + trailing margin")
        assertTrue(l.lineLayouts.all { it == null }, "plain rows are laid out only while visible")
    }

    @Test
    fun `caret offset maps to a glyph boundary on the right line`() {
        val l = layout("abc\nxy\nlonger")
        assertEquals(0f, l.caretAt(0).let { it.x }, 0.5f)
        assertEquals(13.333f, l.caretAt(2).x, 0.5f, "after 'ab'")
        assertEquals(0f, l.caretAt(4).x, 0.5f) // start of "xy"
        assertEquals(10.898f, l.caretAt(4).y, 0.5f, "second line")
        assertEquals(21.796f, l.caretAt(7).y, 0.5f, "third line")
    }

    @Test
    fun `clicking maps back to the caret offset on the clicked row`() {
        val l = layout("abc\nxy\nlonger")
        assertEquals(2, l.offsetAt(12f, 0f), "column 2 on the first line")
        assertEquals(4, l.offsetAt(0f, 12f), "start of the second line")
        // Deep in a large document the bottom row still maps correctly (no jump to an early line).
        val big = layout((0 until 5000).joinToString("\n") { "line$it" })
        assertEquals("line4999", big.lines.last().text)
        val lastTop = big.lineTop(4999)
        assertEquals(big.lines[4999].start, big.offsetAt(0f, lastTop + 1f), "click on the last row")
    }


    @Test
    fun `caret x and vertical movement include inlay width at the same offset`() {
        val plain = layout("a\nzzzzzzzzzz")
        val hint = UiInlayHint(offset = 1, text = ": Int")
        val withInlay = computeEditableFieldLayout(
            text = "a\nzzzzzzzzzz",
            fontSize = fontSize,
            fontFamily = TestFontFamily,
            wrap = false,
            viewportWidth = 0f,
            inlayHints = listOf(hint),
            inlayMetrics = measuredInlays(hint, width = 30f, height = fontSize),
        )

        assertTrue(withInlay.caretAt(1).x > plain.caretAt(1).x + 20f, "caret accounts for trailing inlay")
        assertTrue(withInlay.visualCaretMove(1, 1) > 3, "down movement keeps the inlay-adjusted visual x")
    }

    @Test
    fun `clicking an inlay chooses the caret side from the hint midpoint`() {
        val hint = UiInlayHint(offset = 1, text = ": Int")
        val withInlay = computeEditableFieldLayout(
            text = "a",
            fontSize = fontSize,
            fontFamily = TestFontFamily,
            wrap = false,
            viewportWidth = 0f,
            inlayHints = listOf(hint),
            inlayMetrics = measuredInlays(hint, width = 30f, height = fontSize),
        )
        val visual = withInlay.lineLayouts.single()!!.lines.single()
        val slot = visual.fragments.filterIsInstance<UiInlineWidgetRun>().single()
        val leftQuarter = visual.x + slot.x + slot.width * 0.25f
        val rightQuarter = visual.x + slot.x + slot.width * 0.75f

        assertEquals(EditableFieldCaretHit(1, UiInlayCaretAffinity.BEFORE), withInlay.caretHitAt(leftQuarter, visual.y))
        assertEquals(EditableFieldCaretHit(1, UiInlayCaretAffinity.AFTER), withInlay.caretHitAt(rightQuarter, visual.y))
        assertTrue(
            withInlay.caretAt(1, UiInlayCaretAffinity.BEFORE).x < withInlay.caretAt(1, UiInlayCaretAffinity.AFTER).x,
        )
    }

    @Test
    fun `non-wrapped empty line selection paints one newline cell`() {
        val l = layout("x\n", wrap = false, viewportWidth = 120f)
        val emptyLine = l.lines[1]
        val emptyLineLayout = l.lineLayouts[1]
        val rects = selectionRectsForRow(
            line = emptyLine,
            lineLayout = emptyLineLayout,
            localStart = 0,
            localEnd = 0,
            crossesNewline = true,
            fontSize = fontSize,
            fontFamily = TestFontFamily,
            fullWidth = l.contentWidth,
        )

        assertEquals(120f, l.contentWidth, 0.5f)
        assertTrue(rects.single().width in 1f..fontSize)
    }

    @Test
    fun `the visible range is a band around the scroll offset, not the whole document`() {
        val l = layout((0 until 5000).joinToString("\n") { "l$it" })
        val range = l.visibleRange(scrollY = 1000f, viewportHeight = 100f, overscan = 0f)
        // 1000 / 10.898px puts the first visible line at 91 and the last at 100.
        assertTrue(range.first <= 91 && range.last >= 100, "the band covers what is on screen ($range)")
        assertTrue(range.last - range.first < 50, "only a band is composed")
    }

    @Test
    fun `the visible range holds still across small scrolls`() {
        val l = layout((0 until 5000).joinToString("\n") { "l$it" })
        val first = l.visibleRange(scrollY = 1000f, viewportHeight = 100f, overscan = 0f)
        val nudged = l.visibleRange(scrollY = 1005f, viewportHeight = 100f, overscan = 0f)
        assertEquals(first, nudged, "half a line of scrolling must not recompose the rows")

        val far = l.visibleRange(scrollY = 1400f, viewportHeight = 100f, overscan = 0f)
        assertTrue(far != first, "scrolling past the block does move the band")
    }

    @Test
    fun `wrapping splits a long line into taller rows`() {
        val l = layout("aaaa bbbb cccc dddd", wrap = true, viewportWidth = 60f)
        assertTrue(l.lineLayouts[0] != null, "line has a wrap layout")
        assertTrue(l.lineTop(1) > fontSize, "the wrapped first line is taller than one row")
    }

    @Test
    fun `indent guides continue through blank lines inside a block`() {
        val lines = editableFieldLines("    if (ready) {\n        run()\n\n        finish()\n    }")

        assertEquals(listOf(4), editableFieldIndentGuideColumns(lines, 2, indentSize = 4))
    }
}
