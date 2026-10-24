package ru.hollowhorizon.hollowengine.client.ui.input

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals

class FocusTest {
    @Test
    fun `toolbar clicks preserve the viewport focus and an active panel gets first use of keys`() {
        val log = mutableListOf<String>()
        val revision = mutableStateOf(0)
        HollowUiSurface().use { surface ->
            surface.setContent {
                Row(modifier = Modifier.focusScope()) {
                    Box(id = "preview-scope", modifier = Modifier.size(100.px, 100.px).focusScope()) {
                        key(revision.value) {
                            Box(id = "viewport", modifier = Modifier.size(100.px, 100.px).focus().onKeyInput {
                                log += "viewport"
                                it.consume()
                            })
                        }
                    }
                    Box(id = "toolbar", modifier = Modifier.size(100.px, 100.px)
                        .input(clickable = true).onClick { revision.value++; it.consume() })
                    Box(id = "timeline", modifier = Modifier.size(100.px, 100.px).focusScope()
                        .input(clickable = true).onKeyInput {
                            log += "timeline"
                            it.consume()
                        })
                }
            }
            val runtime = surface.runtime
            surface.frame(300f, 100f, -1f, -1f, 0L)
            runtime.mouseClicked(50f, 50f, GLFW.GLFW_MOUSE_BUTTON_LEFT)
            assertEquals("viewport", runtime.focusedKey)
            runtime.mouseClicked(150f, 50f, GLFW.GLFW_MOUSE_BUTTON_LEFT)
            surface.frame(300f, 100f, -1f, -1f, 16_000_000L)
            assertEquals("viewport", runtime.focusedKey)
            runtime.keyPressed(GLFW.GLFW_KEY_R, 0, 0)
            assertEquals(listOf("viewport"), log)

            log.clear()
            runtime.mouseClicked(250f, 50f, GLFW.GLFW_MOUSE_BUTTON_LEFT)
            runtime.keyPressed(GLFW.GLFW_KEY_G, 0, 0)
            assertEquals(listOf("timeline"), log)
        }
    }

    private fun keyLoggingBox(id: String, layer: Int, log: MutableList<String>, consume: Boolean): BoxNode {
        // A focus scope is always key-active while composed, so it is a self-contained multi-focus target.
        val mod = Modifier.focusScope().layer(layer).onKeyInput { input ->
            log += id
            if (consume) input.consume()
        }
        return BoxNode(id = id, modifiers = listOf(mod))
    }

    @Test
    fun `keys reach every focus-active node, highest layer first`() {
        val log = mutableListOf<String>()
        val top = keyLoggingBox("top", layer = 10, log, consume = false)
        val bottom = keyLoggingBox("bottom", layer = 0, log, consume = false)
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column)
            .also { it.children.add(top); it.children.add(bottom) }
        val runtime = HollowUiRuntime()
        runtime.frame(root, 200f, 200f, -1f, -1f, 0L)

        runtime.keyPressed(GLFW.GLFW_KEY_A, 0, 0)
        assertEquals(listOf("top", "bottom"), log, "both focus-active nodes get the key, top layer first")
    }

    @Test
    fun `a consuming focus-active node stops lower-priority ones`() {
        val log = mutableListOf<String>()
        val top = keyLoggingBox("top", layer = 10, log, consume = true)
        val bottom = keyLoggingBox("bottom", layer = 0, log, consume = false)
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column)
            .also { it.children.add(top); it.children.add(bottom) }
        val runtime = HollowUiRuntime()
        runtime.frame(root, 200f, 200f, -1f, -1f, 0L)

        runtime.keyPressed(GLFW.GLFW_KEY_A, 0, 0)
        assertEquals(listOf("top"), log, "top consumes, bottom never sees the key")
    }
}
