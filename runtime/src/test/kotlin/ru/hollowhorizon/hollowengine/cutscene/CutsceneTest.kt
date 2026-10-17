package ru.hollowhorizon.hollowengine.cutscene

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.timelineRows
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CutsceneTest {

    @Test
    fun `timeline times snap to the nearest whole frame`() {
        assertEquals(1f, TimelineSnap.snap(1.02f, 20))
        assertEquals(1.05f, TimelineSnap.snap(1.04f, 20))
        assertEquals(0.5f, TimelineSnap.snap(0.51f, 24))
        assertEquals(1.24f, TimelineSnap.snap(1.24f, 0))
    }

    @Test
    fun `group drag preserves spacing when clamped to the work area`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = floatCurve(controller)
        val first = Keyframe(2f, 0f)
        val second = Keyframe(5f, 0f)
        curve.keyframes += listOf(first, second)
        controller.select(listOf(first, second), additive = false)

        controller.beginKeyframeDrag(second)
        controller.applyKeyframeDrag(-10f)
        controller.endKeyframeDrag()

        assertEquals(0f, first.time, 0.001f)
        assertEquals(3f, second.time, 0.001f)
    }

    @Test
    fun `group drag is atomic when one keyframe collides`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = floatCurve(controller)
        val first = Keyframe(1f, 0f)
        val second = Keyframe(3f, 0f)
        curve.keyframes += listOf(first, second, Keyframe(4f, 0f))
        controller.select(listOf(first, second), additive = false)

        controller.beginKeyframeDrag(second)
        controller.applyKeyframeDrag(1f)
        controller.endKeyframeDrag()

        assertEquals(1f, first.time, 0.001f)
        assertEquals(3f, second.time, 0.001f)
    }

    @Test
    fun `channels keep their own keys`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val layer = property
        val x = layer.curves[0]
        val y = layer.curves[1]
        x.keyframes += Keyframe(1f, 5f)
        y.keyframes += Keyframe(1f, 7f)

        controller.select(listOf(x.keyframes.first()), additive = false)
        controller.nudgeSelectedKeyframes(1f)

        assertEquals(2f, x.keyframes.first().time, 0.001f)
        assertEquals(1f, y.keyframes.first().time, 0.001f, "moving X must leave Y where it was")
    }

    @Test
    fun `a locked property refuses new keys`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.fov", "FOV", FloatPropertyType("FOV"), 70f),
        )
        val layer = property
        layer.isLocked = true

        assertTrue(controller.addKeyframes(layer, 1f).isEmpty())
        assertTrue(layer.curves.first().keyframes.isEmpty())
    }

    @Test
    fun `duplicate times are rejected on one curve`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = floatCurve(controller)
        controller.setKey(curve, 1f, 0f)
        controller.setKey(curve, 1f, 5f)

        assertEquals(1, curve.keyframes.size, "a second key at the same time replaces the first")
        assertEquals(5f, curve.keyframes.first().value, 0.001f)
    }

    @Test
    fun `deleting the selection leaves other curves alone`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val layer = property
        layer.curves.forEach { it.keyframes += Keyframe(1f, 0f) }
        controller.select(listOf(layer.curves[0].keyframes.first()), additive = false)

        controller.deleteSelectedKeyframes()

        assertTrue(layer.curves[0].keyframes.isEmpty())
        assertFalse(layer.curves[1].keyframes.isEmpty())
    }

    @Test
    fun `a type states what its channels may hold`() {
        val controller = TimelineController()
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty(
                "camera.fov",
                "FOV",
                FloatPropertyType("FOV", CameraRig.FOV_BOUNDS),
                CameraRig.DEFAULT_FOV,
            ),
        )
        val key = controller.setKey(property.curves.first(), 0f, -40f)

        assertEquals(1f, key.value, 0.001f, "an FOV below one is not a shot")
        assertEquals(1f, property.valueAt(0f), 0.001f)
        assertEquals(ChannelBounds(1f, 200f), property.bounds(0))
    }

    @Test
    fun `copied keys paste onto their own curves at the playhead`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val layer = property
        layer.curves[0].keyframes += Keyframe(1f, 3f)
        layer.curves[1].keyframes += Keyframe(2f, 7f)
        controller.select(layer.curves.flatMap { it.keyframes }, additive = false)

        controller.copySelectedKeyframes()
        controller.pasteKeyframes(5f)

        assertEquals(listOf(1f, 5f), layer.curves[0].keyframes.map { it.time })
        assertEquals(listOf(2f, 6f), layer.curves[1].keyframes.map { it.time }, "spacing survives the paste")
        assertEquals(7f, layer.curves[1].keyframes.last().value, 0.001f)
        assertEquals(2, controller.selectedKeyframes.size, "the copies are what stays selected")
    }

    @Test
    fun `cutting takes the keys with it and pasting brings them back`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = floatCurve(controller)
        curve.keyframes += Keyframe(1f, 4f)
        controller.select(curve.keyframes.toList(), additive = false)

        controller.cutSelectedKeyframes()
        assertTrue(curve.keyframes.isEmpty())

        controller.pasteKeyframes(3f)
        assertEquals(1, curve.keyframes.size)
        assertEquals(3f, curve.keyframes.first().time, 0.001f)
        assertEquals(4f, curve.keyframes.first().value, 0.001f)
    }

    @Test
    fun `a clone drag moves the copies and leaves the originals`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = floatCurve(controller)
        val original = Keyframe(1f, 4f)
        curve.keyframes += original
        controller.select(listOf(original), additive = false)

        assertTrue(controller.beginCloneDrag(original, withValues = false))
        assertTrue(controller.isDragDriver(original), "the pressed key still delivers the drag")
        val clone = controller.dragFocusKeyframe!!
        val start = controller.dragStartTimes!!.getValue(clone)
        controller.applyKeyframeDrag(3f - start)
        controller.endKeyframeDrag()

        assertEquals(2, curve.keyframes.size)
        assertEquals(1f, original.time, 0.001f, "the original stays where it was")
        assertEquals(3f, clone.time, 0.001f)
        assertEquals(4f, clone.value, 0.001f)
    }

    @Test
    fun `focusing a track narrows the graph without hiding it from the preview`() {
        val controller = TimelineController()
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val layer = property
        val x = layer.curves[0]
        x.keyframes += Keyframe(0f, 5f)

        controller.focusCurves(listOf(x), additive = false)
        assertTrue(controller.isFocused(x))
        assertFalse(controller.isFocused(layer.curves[1]))
        assertEquals(5f, property.valueAt(0f).x, 0.001f, "focus is about the graph, not the result")

        controller.focusCurves(listOf(x), additive = false)
        assertTrue(controller.focusedCurves.isEmpty())
    }

    @Test
    fun `clicking a stack of keys steps through it`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val stack = property.curves.map { curve ->
            Keyframe(1f, 0f).also { curve.keyframes += it }
        }
        val pressed = stack.first()

        controller.selectStacked(pressed, stack, additive = false)
        assertEquals(listOf(stack[0]), controller.selectedKeyframes.toList())

        controller.selectStacked(pressed, stack, additive = false)
        assertEquals(listOf(stack[1]), controller.selectedKeyframes.toList(), "the second click reaches the key below")

        controller.selectStacked(pressed, stack, additive = false)
        assertEquals(listOf(stack[2]), controller.selectedKeyframes.toList())

        controller.selectStacked(pressed, stack, additive = false)
        assertEquals(listOf(stack[0]), controller.selectedKeyframes.toList(), "and it comes back round")
    }

    @Test
    fun `clicking into a group selection keeps the group`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val property = controller.addProperty(
            listOf("Camera"),
            AnimProperty("camera.translation", "Translation", TranslationPropertyType(), Vec3f.ZERO),
        )
        val stack = property.curves.map { curve ->
            Keyframe(1f, 0f).also { curve.keyframes += it }
        }
        controller.select(stack, additive = false)

        controller.selectStacked(stack.first(), stack, additive = false)

        assertEquals(3, controller.selectedKeyframes.size)
    }

    @Test
    fun `weather keys are discrete even if serialized interpolation says bezier`() {
        val playback = CutscenePlaybackController()
        val curve = playback.weather.curves.first()
        val clear = playback.timeline.setKey(curve, 0f, CutsceneWeather.CLEAR.value)
        playback.timeline.setKey(curve, 10f, CutsceneWeather.THUNDER.value)
        clear.interpolation = KeyInterpolation.BEZIER

        assertEquals(CutsceneWeather.CLEAR, playback.weather.valueAt(9.99f))
        assertEquals(CutsceneWeather.THUNDER, playback.weather.valueAt(10f))
    }

    @Test
    fun `environment tracks survive a cutscene round trip`() {
        val playback = CutscenePlaybackController()
        val timeCurve = playback.timeOfDay.curves.first()
        val weatherCurve = playback.weather.curves.first()
        playback.timeline.setKey(timeCurve, 0f, 6_000f).interpolation = KeyInterpolation.LINEAR
        playback.timeline.setKey(timeCurve, 10f, 18_000f)
        playback.timeline.setKey(weatherCurve, 0f, CutsceneWeather.CLEAR.value)
        playback.timeline.setKey(weatherCurve, 5f, CutsceneWeather.RAIN.value)

        val restored = CutscenePlaybackController()
        restored.setupTracks(playback.toData("Environment"))
        restored.seek(5f)

        assertTrue(restored.timeOfDay.type is TimeOfDayPropertyType)
        assertEquals(12_000f, restored.currentEnvironment.timeOfDay!!, 0.01f)
        assertEquals(CutsceneWeather.RAIN, restored.currentEnvironment.weather)
    }

    @Test
    fun `empty environment tracks do not override the world`() {
        val playback = CutscenePlaybackController()

        assertEquals(null, playback.currentEnvironment.timeOfDay)
        assertEquals(null, playback.currentEnvironment.weather)
    }

    @Test
    fun `time of day formatter follows the minecraft clock`() {
        assertEquals("06:00", TimeOfDayValueFormatter.format(0f))
        assertEquals("12:00", TimeOfDayValueFormatter.format(6_000f))
        assertEquals("00:00", TimeOfDayValueFormatter.format(18_000f))
        assertEquals("05:59", TimeOfDayValueFormatter.format(23_999f))
        assertEquals("07:00", TimeOfDayValueFormatter.format(25_000f))
    }

    @Test
    fun `discrete tracks are omitted from curve editor rows`() {
        val playback = CutscenePlaybackController()
        val rows = timelineRows(playback.timeline, curveEditorOnly = true)

        assertTrue(rows.any { it.property === playback.timeOfDay })
        assertFalse(rows.any { it.property === playback.weather })
    }

    private fun floatCurve(controller: TimelineController): ChannelCurve {
        val property = controller.addProperty(
            listOf("Test"),
            AnimProperty("test.value", "Value", FloatPropertyType(), 0f),
        )
        return property.curves.first()
    }
}
