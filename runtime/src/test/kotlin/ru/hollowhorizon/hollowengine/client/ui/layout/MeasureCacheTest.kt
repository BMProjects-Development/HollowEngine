package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The layout pipeline memoizes measurement per node on `subtreeLayoutRevision`. These tests pin
 * the two behaviors that matter: a scroll-only relayout reuses measured sizes but re-places
 * content at the new offset, and a real content change still re-measures instead of serving a
 * stale cached size.
 */
class MeasureCacheTest {
    /** Mirrors the Compose applier, which attaches every inserted node's layout state to its parent. */
    private fun <T : UiNode> T.attached(): T = apply {
        children.forEach { it.layoutState.attachTo(this); it.attached() }
    }

    private val scroll = UiScrollHandle()

    private fun scrollableTree(): Triple<BoxNode, BoxNode, BoxNode> {
        val item = BoxNode(id = "item", modifiers = listOf(Modifier.size(40.px, 300.px)))
        val viewport = BoxNode(
            id = "viewport",
            measurePolicy = UiMeasurePolicies.box(),
            modifiers = listOf(Modifier.size(100.px, 100.px) then scrollModifier(horizontal = false, state = scroll)),
        ).also { it.children.add(item) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(viewport) }.attached()
        return Triple(root, viewport, item)
    }

    @Test
    fun `scrolling rebuilds placement with shifted content and unchanged sizes`() {
        val (root, viewport, item) = scrollableTree()
        val runtime = HollowUiRuntime()

        val before = runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        assertEquals(200f, before.layout[viewport].scrollRange.y, "content overflows by 200px")
        runtime.setScrollImmediate(scroll, UiScrollOffset(0f, 50f))
        val after = runtime.frame(root, 200f, 200f, -1f, -1f, 16L)

        assertNotSame(before.layout, after.layout, "a scroll change rebuilds the layout")
        assertEquals(50f, after.layout[viewport].scrollOffset.y, "the new offset is applied")
        assertEquals(
            before.layout[item].rect.y - 50f,
            after.layout[item].rect.y,
            "scrolled content shifts by the scroll delta",
        )
        assertEquals(before.layout[item].rect.width, after.layout[item].rect.width)
        assertEquals(before.layout[item].rect.height, after.layout[item].rect.height)
    }

    @Test
    fun `a content size change after cached frames is re-measured`() {
        val (root, viewport, item) = scrollableTree()
        val runtime = HollowUiRuntime()

        runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        runtime.setScrollImmediate(scroll, UiScrollOffset(0f, 50f))
        runtime.frame(root, 200f, 200f, -1f, -1f, 16L)

        // Grow the item; the measure cache must not serve the old 300px height.
        item.modifiers.clear()
        item.modifiers += Modifier.size(40.px, 500.px)
        val grown = runtime.frame(root, 200f, 200f, -1f, -1f, 32L)

        assertEquals(500f, grown.layout[item].rect.height, "the new size is measured")
        assertTrue(
            grown.layout[viewport].scrollRange.y > 300f,
            "the scroll range reflects the grown content",
        )
    }
}
