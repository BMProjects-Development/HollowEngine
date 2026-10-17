package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.mutableStateOf
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditableTextFieldScrollTest {
    @Test
    fun `resizing a single line field does not create a transient scrollbar while text still fits`() {
        HollowUiSurface().use { surface ->
            val width = mutableStateOf(100f)
            surface.setContent {
                TextField(
                    value = "12.345",
                    id = "field",
                    modifier = Modifier.size(width.value.px, 22.px).padding(5.px, 0.px),
                )
            }

            surface.frame(120f, 40f, -1f, -1f, 0L)
            surface.frame(120f, 40f, -1f, -1f, 16_000_000L)

            width.value = 55f
            val frame = surface.frame(120f, 40f, -1f, -1f, 32_000_000L)
            val field = frame.nodes.single { it.id == "field" }

            assertEquals(0f, frame.layout[field].scrollRange.x, 0.01f)
            assertTrue(
                frame.layout.scrollbars[field].isNullOrEmpty(),
                "the first frame after a resize must use the new viewport, not the previous frame's width",
            )
        }
    }

    @Test
    fun `single line field scrolls overflowing text without growing or showing a bar`() {
        HollowUiSurface().use { surface ->
            surface.setContent {
                TextField(
                    value = "12345678901234567890",
                    id = "field",
                    modifier = Modifier.size(55.px, 22.px).padding(5.px, 0.px),
                )
            }

            surface.frame(100f, 40f, -1f, -1f, 0L)
            val frame = surface.frame(100f, 40f, -1f, -1f, 16_000_000L)
            val field = frame.nodes.single { it.id == "field" }

            assertTrue(frame.layout[field].scrollRange.x > 0f, "the text still scrolls")
            assertTrue(
                frame.layout.scrollbars[field].isNullOrEmpty(),
                "a one-line field is too short for a bar. It must stay hidden",
            )
            assertEquals(22f, frame.layout[field].rect.height, 0.01f, "the field keeps its declared height")
            assertEquals(22f, frame.layout[field].content.height, 0.01f, "and nothing is reserved inside it")
        }
    }
}
