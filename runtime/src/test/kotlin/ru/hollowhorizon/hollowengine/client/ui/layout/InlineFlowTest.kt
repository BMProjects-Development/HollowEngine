package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import ru.hollowhorizon.hollowengine.client.ui.text.Bold
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextFonts
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineFlowTest {
    /** A line is as tall as the test font's line, a little taller than its 10px em. */
    private fun lineOf(fontSize: Float) = UiTextFonts.resolve(TestFontFamily).lineHeight(fontSize)
    private val Line = lineOf(10f)

    private fun span(text: String, vararg mods: Modifier) =
        SpanNode(text, modifiers = mods.toList())

    private fun flow(
        width: Float,
        vararg children: UiNode,
        sheet: String? = null,
        tags: List<String> = emptyList(),
    ): Pair<UiLayoutResult, BoxNode> {
        val container = BoxNode(
            id = "flow",
            tags = tags,
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(Modifier.size(width.px, UiLength.Auto)),
        )
        children.forEach { container.children.add(it) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column, modifiers = listOf(TestFontStyle)).also { it.children.add(container) }
        val resolver = sheet?.let { UiModifierResolver(stylesheet = compileHss(it)) } ?: UiModifierResolver()
        resolver.resolve(root)
        return UiLayoutPipeline().compute(root, 1000f, 1000f, UiScrollState()) to container
    }

    @Test
    fun `words wrap at the container width and height grows per line`() {
        // "aaaa bbbb cccc" @6px/char: words 24px + space 6 => line1 "aaaa bbbb"=54, "cccc" wraps.
        val s = span("aaaa bbbb cccc")
        val (layout, container) = flow(60f, s)
        val containerLayout = layout.nodes.getValue(container)
        assertEquals(Line * 2f, containerLayout.rect.height, 0.6f, "two 10px lines")

        val spanLayout = layout.nodes.getValue(s)
        assertEquals(2, spanLayout.textLayout!!.lines.size, "span split across two lines")
    }

    @Test
    fun `single line when everything fits`() {
        val s = span("aa bb")
        val (layout, container) = flow(200f, s)
        assertEquals(Line, layout.nodes.getValue(container).rect.height, 0.6f)
        assertEquals(1, layout.nodes.getValue(s).textLayout!!.lines.size)
    }

    @Test
    fun `an oversized word keeps placed chunks and source offsets`() {
        val s = span("abcdef")
        val (layout, container) = flow(21f, s)
        val textLayout = layout.nodes.getValue(s).textLayout!!

        assertEquals(2, textLayout.lines.size)
        assertEquals(Line * 2f, layout.nodes.getValue(container).rect.height, 0.6f)
        assertEquals(0, textLayout.lines[0].sourceStart)
        assertEquals(3, textLayout.lines[0].sourceLength)
        assertEquals(3, textLayout.lines[1].sourceStart)
        assertEquals(3, textLayout.lines[1].sourceLength)
    }

    @Test
    fun `a fit-content padded box does not wrap its own text at fractional glyph widths`() {
        for (fontSize in listOf(10f, 13f, 17f, 23f, 29f)) {
            val label = span("align: right", Modifier.fontSize(fontSize))
            val text = BoxNode(id = "pill-text", measurePolicy = UiMeasurePolicies.InlineFlow)
                .also { it.children.add(label) }
            val pill = BoxNode(
                id = "pill",
                measurePolicy = UiMeasurePolicies.Row,
                modifiers = listOf(Modifier.padding(6.px, 2.px)),
            ).also { it.children.add(text) }
            val row = BoxNode(measurePolicy = UiMeasurePolicies.Row).also { it.children.add(pill) }
            val root = BoxNode(measurePolicy = UiMeasurePolicies.Column, modifiers = listOf(TestFontStyle)).also { it.children.add(row) }
            UiModifierResolver().resolve(root)
            UiLayoutPipeline().compute(root, 2000f, 400f, UiScrollState())

            assertEquals(1, label.lineLayout!!.lines.size, "fontSize $fontSize: pill hugs its label on one line")
        }
    }

    @Test
    fun `a max constrained fit flow hugs wrapped lines and measures their full height`() {
        val signature = span(
            "(screen: ResourceLocation, kind: UiSurfaceKind, charDelay: Int, choiceRevealDelay: Long)",
        )
        val container = BoxNode(
            id = "signature",
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(
                Modifier.size(UiLength.Fit, UiLength.Fit)
                    .maxSize(width = 260.px)
                    .textWrap(true),
            ),
        ).also { it.children.add(signature) }
        val root = BoxNode(
            measurePolicy = UiMeasurePolicies.Column,
            modifiers = listOf(TestFontStyle),
        ).also { it.children.add(container) }
        UiModifierResolver().resolve(root)

        val layout = UiLayoutPipeline().compute(root, 600f, 400f, UiScrollState())
        val containerRect = layout.nodes.getValue(container).rect
        val textLayout = layout.nodes.getValue(signature).textLayout!!

        assertTrue(textLayout.lines.size > 1, "the signature must wrap at the maximum width")
        assertEquals(textLayout.width, containerRect.width, 0.6f, "fit width must hug the longest wrapped line")
        assertEquals(textLayout.height, containerRect.height, 0.6f, "fit height must include every wrapped line")
    }


    @Test
    fun `newline forces a hard break`() {
        val s = span("aa\nbb")
        val (layout, _) = flow(500f, s)
        assertEquals(2, layout.nodes.getValue(s).textLayout!!.lines.size)
    }

    @Test
    fun `text-wrap off keeps one line but a newline still breaks`() {
        val wide = span("aaaa bbbb cccc dddd")
        val container = BoxNode(
            id = "flow",
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(Modifier.size(60.px, UiLength.Auto).textWrap(false)),
        ).also { it.children.add(wide) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column, modifiers = listOf(TestFontStyle)).also { it.children.add(container) }
        UiModifierResolver().resolve(root)
        val noWrap = UiLayoutPipeline().compute(root, 1000f, 1000f, UiScrollState())
        assertEquals(1, noWrap.nodes.getValue(wide).textLayout!!.lines.size, "no wrap: single line")

        val hard = span("aa\nbb")
        val c2 = BoxNode(
            id = "flow",
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(Modifier.size(60.px, UiLength.Auto).textWrap(false)),
        ).also { it.children.add(hard) }
        val root2 = BoxNode(measurePolicy = UiMeasurePolicies.Column, modifiers = listOf(TestFontStyle)).also { it.children.add(c2) }
        UiModifierResolver().resolve(root2)
        val layout2 = UiLayoutPipeline().compute(root2, 1000f, 1000f, UiScrollState())
        assertEquals(2, layout2.nodes.getValue(hard).textLayout!!.lines.size, "newline always breaks")
    }

    @Test
    fun `atom widgets flow inline between spans and wrap`() {
        val a = span("aaaa ")              // 24 + trailing space
        val box = BoxNode(modifiers = listOf(Modifier.size(30.px, 16.px)))
        val b = span(" bbbb")              // leading space + 24
        val (layout, container) = flow(60f, a, box, b)
        val boxRect = layout.nodes.getValue(box).rect
        val containerRect = layout.nodes.getValue(container).rect
        assertTrue(containerRect.height > 16f, "flow wrapped into multiple lines (h=${containerRect.height})")
        assertTrue(boxRect.width == 30f && boxRect.height == 16f, "atom keeps its size")
    }

    @Test
    fun `justify stretches non-final lines to the full width`() {
        val s = span("aaaa bbbb cccc dddd")
        val (layout, _) = flow(60f, s, sheet = "#flow { align-items: justify start; }")
        val spanLayout = layout.nodes.getValue(s).textLayout!!
        assertTrue(spanLayout.lines.size >= 2)
        val first = spanLayout.lines.first()
        val lastRun = first.fragments.maxBy { it.x }
        val spanRect = layout.nodes.getValue(s).rect
        val lineEnd = spanRect.x + lastRun.x + lastRun.width
        assertEquals(60f, lineEnd - layout.nodes.getValue(s).let { spanRect.x }, 1.5f, "first line stretched to 60px")
    }

    @Test
    fun `text-align positions the line left, center and right`() {
        fun wordX(align: String): Float {
            val s = span("aa") // 12px wide in a 100px flow
            val (layout, container) = flow(100f, s, sheet = "#flow { text-align: $align; }")
            return layout.nodes.getValue(s).rect.x - layout.nodes.getValue(container).content.x
        }
        assertEquals(0f, wordX("left"), 0.5f)
        assertEquals(43.333f, wordX("center"), 1f)   // (100 - 13.333) / 2
        assertEquals(86.667f, wordX("right"), 1f)     // 100 - 13.333
    }

    @Test
    fun `bold reserves extra width so the next word keeps its space`() {
        val plain = span("aa")
        val bold = span("aa", Modifier.textEffects(Bold()))
        val (l1, _) = flow(500f, plain)
        val (l2, _) = flow(500f, bold)
        val plainW = l1.nodes.getValue(plain).rect.width
        val boldW = l2.nodes.getValue(bold).rect.width
        assertTrue(boldW > plainW + 1f, "bold word is wider (bold=$boldW plain=$plainW)")
    }

    @Test
    fun `a comma glued to the previous word does not wrap alone`() {
        val word = span("aaaaaaaaaa")
        val comma = span(",")
        val (layout, container) = flow(67f, word, comma)
        assertEquals(Line, layout.nodes.getValue(container).rect.height, 0.6f, "single line, comma glued to the word")
    }

    @Test
    fun `a word split across spans wraps as one word`() {
        val revealed = span("aaaa bb")
        val pending = span("bb")
        val (layout, container) = flow(42f, revealed, pending)
        val revealedLayout = layout.nodes.getValue(revealed)

        assertEquals(Line * 2f, layout.nodes.getValue(container).rect.height, 0.6f, "the whole word moves to the next line")
        assertEquals(
            revealedLayout.rect.y + revealedLayout.textLayout!!.lines.last().y,
            layout.nodes.getValue(pending).rect.y,
            0.1f,
            "both parts of the split word stay on the same line",
        )
    }

    @Test
    fun `spans keep their own font size in the flow`() {
        val small = span("aa")
        val big = span("aa", Modifier.fontSize(20f))
        val (layout, container) = flow(500f, small, big)
        assertEquals(lineOf(20f), layout.nodes.getValue(container).rect.height, 0.6f, "line height = tallest span")
        val bigRect = layout.nodes.getValue(big).rect
        assertEquals(26.667f, bigRect.width, 1.0f)
    }
}
