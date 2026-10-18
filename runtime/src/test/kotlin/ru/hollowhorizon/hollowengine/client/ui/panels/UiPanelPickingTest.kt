package ru.hollowhorizon.hollowengine.client.ui.panels

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.test.*

class UiPanelPickingTest {
    @Test
    fun `entity in front of panel blocks interaction even beyond vanilla reach`() {
        assertTrue(UiPanelPicking.isOccluded(10.0, null, 8.0))
        assertTrue(UiPanelPicking.isOccluded(5.0, null, 4.99))
    }

    @Test
    fun `entity behind panel does not block interaction`() {
        assertFalse(UiPanelPicking.isOccluded(5.0, null, 6.0))
        assertFalse(UiPanelPicking.isOccluded(5.0, null, null))
    }

    @Test
    fun `block face tolerance does not let interactions pass through entities`() {
        assertFalse(UiPanelPicking.isOccluded(5.0, 4.99, null))
        assertTrue(UiPanelPicking.isOccluded(5.0, 4.9, null))
        assertTrue(UiPanelPicking.isOccluded(5.0, 4.99, 4.99))
    }

    @Test
    fun `translated and scaled panels report world distance and normalized coordinates`() {
        val placement = Matrix4f().translation(3f, 4f, 0f).scale(2f, 4f, 1f)
        val hit = assertNotNull(UiPanelPicking.hitOf(placement, 3.5, 5.0, 7.0, Vector3f(0f, 0f, -1f)))

        assertEquals(7.0, hit.first, 0.000001)
        assertEquals(0.75f, hit.second.first, 0.000001f)
        assertEquals(0.25f, hit.second.second, 0.000001f)
    }

    @Test
    fun `back facing panels and rays outside the panel cannot be selected`() {
        val placement = Matrix4f()
        assertNull(UiPanelPicking.hitOf(placement, 0.0, 0.0, -2.0, Vector3f(0f, 0f, 1f)))
        assertNull(UiPanelPicking.hitOf(placement, 0.0, 0.0, 2.0, Vector3f(0f, 0f, 1f)))
        assertNull(UiPanelPicking.hitOf(placement, 2.0, 0.0, 2.0, Vector3f(0f, 0f, -1f)))
    }
}
