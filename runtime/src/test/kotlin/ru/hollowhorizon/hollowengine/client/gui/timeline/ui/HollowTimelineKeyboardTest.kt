package ru.hollowhorizon.hollowengine.client.gui.timeline.ui

import androidx.compose.runtime.mutableStateOf
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.HollowUiSurface
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.HollowTimelineEditor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HollowTimelineKeyboardTest {
    @Test
    fun `active timeline takes the keys it uses and lets the rest reach the editor`() {
        HollowUiSurface().use { surface ->
            val active = mutableStateOf(true)
            val received = mutableListOf<Int>()
            surface.setContent {
                HollowTimelineEditor(
                    controller = TimelineController(),
                    refresh = {},
                    keyboardActive = active.value,
                    onKeyInput = { key, _ ->
                        received += key
                        key == GLFW.GLFW_KEY_SPACE
                    },
                )
            }

            surface.frame(800f, 500f, -1f, -1f, 0L)

            assertTrue(surface.runtime.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0))
            assertFalse(surface.runtime.keyPressed(GLFW.GLFW_KEY_W, 0, 0), "a key the timeline has no use for goes on")
            assertEquals(listOf(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_W), received)
            surface.runtime.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0, repeat = true)
            assertEquals(
                listOf(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_W),
                received,
                "repeats do not retrigger one-shot timeline commands",
            )

            active.value = false
            surface.frame(800f, 500f, -1f, -1f, 16_000_000L)
            assertFalse(surface.runtime.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0))
        }
    }
}
