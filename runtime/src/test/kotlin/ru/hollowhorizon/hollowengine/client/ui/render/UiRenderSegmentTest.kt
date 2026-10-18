package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiBackfaceVisibility
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiRenderSegmentTest {
    private val shape = GenericShape { size ->
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        close()
    }

    @Test
    fun `shape shadow stays in the geometry segment`() {
        assertTrue(shadowCommand(hasShape = true).isSegmentBatchable())
    }

    @Test
    fun `rectangle shadow stays in the analytic sdf segment`() {
        assertTrue(shadowCommand(hasShape = false).isSegmentBatchable())
    }

    @Test
    fun `layer texture subdivides only transforms with planar perspective`() {
        assertEquals(1, layerTextureSubdivisions(UiMatrix4.identity()))
        assertEquals(1, layerTextureSubdivisions(UiMatrix4.rotationX(0.5f)))
        assertEquals(
            12,
            layerTextureSubdivisions(UiMatrix4.perspective(500f) * UiMatrix4.rotationX(0.5f)),
        )
    }

    private fun shadowCommand(hasShape: Boolean) = DrawShadowCommand(
        node = BoxNode(),
        rect = UiRect(0f, 0f, 16f, 16f),
        radius = 0f,
        shape = shape.takeIf { hasShape },
        shadows = listOf(UiShadow(offset = UiVec3(0f, 0f, 0f), blur = 2f, color = UiColor.White)),
        opacity = 1f,
        transform = UiMatrix4.identity(),
        filter = UiFilterChain.Empty,
        backfaceVisibility = UiBackfaceVisibility.VISIBLE,
    )
}
