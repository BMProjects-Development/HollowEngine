package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurveTransformTest {
    /** A hundred pixels a second and a hundred a unit of value, the value 0 in the middle of a 200 px graph. */
    private val view = CurveView(pxPerSec = 100f, center = 0f, span = 2f, height = 200f)

    private fun curve(controller: TimelineController, vararg keys: Keyframe): ChannelCurve {
        controller.workAreaEnd = 10f
        val property = controller.addProperty(listOf("Test"), AnimProperty("test.value", "Value", FloatPropertyType(), 0f))
        return property.curves.first().also { it.keyframes += keys }
    }

    @Test
    fun `scale stretches keys about their middle`() {
        val controller = TimelineController()
        val first = Keyframe(1f, 0f, handleMode = HandleMode.FREE)
        val second = Keyframe(3f, 0f, handleMode = HandleMode.FREE)
        curve(controller, first, second)
        controller.select(listOf(first, second), additive = false)

        val middle = view.x(2f)
        val transform = assertNotNull(CurveTransform.start(controller, GizmoEditMode.SCALE, view, middle + 50f, view.y(0f)))
        transform.update(middle + 100f, view.y(0f))
        transform.key(GLFW.GLFW_KEY_ENTER, 0)

        assertEquals(0f, first.time, 1e-4f)
        assertEquals(4f, second.time, 1e-4f)
    }

    @Test
    fun `a handle picked alone turns about its key, which stays`() {
        val controller = TimelineController()
        val key = Keyframe(1f, 0f, handleMode = HandleMode.FREE, outgoing = KeyTangent(0.5f, 0f))
        curve(controller, key, Keyframe(3f, 0f))
        controller.selectHandle(key, TangentSide.OUTGOING, additive = false)

        val transform =
            assertNotNull(CurveTransform.start(controller, GizmoEditMode.ROTATE, view, view.x(2f), view.y(0f)))
        transform.update(view.x(1f) + 0.0001f, view.y(1f))
        transform.key(GLFW.GLFW_KEY_ENTER, 0)

        assertEquals(1f, key.time, 1e-4f)
        assertEquals(0f, key.value, 1e-4f)
        assertEquals(0f, key.outgoing.time, 1e-3f)
        assertEquals(0.5f, key.outgoing.value, 1e-3f)
    }

    @Test
    fun `a move held to the time leaves values alone, and Escape puts keys back`() {
        val controller = TimelineController()
        val key = Keyframe(1f, 0.5f)
        curve(controller, key)
        controller.select(listOf(key), additive = false)

        val transform = assertNotNull(CurveTransform.start(controller, GizmoEditMode.TRANSLATE, view, 0f, 0f))
        transform.key(GLFW.GLFW_KEY_X, 0)
        transform.update(100f, 100f)
        assertEquals(2f, key.time, 1e-4f)
        assertEquals(0.5f, key.value, 1e-4f)

        transform.key(GLFW.GLFW_KEY_ESCAPE, 0)
        assertEquals(1f, key.time, 1e-4f)
        assertEquals(0.5f, key.value, 1e-4f)
    }

    @Test
    fun `undoing a transform keeps what was picked`() {
        val controller = TimelineController()
        val key = Keyframe(1f, 0f)
        curve(controller, key, Keyframe(3f, 0f))
        controller.select(listOf(key), additive = false)

        val transform = assertNotNull(CurveTransform.start(controller, GizmoEditMode.TRANSLATE, view, 0f, 0f))
        transform.update(50f, 0f)
        transform.key(GLFW.GLFW_KEY_ENTER, 0)
        controller.history.undo()

        assertEquals(1f, key.time, 1e-4f)
        assertTrue(controller.isSelected(key))
    }

    @Test
    fun `a moved key takes its handles along without stretching them`() {
        val controller = TimelineController()
        val key = Keyframe(1f, 0f, handleMode = HandleMode.FREE, outgoing = KeyTangent(0.5f, 0.25f))
        curve(controller, key, Keyframe(3f, 0f))
        controller.select(listOf(key), additive = false)

        val transform = assertNotNull(CurveTransform.start(controller, GizmoEditMode.TRANSLATE, view, 0f, 0f))
        transform.update(50f, -50f)
        transform.key(GLFW.GLFW_KEY_ENTER, 0)

        assertEquals(1.5f, key.time, 1e-4f)
        assertEquals(0.5f, key.value, 1e-4f)
        assertEquals(KeyTangent(0.5f, 0.25f), key.outgoing)
    }
}
