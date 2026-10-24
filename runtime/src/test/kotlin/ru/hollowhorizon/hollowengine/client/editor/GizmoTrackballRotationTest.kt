package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GizmoTrackballRotationTest {
    private val start = GizmoTransformValues(Vec3f.ZERO, QuatF.IDENTITY, Vec3f(1f, 1f, 1f))

    @Test
    fun `switching rotation modes stays in one edit and cancellation restores the target`() {
        val session = GizmoSession(projector())
        val target = Target(start)
        assertTrue(session.key(target, GLFW.GLFW_KEY_R, 0, 450f, 300f))
        session.moveTo(500f, 350f, 0)
        assertTrue(session.key(target, GLFW.GLFW_KEY_R, 0, 500f, 350f))
        session.moveTo(550f, 400f, 0)
        assertNotEquals(start.rotation, target.values.rotation)
        assertEquals(1, target.begun)
        assertEquals(0, target.ended)
        assertTrue(session.key(target, GLFW.GLFW_KEY_ESCAPE, 0, 550f, 400f))
        assertEquals(start, target.values)
        assertEquals(1, target.ended)
        assertNull(session.keyboard)
    }

    @Test
    fun `repeated rotate toggles the mode and axis constraints leave trackball`() {
        val projector = projector()
        val transform = GizmoKeyboardTransform(
            GizmoEditMode.ROTATE, start, 450f, 300f, projector, GizmoGeometry(projector), GizmoManipulator(projector),
        )
        assertEquals(GizmoRotationMode.VIEW_ALIGNED, transform.rotationMode)
        assertEquals(GizmoKeyResult.CHANGED, transform.key(GLFW.GLFW_KEY_R, 0))
        assertEquals(GizmoRotationMode.TRACKBALL, transform.rotationMode)
        assertNotEquals(start.rotation, assertNotNull(transform.update(500f, 350f, 0)).rotation)
        transform.key(GLFW.GLFW_KEY_R, 0)
        assertEquals(GizmoRotationMode.VIEW_ALIGNED, transform.rotationMode)
        transform.key(GLFW.GLFW_KEY_R, 0)
        transform.key(GLFW.GLFW_KEY_X, 0)
        assertEquals(GizmoRotationMode.VIEW_ALIGNED, transform.rotationMode)
        assertEquals(0, transform.axis)
        transform.key(GLFW.GLFW_KEY_R, 0)
        assertEquals(GizmoRotationMode.TRACKBALL, transform.rotationMode)
        assertNull(transform.axis)
    }

    @Test
    fun `trackball follows camera axes and recomputes from the original pose with snapping and fine motion`() {
        val trackball = GizmoTrackballRotation(projector(), start, 400f, 300f, GizmoSnapping.MODEL)
        assertEquals(start, trackball.update(400f, 300f, 0))
        val horizontal = assertNotNull(trackball.update(550f, 300f, 0))
        val front = Vec3f.Z_AXIS.rotateBy(horizontal.rotation)
        assertEquals(1f, front.x, 0.0001f)
        assertEquals(0f, front.z, 0.0001f)
        trackball.update(550f, 450f, 0)
        assertEquals(horizontal, trackball.update(550f, 300f, 0))
        val fine = assertNotNull(trackball.update(550f, 300f, GLFW.GLFW_MOD_SHIFT))
        val equivalent = assertNotNull(trackball.update(415f, 300f, 0))
        assertEquals(equivalent.rotation, fine.rotation)
        val snapped = assertNotNull(trackball.update(403f, 300f, GLFW.GLFW_MOD_CONTROL))
        assertEquals(start.rotation, snapped.rotation)
        assertEquals(start.translation, horizontal.translation)
        assertEquals(start.scale, horizontal.scale)
    }

    private fun projector() = GizmoProjector().also {
        it.capture(Matrix4f(), Matrix4f().perspective(1f, 800f / 600f, 0.1f, 100f), Vec3(0.0, 0.0, 5.0), 60f, 800f, 600f)
    }

    private class Target(private val start: GizmoTransformValues) : GizmoTarget {
        override var values = start
        var begun = 0
        var ended = 0

        override fun place(values: GizmoTransformValues, mode: GizmoEditMode) { this.values = values }
        override fun restore() { values = start }
        override fun beginEdit() { begun++ }
        override fun endEdit() { ended++ }
    }
}
