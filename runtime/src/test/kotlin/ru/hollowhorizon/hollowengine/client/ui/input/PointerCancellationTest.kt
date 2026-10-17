package ru.hollowhorizon.hollowengine.client.ui.input

import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointerCancellationTest {
    @Test
    fun `native handoff cancels the old gesture without release or click and keeps keyboard focus`() {
        var drags = 0
        var releases = 0
        var clicks = 0
        val source = BoxNode(
            id = "drag-source",
            modifiers = listOf(
                Modifier.size(100.px, 100.px).input(hoverable = true, clickable = true, draggable = true)
                    .focusScope().focus().onDrag { drags++ }
                    .onRelease { releases++; it.consume() }.onClick { clicks++ },
            ),
        )
        val runtime = HollowUiRuntime()
        runtime.frame(source, 200f, 200f, -1f, -1f, 0L)
        runtime.mouseClicked(10f, 10f, 0, 0)
        runtime.mouseDragged(30f, 10f, 0, 20f, 0f, 0)
        assertEquals(1, drags)
        assertEquals("drag-source", runtime.focusedKey)

        runtime.cancelPointerInput()
        runtime.cancelPointerInput() // Both handoff and native completion may clean up.
        runtime.mouseDragged(40f, 10f, 0, 10f, 0f, 0)
        assertEquals(1, drags)
        assertEquals(0, releases)
        assertEquals(0, clicks)
        assertEquals("drag-source", runtime.focusedKey)

        assertTrue(runtime.mouseClicked(10f, 10f, 0, 0))
        runtime.mouseDragged(30f, 10f, 0, 20f, 0f, 0)
        runtime.mouseReleased(30f, 10f, 0, 0)
        assertEquals(2, drags)
        assertEquals(1, releases)
    }
}
