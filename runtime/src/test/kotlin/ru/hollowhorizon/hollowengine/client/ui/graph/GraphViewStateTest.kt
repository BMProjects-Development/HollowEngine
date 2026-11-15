package ru.hollowhorizon.hollowengine.client.ui.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphViewStateTest {
    @Test
    fun `edge drag pans continuously and keeps the grabbed graph point under the pointer`() {
        val view = GraphViewState()
        val drag = GraphDrag()
        drag.startX = view.toGraphX(200f)
        drag.startY = view.toGraphY(100f)
        var deltaX = 0f
        drag.update(395f, 100f) { deltaX = view.toGraphX(395f) - drag.startX }
        repeat(10) { drag.advance(view, 400f, 200f, 0.016f) }
        assertEquals(395f, view.toCanvasX(drag.startX + deltaX), 1e-3f)
        assert(view.panX < 60f)
    }

    @Test
    fun `zoom spring preserves drag coordinates while the pointer is stationary`() {
        val view = GraphViewState()
        val drag = GraphDrag()
        drag.startX = view.toGraphX(100f)
        drag.startY = view.toGraphY(100f)
        var deltaX = 0f
        drag.update(200f, 100f) { deltaX = view.toGraphX(200f) - drag.startX }
        view.zoomBy(2f, 300f, 100f)
        view.advance(1L)
        repeat(20) { step ->
            view.advance(1L + (step + 1) * 16_000_000L)
            drag.advance(view, 500f, 300f, 0.016f)
            assertEquals(200f, view.toCanvasX(drag.startX + deltaX), 1e-3f)
        }
    }
    @Test
    fun `a grabbed point stays under the pointer while a zoom is still easing in`() {
        val view = GraphViewState()
        view.zoomBy(2f, aroundX = 300f, aroundY = 200f)
        view.advance(1L)
        view.advance(16_000_001L)

        view.grab(100f, 100f)
        val grabbedX = view.toGraphX(100f)
        val grabbedY = view.toGraphY(100f)
        var frame = 16_000_001L
        repeat(20) { step ->
            view.dragTo(100f + step * 5f, 100f + step * 3f)
            frame += 16_000_000L
            view.advance(frame)
            assertEquals(grabbedX, view.toGraphX(100f + step * 5f), 1e-3f)
            assertEquals(grabbedY, view.toGraphY(100f + step * 3f), 1e-3f)
        }
    }
}
