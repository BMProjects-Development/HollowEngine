package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.HssCompiler
import ru.hollowhorizon.hollowengine.client.ui.style.HssParser
import ru.hollowhorizon.hollowengine.client.ui.style.UiTransition
import ru.hollowhorizon.hollowengine.client.ui.style.translate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StoryReorderTest {
    @Test
    fun `visible handle drags immediately at graph zoom and commits on release`() {
        val drag = StoryReorder()
        var order = listOf("a", "b", "c")
        val stylesheet = HssCompiler().compile(
            HssParser(
                """
            .st-reorder-handle { size: 16px 20px; }
            .st-reorder-icon { size: 10px 14px; }
        """.trimIndent()
            ).parse()
        )
        HollowUiSurface(stylesheet = stylesheet).use { surface ->
            surface.setContent {
                Box(
                    id = "anchor",
                    mode = UiBoxMode.STACK,
                    modifier = Modifier.size(100.px, 100.px).pivot(0.px, 0.px).scale(2f)
                ) {
                    Box(id = "drag-row", modifier = Modifier.size(100.px, 25.px).reorderOffset(drag, 0, 25f)) {
                        StoryReorderHandle("handle", 0, 3, 25f, 2f, drag, true, "anchor") { from, to ->
                            order = order.reordered(from, to)
                        }
                    }
                }
            }
            val frame = surface.frame(200f, 200f, 10f, 10f, 1_000_000L)
            assertNotNull(frame.nodeByIdentifier("st-reorder-icon"))
            surface.runtime.mouseClicked(10f, 10f, 0)
            surface.frame(200f, 200f, 10f, 10f, 9_000_000L)
            surface.runtime.mouseDragged(10f, 40f, 0, 0f, 30f, 0)
            assertEquals(15f, drag.offset(0, 25f))
            val moved = surface.frame(200f, 200f, 10f, 40f, 17_000_000L)
            assertEquals(15f, assertNotNull(moved.nodeByIdentifier("drag-row")).resolvedSnapshot.translate.y)
            surface.runtime.mouseReleased(10f, 40f, 0)
            assertEquals(listOf("b", "a", "c"), order)
        }
    }

    @Test
    fun `held row snaps on the next frame while displaced rows animate`() {
        val drag = StoryReorder()
        HollowUiSurface().use { surface ->
            surface.setContent {
                Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.px, 100.px)) {
                    repeat(3) { index ->
                        Box(
                            id = "row-$index",
                            tags = listOf("row"),
                            modifier = Modifier.position(0.px, (index * 25).px).size(100.px, 25.px)
                                .transition(UiTransition("translate", 500)).reorderOffset(drag, index, 25f)
                        )
                    }
                }
            }
            surface.frame(100f, 100f, 0f, 0f, 1_000_000L)
            drag.start(0)
            drag.move(15f, 25f, 3)
            val frame = surface.frame(100f, 100f, 0f, 0f, 17_000_000L)
            assertEquals(15f, assertNotNull(frame.nodeByIdentifier("row-0")).resolvedSnapshot.translate.y)
            assertEquals(0f, assertNotNull(frame.nodeByIdentifier("row-1")).resolvedSnapshot.translate.y)
            val later = surface.frame(100f, 100f, 0f, 0f, 77_000_000L)
            val neighbour = assertNotNull(later.nodeByIdentifier("row-1")).resolvedSnapshot.translate.y
            kotlin.test.assertTrue(neighbour < 0f && neighbour > -25f)
        }
    }

    @Test
    fun `drag stays within the section and can move back after reaching an edge`() {
        val drag = StoryReorder()
        drag.start(1)
        drag.move(500f, 25f, 3)
        assertEquals(25f, drag.offset(1, 25f))
        assertEquals(-25f, drag.offset(2, 25f))
        drag.move(-500f, 25f, 3)
        assertEquals(-25f, drag.offset(1, 25f))
        assertEquals(25f, drag.offset(0, 25f))
        drag.move(0f, 25f, 3)
        assertEquals(0f, drag.offset(0, 25f))
        var committed = false
        drag.finish { _, _ -> committed = true }
        assertEquals(false, committed)
    }

    @Test
    fun `a single row cannot be dragged out of its section`() {
        val drag = StoryReorder()
        drag.start(0)
        drag.move(100f, 25f, 1)
        assertEquals(0f, drag.offset(0, 25f))
    }

    @Test
    fun `drag previews displaced rows and commits their order once`() {
        val drag = StoryReorder()
        drag.start(0)
        drag.move(50f, 25f, 3)
        assertEquals(50f, drag.offset(0, 25f))
        assertEquals(-25f, drag.offset(1, 25f))
        var values = listOf("a", "b", "c")
        drag.finish { from, to -> values = values.reordered(from, to) }
        assertEquals(listOf("b", "c", "a"), values)
        assertEquals(0f, drag.offset(1, 25f))
    }
}
