package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.AnimProperty
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelSampling
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ChannelSpec
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.FloatPropertyType
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.PropertyType
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.common.utils.Color
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineRowsTest {
    @Test
    fun `discrete tracks are omitted from curve editor rows`() {
        val timeline = TimelineController()
        val continuous = timeline.addProperty(listOf("node"), AnimProperty("size", "Size", FloatPropertyType(), 1f))
        val discrete = timeline.addProperty(listOf("node"), AnimProperty("mode", "Mode", StepPropertyType, 0f))

        val rows = timelineRows(timeline, curveEditorOnly = true)

        assertTrue(rows.any { it.property === continuous })
        assertFalse(rows.any { it.property === discrete })
    }

    private object StepPropertyType : PropertyType<Float> {
        override val id = "step"
        override val channels = listOf(ChannelSpec("Mode", Color("FFFFFF"), sampling = ChannelSampling.DISCRETE))

        override fun decompose(value: Float, into: FloatArray) {
            into[0] = value
        }

        override fun compose(values: FloatArray): Float = values[0]
    }
}
