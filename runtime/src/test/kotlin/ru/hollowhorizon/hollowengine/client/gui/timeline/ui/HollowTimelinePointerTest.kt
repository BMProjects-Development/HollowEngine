package ru.hollowhorizon.hollowengine.client.gui.timeline.ui

import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.HollowUiFrame
import ru.hollowhorizon.hollowengine.client.ui.HollowUiSurface
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.HollowTimelineEditor
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import ru.hollowhorizon.hollowengine.client.ui.style.scale
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HollowTimelinePointerTest {
    @Test
    fun `ctrl click on empty lane keeps the keyframe selection`() {
        val (controller, curve) = timelineWithKeys(1f, 2f)
        controller.selectedKeyframes.addAll(curve.keyframes)

        HollowUiSurface().use { surface ->
            surface.setContent { HollowTimelineEditor(controller, refresh = {}) }
            val frame = surface.frame(800f, 500f, -1f, -1f, 0L)
            val lane = frame.nodes.single { node ->
                node.id?.startsWith("lane-") == true &&
                        frame.layout.childrenOf(node).any { "timeline-keyframe" in it.tags }
            }
            val laneRect = frame.layout[lane].rect
            val keyframeRight = frame.timelineKeyframes()
                .maxOf { keyframe -> frame.layout[keyframe].rect.let { it.x + it.width } }
            val x = keyframeRight + 24f
            val y = laneRect.y + laneRect.height * 0.5f

            assertTrue(surface.runtime.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_CONTROL))
            assertEquals(curve.keyframes.toSet(), controller.selectedKeyframes.toSet())

            assertTrue(surface.runtime.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT))
            assertTrue(controller.selectedKeyframes.isEmpty())
        }
    }

    @Test
    fun `close keyframes have disjoint hit regions and animate only the hovered visual`() {
        val (controller, _) = timelineWithKeys(1f, 1.04f)
        HollowUiSurface(stylesheet = widgetStyles()).use { surface ->
            surface.setContent { HollowTimelineEditor(controller, refresh = {}) }
            var frame = surface.frame(800f, 500f, -1f, -1f, 0L)
            var keyframes = frame.timelineKeyframes()
            val firstVisualRect = frame.layout[keyframes[0]].rect
            val x = firstVisualRect.x + firstVisualRect.width * 0.5f
            val y = firstVisualRect.y + firstVisualRect.height * 0.5f

            frame = surface.frame(800f, 500f, x, y, 16_000_000L)

            surface.frame(800f, 500f, x, y, 32_000_000L)
            frame = surface.frame(800f, 500f, x, y, 112_000_000L)
            keyframes = frame.timelineKeyframes()
            val hoveredScale = keyframes[0].resolvedSnapshot.scale.x
            val neighbourScale = keyframes[1].resolvedSnapshot.scale.x

            assertTrue(hoveredScale > 1f && hoveredScale < 1.12f, "hover transition was not in progress: $hoveredScale")
            assertEquals(1f, neighbourScale, 0.001f, "hovering one keyframe must not animate its neighbour")

            frame = surface.frame(800f, 500f, x, y, 240_000_000L)
            keyframes = frame.timelineKeyframes()
            assertEquals(1.12f, keyframes[0].resolvedSnapshot.scale.x, 0.001f)
            assertEquals(1f, keyframes[1].resolvedSnapshot.scale.x, 0.001f)
        }
    }

    @Test
    fun `hovering one key across a multi-channel rig animates only that key`() {
        val controller = TimelineController()
        val translation = controller.addProperty(
            listOf("Camera", "Transform"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val fov = controller.addProperty(
            listOf("Camera", "Lens"),
            AnimProperty("camera.fov", "FOV", FloatPropertyType("FOV"), 70f),
        )
        listOf(translation, fov).forEach { property ->
            property.curves.forEach { curve ->
                listOf(0.5f, 1.5f, 2.5f).forEach { time -> curve.keyframes += Keyframe(time, time) }
            }
        }

        HollowUiSurface(stylesheet = widgetStyles()).use { surface ->
            surface.setContent { HollowTimelineEditor(controller, refresh = {}) }
            surface.frame(900f, 600f, -1f, -1f, 0L)
            var frame = surface.frame(900f, 600f, -1f, -1f, 16_000_000L)
            val keyframes = frame.timelineKeyframes()
            assertTrue(keyframes.size >= 8, "expected a key per channel per moment, got ${keyframes.size}")

            val target = frame.layout[keyframes[keyframes.size / 2]].rect
            val x = target.x + target.width * 0.5f
            val y = target.y + target.height * 0.5f
            surface.frame(900f, 600f, x, y, 32_000_000L)
            surface.frame(900f, 600f, x, y, 112_000_000L)
            frame = surface.frame(900f, 600f, x, y, 400_000_000L)

            val scaled = frame.timelineKeyframes().filter { it.resolvedSnapshot.scale.x > 1.001f }
            assertEquals(1, scaled.size, "hover at $x,$y landed on ${scaled.size} keys")

            translation.isExpanded = false
            surface.frame(900f, 600f, x, y, 420_000_000L)
            frame = surface.frame(900f, 600f, x, y, 700_000_000L)
            val afterCollapse = frame.timelineKeyframes().count { it.resolvedSnapshot.scale.x > 1.001f }
            assertTrue(afterCollapse <= 1, "restructuring the rows left $afterCollapse keys hovered")
        }
    }

    private fun timelineWithKeys(vararg times: Float): Pair<TimelineController, ChannelCurve> {
        val controller = TimelineController()
        val property = controller.addProperty(
            listOf("Test"),
            AnimProperty("test.value", "Value", FloatPropertyType(), 0f),
        )
        val curve = property.curves.first()
        times.forEachIndexed { index, time -> curve.keyframes += Keyframe(time, index.toFloat()) }
        controller.selectedKeyframes.clear()
        return controller to curve
    }

    private fun widgetStyles() = compileHss(
        checkNotNull(javaClass.getResource("/assets/hollowengine/ui/styles/widgets.hss")).readText(),
    )

    private fun HollowUiFrame.timelineKeyframes() = nodes
        .filter { "timeline-keyframe-visual" in it.tags }
        .sortedBy { layout[it].rect.x }
}
