package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathStrokeLineCap
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathStrokeLineJoin
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiCanvasModifierRenderTest {
    @Test
    fun `draw modifiers record local geometry in their requested phases`() {
        val behind = UiColor(1f, 0f, 0f)
        val overlay = UiColor(0f, 1f, 0f)
        val node = BoxNode(
            modifiers = listOf(
                Modifier
                    .size(40.px, 20.px)
                    .drawBehind { drawRect(UiPaint.Color(behind)) }
                    .draw { drawRect(UiRect(2f, 3f, 8f, 9f), UiPaint.Color(overlay)) },
            ),
        )

        val boxes = render(node).filterIsInstance<DrawBoxCommand>()

        assertEquals(2, boxes.size)
        assertEquals(UiRenderPhase.BACKGROUND, boxes[0].phase)
        assertEquals(40f, boxes[0].rect.width)
        assertEquals(20f, boxes[0].rect.height)
        assertEquals(UiRenderPhase.OVERLAY, boxes[1].phase)
        assertEquals(2f, boxes[1].rect.x - boxes[0].rect.x)
        assertEquals(3f, boxes[1].rect.y - boxes[0].rect.y)
        assertEquals(8f, boxes[1].rect.width)
        assertEquals(9f, boxes[1].rect.height)
    }

    @Test
    fun `overlay draw is recorded after child content`() {
        val child = BoxNode(
            modifiers = listOf(Modifier.size(10.px, 10.px).background(UiColor.White)),
        )
        val parent = BoxNode(
            modifiers = listOf(
                Modifier.size(20.px, 20.px).draw { drawRect(UiPaint.Color(UiColor.Black)) },
            ),
        ).also { it.children += child }

        val commands = render(parent)
        val childIndex = commands.indexOfFirst { it is DrawBoxCommand && it.node === child }
        val overlayIndex = commands.indexOfFirst {
            it is DrawBoxCommand && it.node === parent && it.phase == UiRenderPhase.OVERLAY
        }

        assertTrue(childIndex >= 0)
        assertTrue(overlayIndex > childIndex)
    }

    @Test
    fun `shape stroke preserves cap and join in canvas command`() {
        val shape = GenericShape {
            moveTo(0f, 0f)
            lineTo(it.width, it.height)
        }
        val node = BoxNode(
            modifiers = listOf(
                Modifier.size(24.px, 12.px).drawBehind {
                    drawShape(
                        shape = shape,
                        paint = UiPaint.Color(UiColor.White),
                        style = UiDrawStyle.Stroke(
                            width = 3f,
                            lineCap = UiPathStrokeLineCap.Butt,
                            lineJoin = UiPathStrokeLineJoin.Bevel,
                        ),
                    )
                },
            ),
        )

        val command = assertIs<DrawShapeCommand>(render(node).single())

        assertEquals(3f, command.strokeWidth)
        assertEquals(UiPathStrokeLineCap.Butt, command.strokeLineCap)
        assertEquals(UiPathStrokeLineJoin.Bevel, command.strokeLineJoin)
    }

    @Test
    fun `box shadow carries the node shape into rendering`() {
        val shape = GenericShape {
            moveTo(it.width * 0.5f, 0f)
            lineTo(it.width, it.height)
            lineTo(0f, it.height)
            close()
        }
        val node = BoxNode(
            modifiers = listOf(
                Modifier
                    .size(24.px, 24.px)
                    .shape(shape, UiPaint.Color(UiColor.White))
                    .shadow(UiShadow(blur = 4f, spread = 2f, color = UiColor.Black)),
            ),
        )

        val command = render(node).filterIsInstance<DrawShadowCommand>().single()

        assertEquals(shape, command.shape)
    }

    private fun render(node: BoxNode): List<UiRenderCommand> {
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children += node }
        UiModifierResolver().resolve(root, animate = false)
        val layout = UiLayoutPipeline().compute(root, 200f, 200f, UiScrollState())
        return UiCommandRenderer().collect(root, layout)
    }
}
