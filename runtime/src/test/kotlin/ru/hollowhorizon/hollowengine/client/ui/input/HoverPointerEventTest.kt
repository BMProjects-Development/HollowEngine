package ru.hollowhorizon.hollowengine.client.ui.input

import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class HoverPointerEventTest {
    @Test
    fun `hover event contains frame and pointer coordinates local to target`() {
        var event: UiEvent? = null
        HollowUiSurface().use { surface ->
            surface.setContent {
                Box(
                    id = "hover-target",
                    modifier = Modifier.size(100.px, 60.px).onHover { event = it },
                )
            }

            val frame = surface.frame(200f, 120f, 35f, 24f, 0L)
            val hover = assertNotNull(event)

            assertSame(frame, hover.frame)
            assertEquals(35f, hover.x)
            assertEquals(24f, hover.y)
            assertEquals(35f, hover.localX)
            assertEquals(24f, hover.localY)
        }
    }
}
