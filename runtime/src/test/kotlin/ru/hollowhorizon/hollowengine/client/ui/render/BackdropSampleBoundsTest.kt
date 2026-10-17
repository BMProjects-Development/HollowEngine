package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackdropSampleBoundsTest {
    @Test
    fun `bounds include gaussian sampling padding`() {
        assertEquals(
            ScissorBounds(x = 28, y = 8, width = 64, height = 54),
            backdropSampleBounds(
                rect = UiRect(20f, 10f, 20f, 15f),
                targetWidth = 200,
                targetHeight = 100,
                scaleX = 2f,
                scaleY = 2f,
                padding = gaussianSamplePadding(8f),
            ),
        )
    }

    @Test
    fun `bounds clamp to target edges`() {
        assertEquals(
            ScissorBounds(x = 0, y = 0, width = 32, height = 27),
            backdropSampleBounds(
                rect = UiRect(-5f, -2f, 15f, 7f),
                targetWidth = 200,
                targetHeight = 100,
                scaleX = 2f,
                scaleY = 3f,
                padding = gaussianSamplePadding(8f),
            ),
        )
    }

    @Test
    fun `bounds reject regions outside target`() {
        assertNull(
            backdropSampleBounds(
                rect = UiRect(120f, 60f, 10f, 10f),
                targetWidth = 200,
                targetHeight = 100,
                scaleX = 2f,
                scaleY = 2f,
                padding = gaussianSamplePadding(8f),
            )
        )
    }

    @Test
    fun `large gaussian radii use lower resolution`() {
        assertEquals(1, gaussianDownsampleFactor(8f))
        assertEquals(2, gaussianDownsampleFactor(9f))
        assertEquals(8, gaussianDownsampleFactor(64f))
    }

    @Test
    fun `workspace axes use stable allocation buckets`() {
        assertEquals(64, alignFramebufferAxis(42, 16_384))
        assertEquals(128, alignFramebufferAxis(65, 16_384))
        assertEquals(100, alignFramebufferAxis(65, 100))
    }

    @Test
    fun `workspace axes grow geometrically without shrinking`() {
        assertEquals(64, growFramebufferAxis(42, 0, 16_384))
        assertEquals(128, growFramebufferAxis(65, 64, 16_384))
        assertEquals(384, growFramebufferAxis(257, 256, 16_384))
        assertEquals(256, growFramebufferAxis(200, 256, 16_384))
        assertEquals(1_280, growFramebufferAxis(1_025, 1_024, 16_384))
    }
}
