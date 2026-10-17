package ru.hollowhorizon.hollowengine.client.ui.input

import ru.hollowhorizon.hollowengine.client.ui.UiDragAndDropState
import ru.hollowhorizon.hollowengine.client.ui.UiDragItem
import ru.hollowhorizon.hollowengine.client.ui.UiDropTarget
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.test.*

class DropFeedbackTest {
    @Test
    fun `invalid row blocks the root and leaving clears folder feedback`() {
        val state = UiDragAndDropState()
        var drops = 0
        state.register(UiDropTarget("root", UiRect(0f, 0f, 200f, 200f), onDrop = { _, _, _ -> drops++; true }))
        state.register(UiDropTarget("file", UiRect(0f, 0f, 200f, 24f), accepts = { false }))
        state.register(UiDropTarget("folder", UiRect(0f, 24f, 200f, 24f), onDrop = { _, _, _ -> drops++; true }))
        state.begin(UiDragItem("file"), 10f, 30f)
        assertEquals("folder", state.hoveredTargetId)
        assertTrue(state.canDrop)
        state.move(10f, 10f)
        assertEquals("file", state.hoveredTargetId)
        assertFalse(state.canDrop)
        assertFalse(state.drop())
        assertEquals(0, drops)
        assertNull(state.hoveredTargetId)
        state.begin(UiDragItem("file"), 10f, 90f)
        assertEquals("root", state.hoveredTargetId)
        assertTrue(state.drop())
        assertEquals(1, drops)
        assertFalse(state.canDrop)
    }

    @Test
    fun `scrolled offscreen targets cannot receive a drop outside their viewport`() {
        val state = UiDragAndDropState()
        state.register(UiDropTarget("clipped", UiRect(0f, 0f, 200f, 24f), clip = UiRect(0f, 24f, 200f, 100f)))
        state.begin(UiDragItem("file"), 10f, 10f)
        assertFalse(state.canDrop)
        assertNull(state.hoveredTargetId)
        state.cancel()
        assertFalse(state.isDragging)
    }
}
