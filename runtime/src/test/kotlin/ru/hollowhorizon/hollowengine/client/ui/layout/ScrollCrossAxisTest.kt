package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A column only lets a child overflow the cross axis (keep its natural width) when it actually
 * scrolls that axis: horizontal scroll -> wide rows keep their width and produce a horizontal scroll
 * range; vertical-only scroll -> rows are clamped to the viewport (so their text can wrap).
 */
class ScrollCrossAxisTest {
    private fun wideRow(width: Float) = BoxNode(
        modifiers = listOf(Modifier.size(width.px, 20.px)),
    )

    private fun column(scroll: Modifier): Pair<UiLayoutResult, BoxNode> {
        val row = wideRow(600f)
        val column = BoxNode(
            id = "col",
            measurePolicy = UiMeasurePolicies.Column,
            modifiers = listOf(Modifier.size(200.px, 100.px).then(scroll)),
        ).also { it.children.add(row) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(column) }
        UiModifierResolver().resolve(root)
        return UiLayoutPipeline().compute(root, 400f, 400f, UiScrollState()) to row
    }

    @Test
    fun `horizontal scroll keeps a wide row's natural width and yields a scroll range`() {
        val (layout, row) = column(Modifier then scrollModifier())
        assertEquals(600f, layout.nodes.getValue(row).rect.width, 1f, "row keeps its 600px width")
        val colLayout = layout.nodes.getValue(layout.root.children.first())
        assertTrue(colLayout.scrollRange.x > 0f, "wide content produces a horizontal scroll range")
    }

    @Test
    fun `vertical-only scroll clamps a wide row to the viewport`() {
        val (layout, row) = column(Modifier then scrollModifier(horizontal = false))
        assertEquals(200f, layout.nodes.getValue(row).rect.width, 1f, "row clamped to the 200px viewport")
        val colLayout = layout.nodes.getValue(layout.root.children.first())
        assertEquals(0f, colLayout.scrollRange.x, 0.5f, "no horizontal scroll range")
    }
}
