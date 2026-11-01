import ru.hollowhorizon.hollowengine.client.ui.render.UiBatchBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiBatchBoundsTest {
    @Test
    fun `a quad between pending quads does not overlap them`() {
        val bounds = UiBatchBounds()
        bounds.add(0f, 0f, 10f, 10f)
        bounds.add(100f, 100f, 110f, 110f)
        assertFalse(bounds.overlaps(50f, 50f, 60f, 60f))
        assertTrue(bounds.overlaps(105f, 105f, 120f, 120f))
    }

    @Test
    fun `no quad is forgotten past the rects it keeps apart`() {
        val bounds = UiBatchBounds()
        repeat(500) { bounds.add(it * 20f, 0f, it * 20f + 10f, 10f) }
        repeat(500) { assertTrue(bounds.overlaps(it * 20f + 1f, 1f, it * 20f + 2f, 2f), "quad $it") }
    }
}
