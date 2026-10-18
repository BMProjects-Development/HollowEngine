import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditableFieldCompletionPopupTests {
    @Test
    fun `popup geometry stays inside a small editor viewport`() {
        val text = "first\nsecond\nthird"
        val layout = computeEditableFieldLayout(
            text = text,
            fontSize = 12f,
            fontFamily = null,
            wrap = false,
            viewportWidth = 120f,
        )

        val geometry = assertNotNull(
            editableFieldCompletionGeometry(
                layout = layout,
                anchor = text.length,
                items = List(20) { UiTextCompletion("VeryLongCompletionName$it") },
                scrollX = 0f,
                scrollY = 0f,
                viewportWidth = 120f,
                viewportHeight = 70f,
            ),
        )

        assertTrue(geometry.x >= 4f)
        assertTrue(geometry.x + geometry.width <= 116f)
        assertTrue(geometry.y >= 4f)
        assertTrue(geometry.y + geometry.height <= 66f)
        assertTrue(geometry.visibleRows < 10)
    }

    @Test
    fun `manual scrolling advances completion window independently of selection`() {
        val start = completionWindowStartIndex(
            totalCount = 500,
            selectedIndex = 0,
            selectedChanged = false,
            scrollOffset = 120 * 20f,
            rowHeight = 20f,
            visibleRows = 10,
        )

        assertEquals(108, start)
    }

    @Test
    fun `keyboard selection remains visible without changing manual scroll calculation`() {
        val scrollIndex = completionScrollRowIndex(
            totalCount = 500,
            scrollOffset = 120 * 20f,
            rowHeight = 20f,
            visibleRows = 10,
        )

        assertEquals(120, scrollIndex)
        assertEquals(191, completionSelectionRowIndex(500, 200, scrollIndex, 10))
        assertEquals(80, completionSelectionRowIndex(500, 80, scrollIndex, 10))
    }
}
