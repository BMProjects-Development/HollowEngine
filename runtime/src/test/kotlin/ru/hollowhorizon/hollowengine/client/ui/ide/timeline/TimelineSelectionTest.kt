package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimelineSelectionTest {
    @Test
    fun `select all includes folded groups but skips locked and unlisted channels`() {
        val timeline = TimelineController()
        val folded = property(timeline, "folded")
        timeline.group(listOf("folded")).isCollapsed = true
        val locked = property(timeline, "locked")
        timeline.group(listOf("locked")).isLocked = true
        val hidden = property(timeline, "hidden").also { it.isListed = false }
        val channel = property(timeline, "channel").also { it.curves.single().isListed = false }

        assertTrue(TimelineKeys.handle(timeline, GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL))
        assertEquals(folded.keyframes.toSet(), timeline.selectedKeyframes.toSet())
        assertTrue(listOf(locked, hidden, channel).none { it.keyframes.any(timeline::isSelected) })
    }

    @Test
    fun `column selection starts at the playhead and extends all selected times across tracks`() {
        val timeline = TimelineController()
        val first = property(timeline, "first")
        val second = property(timeline, "second")
        val nearby = Keyframe(1f + ChannelCurve.KEY_TIME_EPSILON * 0.5f, 0f)
        second.curves.single().keyframes += nearby
        timeline.applyCurrentTime(1f)

        assertTrue(TimelineKeys.handle(timeline, GLFW.GLFW_KEY_K, 0))
        assertEquals(setOf(first.keyframes[0], second.keyframes[0], nearby), timeline.selectedKeyframes.toSet())

        timeline.select(listOf(first.keyframes[0], first.keyframes[2]), additive = false)
        timeline.selectKeyColumns()
        assertEquals(
            setOf(first.keyframes[0], first.keyframes[2], second.keyframes[0], second.keyframes[2], nearby),
            timeline.selectedKeyframes.toSet(),
        )
    }

    @Test
    fun `graph commands respect curve focus and track selection does not select other channels`() {
        val timeline = TimelineController()
        val first = property(timeline, "first")
        val second = property(timeline, "second")
        timeline.viewMode = TimelineViewMode.CURVES
        timeline.focusCurves(first.curves, additive = false)

        timeline.selectAllKeys()
        assertEquals(first.keyframes.toSet(), timeline.selectedKeyframes.toSet())

        timeline.selectTrackKeys(second.curves)
        assertEquals(second.keyframes.toSet(), timeline.selectedKeyframes.toSet())
    }

    private fun property(timeline: TimelineController, name: String): AnimProperty<Float> =
        timeline.addProperty(listOf(name), AnimProperty(name, name, FloatPropertyType(), 0f)).also { property ->
            property.curves.single().keyframes.addAll(listOf(1f, 2f, 3f).map { Keyframe(it, 0f) })
        }
}
