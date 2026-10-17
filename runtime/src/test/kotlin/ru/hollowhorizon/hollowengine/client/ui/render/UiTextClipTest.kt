package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextRun
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiTextClipTest {
    @Test
    fun `fitting hidden text does not create a clip barrier`() {
        assertFalse(requiresTextClip(UiTextOverflow.HIDDEN, 80f, 20f, 100f, 30f, 0f, 0f))
    }

    @Test
    fun `overflowing or scrolled text keeps its clip`() {
        assertTrue(requiresTextClip(UiTextOverflow.HIDDEN, 120f, 20f, 100f, 30f, 0f, 0f))
        assertTrue(requiresTextClip(UiTextOverflow.HIDDEN, 80f, 40f, 100f, 30f, 0f, 0f))
        assertTrue(requiresTextClip(UiTextOverflow.HIDDEN, 80f, 20f, 100f, 30f, 1f, 0f))
    }

    @Test
    fun `show overflow never clips`() {
        assertFalse(requiresTextClip(UiTextOverflow.SHOW, 120f, 40f, 100f, 30f, 1f, 1f))
    }

    @Test
    fun `overflow on a Text container reaches its span and produces dots`() {
        val span = SpanNode("a_very_long_asset_name.json")
        val text = BoxNode(
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(
                Modifier.size(40.px, 12.px).fontFamily(TestFontFamily).fontSize(10f)
                    .textWrap(false).textOverflow(UiTextOverflow.DOTS),
            ),
        ).also { node ->
            node.children += span
            span.layoutState.attachTo(node)
        }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { node ->
            node.children += text
            text.layoutState.attachTo(node)
        }
        UiModifierResolver().resolve(root, animate = false)
        val layout = UiLayoutPipeline().compute(root, 100f, 100f, UiScrollState())
        val command = UiCommandRenderer().collect(root, layout).filterIsInstance<DrawTextCommand>().single()

        assertEquals(UiTextOverflow.DOTS, command.overflow)
        assertEquals(40f, command.rect.width, 0.5f)
        val displayed = UiTextOverflowResolver.ellipsizeLine(command, command.layout.lines.single())
        assertTrue(displayed.text.endsWith("..."), "overflow result was '${displayed.text}'")
    }

    @Test
    fun `shortened text keeps the spaces between its words`() {
        val span = SpanNode("one two three four five six")
        val text = BoxNode(
            measurePolicy = UiMeasurePolicies.InlineFlow,
            modifiers = listOf(
                Modifier.size(70.px, 12.px).fontFamily(TestFontFamily).fontSize(10f)
                    .textWrap(false).textOverflow(UiTextOverflow.DOTS),
            ),
        ).also { node ->
            node.children += span
            span.layoutState.attachTo(node)
        }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { node ->
            node.children += text
            text.layoutState.attachTo(node)
        }
        UiModifierResolver().resolve(root, animate = false)
        val layout = UiLayoutPipeline().compute(root, 100f, 100f, UiScrollState())
        val command = UiCommandRenderer().collect(root, layout).filterIsInstance<DrawTextCommand>().single()

        val displayed = UiTextOverflowResolver.ellipsizeLine(command, command.layout.lines.single())
        assertTrue(displayed.text.startsWith("one two"), "overflow result was '${displayed.text}'")
        val runs = displayed.fragments.filterIsInstance<UiTextRun>()
        assertTrue(runs.zipWithNext().all { (left, right) -> right.x > left.x + left.width - 0.01f })
        assertTrue(runs[1].x > runs[0].x + runs[0].width + 0.5f, "the words were drawn back to back")
    }
}
