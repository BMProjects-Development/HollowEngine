package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextOverflowScrollTest {
    @Test
    fun `a tall Text flow overflows a fixed scroll box and gets a scrollbar`() {
        val text = BoxNode(
            id = "para",
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(Modifier.size(UiLength.Fill, UiLength.Auto)),
        )
        repeat(200) { text.children.add(SpanNode("word$it ")) }
        val box = BoxNode(
            id = "scrollbox",
            modifiers = listOf(Modifier.size(200.px, 100.px).then(scrollModifier(horizontal = false))),
        ).also { it.children.add(text) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(box) }
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 1000f, 1000f, UiScrollState())

        val box2 = layout.nodes.getValue(box)
        val txt = layout.nodes.getValue(text)
        assertEquals(100f, box2.rect.height, 0.5f, "the box keeps its fixed height")
        assertTrue(txt.rect.height > 100f, "the flow reports its full natural height (overflow)")
        assertEquals(txt.rect.height - 100f, box2.scrollRange.y, 1f, "scroll range = overflow")
        assertEquals(1, layout.scrollbars[box]?.size ?: 0, "a vertical scrollbar is synthesized")
    }
}
