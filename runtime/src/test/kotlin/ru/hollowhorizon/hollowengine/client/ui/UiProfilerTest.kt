package ru.hollowhorizon.hollowengine.client.ui

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiProfilerTest {
    @Test
    fun `timing history reports current average and rolling maximum`() {
        val profiler = UiProfiler(historySize = 2).apply { enabled = true }
        profiler.complete(profileFrame(profiler, composeNanos = 1_000_000L), forcePublish = true)
        profiler.complete(profileFrame(profiler, composeNanos = 3_000_000L), forcePublish = true)

        val timing = profiler.snapshot.timings.compose
        assertEquals(3.0, timing.currentMs, 0.0001)
        assertEquals(2.0, timing.averageMs, 0.0001)
        assertEquals(3.0, timing.maxMs, 0.0001)
        assertTrue("Command collections:" in profiler.snapshot.report)
    }

    @Test
    fun `style layout and command counters follow the real pipeline`() {
        val profiler = UiProfiler().apply { enabled = true }
        val profile = profiler.beginFrame()!!
        val root = BoxNode(
            modifiers = listOf(
                Modifier.size(64.px, 32.px).background(UiColor.White),
            ),
        )
        val resolver = UiModifierResolver()
        resolver.resolve(root, profile = profile)
        val layout = UiLayoutPipeline().compute(root, 100f, 100f, UiScrollState(), profile)
        UiCommandRenderer().collect(root, layout, profile)

        assertEquals(1, profile.stylePasses)
        assertEquals(1, profile.styleVisitedNodes)
        assertEquals(1, profile.styleRecomputedNodes)
        assertEquals(1, profile.styleMissUncached)
        assertTrue(profile.measureCalls >= 1)
        assertTrue(profile.measurePasses >= 1)
        assertTrue(profile.placedNodes >= 1)
        assertEquals(1, profile.commandCollections)
        assertEquals(1, profile.rectangleCommands)

        val cachedProfile = profiler.beginFrame()!!
        resolver.resolve(root, profile = cachedProfile)
        assertEquals(1, cachedProfile.styleVisitedNodes)
        assertEquals(1, cachedProfile.styleCacheHits)
        assertEquals(0, cachedProfile.styleRecomputedNodes)
    }

    private fun profileFrame(profiler: UiProfiler, composeNanos: Long): UiProfileFrame =
        profiler.beginFrame()!!.also { it.composeNanos = composeNanos }
}
