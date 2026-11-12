package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor.CutsceneCameraNavigation
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CutsceneCameraNavigationTest {
    private val start = CutsceneCameraNavigation(CameraPose(fov = 90f), pivot = Vec3f(0f, 0f, 8f))

    @Test
    fun `orbit keeps the pivot centered`() {
        val turned = start.orbit(360f, 0f)

        assertEquals(90f, turned.pose.yaw)
        assertEquals(8f, turned.pose.position.x, 0.0001f)
        assertEquals(8f, turned.pose.position.z, 0.0001f)
        assertEquals(start.distance, turned.distance)
    }

    @Test
    fun `orbit preserves an off-center pivot in camera coordinates on both axes`() {
        val offCenter = start.copy(pivot = start.pose.viewportRay(0.8f, 0.2f, 1.6f) * 18f)
        val turned = offCenter.orbit(160f, 120f)
        val before = offCenter.pivot - offCenter.pose.position
        val after = turned.pivot - turned.pose.position

        assertEquals(40f, turned.pose.yaw)
        assertEquals(30f, turned.pose.pitch)
        assertEquals(offCenter.pivot, turned.pivot)
        assertEquals(before.length(), after.length(), 0.0001f)
        assertEquals(
            before dot offCenter.pose.direction(1f, 0f, 0f),
            after dot turned.pose.direction(1f, 0f, 0f),
            0.0001f
        )
        assertEquals(
            before dot offCenter.pose.direction(0f, 1f, 0f),
            after dot turned.pose.direction(0f, 1f, 0f),
            0.0001f
        )
    }

    @Test
    fun `pan and dolly keep the lens unchanged`() {
        val panned = start.pan(50f, -50f, 200f)
        val zoomed = panned.dolly(Vec3f.Z_AXIS, 1024f)

        assertEquals(4f, panned.pose.position.x, 0.0001f)
        assertEquals(-4f, panned.pose.position.y, 0.0001f)
        assertEquals(panned.pose.position.x, zoomed.pose.position.x, 0.0001f)
        assertTrue(zoomed.pose.position.z > 1000f)
        assertEquals(4f, panned.pivot.x, 0.0001f)
        assertEquals(-4f, panned.pivot.y, 0.0001f)
        assertEquals(Vec3f.ZERO, zoomed.pose.rotation)
        assertEquals(start.pose.fov, zoomed.pose.fov)
    }
}
