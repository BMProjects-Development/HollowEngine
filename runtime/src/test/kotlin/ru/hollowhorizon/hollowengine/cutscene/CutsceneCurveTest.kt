package ru.hollowhorizon.hollowengine.cutscene

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.AnimProperty
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelCurve
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.CurvePresets
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.FloatPropertyType
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.HandleMode
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInterpolation
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyTangent
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.Keyframe
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TangentSide
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineCurve
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineEdits
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CutsceneCurveTest {

    @Test
    fun `bezier segment passes through both keys`() {
        val start = TimelineCurve.sampleSegment(
            startTime = 1f, startValue = 10f, outgoing = KeyTangent(0.3f, 5f),
            endTime = 3f, endValue = 30f, incoming = KeyTangent(-0.3f, -5f),
            time = 1f,
        )
        val end = TimelineCurve.sampleSegment(
            startTime = 1f, startValue = 10f, outgoing = KeyTangent(0.3f, 5f),
            endTime = 3f, endValue = 30f, incoming = KeyTangent(-0.3f, -5f),
            time = 3f,
        )
        assertEquals(10f, start, 0.001f)
        assertEquals(30f, end, 0.001f)
    }

    @Test
    fun `overlong handles are shortened so the curve stays a function of time`() {
        var previous = Float.NEGATIVE_INFINITY
        for (step in 0..20) {
            val time = step / 20f
            val value = TimelineCurve.sampleSegment(
                startTime = 0f, startValue = 0f, outgoing = KeyTangent(5f, 0f),
                endTime = 1f, endValue = 10f, incoming = KeyTangent(-5f, 0f),
                time = time,
            )
            assertTrue(value >= previous - 0.001f, "curve went backwards at t=$time")
            previous = value
        }
    }

    @Test
    fun `auto handles flatten on a local extremum`() {
        val peak = TimelineCurve.autoTangents(
            previousTime = 0f, previousValue = 0f,
            time = 1f, value = 10f,
            nextTime = 2f, nextValue = 0f,
        )
        assertEquals(0f, peak.outgoing.value, 0.001f)
        assertEquals(0f, peak.incoming.value, 0.001f)

        val slope = TimelineCurve.autoTangents(
            previousTime = 0f, previousValue = 0f,
            time = 1f, value = 10f,
            nextTime = 2f, nextValue = 20f,
        )
        assertTrue(slope.outgoing.value > 0f, "a rising key should keep a rising handle")
        assertTrue(slope.incoming.value < 0f)
    }

    @Test
    fun `an auto-smoothed curve does not overshoot its own keys`() {
        val curve = curveOf(Keyframe(0f, 0f), Keyframe(1f, 10f), Keyframe(2f, 10f))

        for (step in 0..20) {
            val value = curve.valueAt(step / 10f, 0f)
            assertTrue(value <= 10.001f, "spline overshot to $value")
        }
        assertEquals(10f, curve.valueAt(1f, 0f), 0.001f)
    }

    @Test
    fun `constant interpolation holds the value until the next key`() {
        val curve = curveOf(
            Keyframe(0f, 0f, interpolation = KeyInterpolation.CONSTANT),
            Keyframe(1f, 10f),
        )

        assertEquals(0f, curve.valueAt(0.99f, 0f), 0.001f)
        assertEquals(10f, curve.valueAt(1f, 0f), 0.001f)
    }

    @Test
    fun `linear interpolation is a straight line`() {
        val curve = curveOf(
            Keyframe(0f, 0f, interpolation = KeyInterpolation.LINEAR),
            Keyframe(2f, 10f),
        )

        assertEquals(5f, curve.valueAt(1f, 0f), 0.001f)
    }

    @Test
    fun `a preset writes handles the editor can recognise again`() {
        val preset = assertNotNull(CurvePresets.byId("cubicInOut"))
        val start = Keyframe(0f, 0f)
        val end = Keyframe(2f, 10f)

        CurvePresets.apply(preset, start, end)

        assertEquals(KeyInterpolation.BEZIER, start.interpolation)
        assertEquals(2f * preset.outX, start.outgoing.time, 0.001f)
        assertEquals(10f * preset.outY, start.outgoing.value, 0.001f)
        assertEquals(preset.id, CurvePresets.match(start, end)?.id)
    }

    @Test
    fun `a mirrored handle copies direction and length, an aligned one keeps its own`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = curveOf(Keyframe(0f, 0f), Keyframe(1f, 0f), Keyframe(2f, 0f), controller = controller)
        val key = curve.keyframes[1]
        key.handleMode = HandleMode.FREE
        key.incoming = KeyTangent(-0.1f, -1f)

        controller.setTangent(key, TangentSide.OUTGOING, KeyTangent(0.5f, 4f), HandleMode.MIRRORED, 1f, 1f)
        assertEquals(-0.5f, key.incoming.time, 0.001f)
        assertEquals(-4f, key.incoming.value, 0.001f)

        key.incoming = KeyTangent(-0.1f, 0f)
        controller.setTangent(key, TangentSide.OUTGOING, KeyTangent(0.5f, 0f), HandleMode.ALIGNED, 1f, 1f)
        assertEquals(0.1f, abs(key.incoming.time), 0.001f, "aligned keeps the partner's own length")
    }

    @Test
    fun `a free handle leaves its partner alone`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = curveOf(Keyframe(0f, 0f), Keyframe(1f, 0f), Keyframe(2f, 0f), controller = controller)
        val key = curve.keyframes[1]
        key.handleMode = HandleMode.FREE
        key.incoming = KeyTangent(-0.2f, 1f)

        controller.setTangent(key, TangentSide.OUTGOING, KeyTangent(0.5f, 4f), HandleMode.FREE, 1f, 1f)

        assertEquals(-0.2f, key.incoming.time, 0.001f)
        assertEquals(1f, key.incoming.value, 0.001f)
    }

    @Test
    fun `grabbing a handle turns the segment it faces into a spline`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = curveOf(
            Keyframe(0f, 0f, interpolation = KeyInterpolation.LINEAR),
            Keyframe(1f, 10f, interpolation = KeyInterpolation.LINEAR),
            controller = controller,
        )
        val second = curve.keyframes[1]

        controller.setTangent(second, TangentSide.INCOMING, KeyTangent(-0.3f, -2f), HandleMode.FREE, 1f, 1f)

        assertEquals(
            KeyInterpolation.BEZIER,
            curve.keyframes[0].interpolation,
            "the incoming handle shapes the previous segment, so that is the one that becomes a curve",
        )
    }

    @Test
    fun `a handle only counts on the side that has a segment to shape`() {
        val curve = curveOf(
            Keyframe(0f, 0f, interpolation = KeyInterpolation.LINEAR),
            Keyframe(1f, 10f),
        )

        assertTrue(!curve.isTangentUsed(curve.keyframes[0], TangentSide.INCOMING), "the first key has no segment behind it")
        assertTrue(!curve.isTangentUsed(curve.keyframes[0], TangentSide.OUTGOING), "a linear segment ignores handles")
        assertTrue(!curve.isTangentUsed(curve.keyframes[1], TangentSide.OUTGOING), "the last key has no segment ahead")
    }

    @Test
    fun `smoothing a selection makes it a spline on automatic handles`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = curveOf(Keyframe(1f, 0f, handleMode = HandleMode.FREE), controller = controller)
        val key = curve.keyframes.first()
        key.outgoing = KeyTangent(1f, 1f)
        controller.select(listOf(key), additive = false)

        controller.smoothSelectedKeyframes()

        assertEquals(KeyInterpolation.BEZIER, key.interpolation)
        assertEquals(HandleMode.AUTO, key.handleMode)
        assertEquals(KeyTangent.ZERO, key.outgoing)
    }

    @Test
    fun `undo restores handles as well as times`() {
        val controller = TimelineController().apply { workAreaEnd = 10f }
        val curve = curveOf(Keyframe(0f, 0f), Keyframe(1f, 5f), controller = controller)
        val key = curve.keyframes[1]

        controller.beginHistoryTransaction(TimelineEdits.EDIT_HANDLES)
        controller.setTangent(key, TangentSide.INCOMING, KeyTangent(-0.25f, -3f), HandleMode.MIRRORED, 1f, 1f)
        controller.commitHistoryTransaction()
        controller.history.undo()

        assertEquals(HandleMode.AUTO, curve.keyframes[1].handleMode)
    }

    @Test
    fun `evaluating a curve leaves the keys it reads alone`() {
        val curve = curveOf(Keyframe(0f, 0f), Keyframe(1f, 5f), Keyframe(2f, 1f))
        curve.keyframes.add(Keyframe(0.5f, 9f))

        val visited = curve.keyframes.map { key -> curve.valueAt(key.time + 0.1f, 0f) }

        assertEquals(4, visited.size)
        assertEquals(listOf(0f, 1f, 2f, 0.5f), curve.keyframes.map { it.time }, "reading must not reorder")
    }

    private fun curveOf(
        vararg keys: Keyframe,
        controller: TimelineController = TimelineController(),
    ): ChannelCurve {
        val property = controller.addProperty(
            listOf("Test"),
            AnimProperty("test.value", "Value", FloatPropertyType(), 0f),
        )
        val curve = property.curves.first()
        curve.keyframes.addAll(keys)
        curve.sort()
        return curve
    }
}
