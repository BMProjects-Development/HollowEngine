package ru.hollowhorizon.hollowengine.client.ui.input

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import kotlin.test.assertEquals

/**
 * A draggable node still has to be clickable: the project tree selects, opens and shows its menu on
 * click, and arming a drag on press must not swallow that.
 */
class DraggableClickTest {
    private class Row {
        val clicks = mutableListOf<Int>()
        val drags = mutableListOf<Int>()
        val node = BoxNode(
            modifiers = listOf(
                Modifier.size(100.px, 20.px)
                    .background(UiColor(1f, 0f, 0f, 1f))
                    .input(hoverable = true, clickable = true, draggable = true)
                    .onClick { clicks += it.button }
                    .onDrag { drags += it.button },
            ),
        )
    }

    private fun frameOf(row: Row): Pair<HollowUiFrame, HollowUiInputController> {
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(row.node) }
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 400f, 400f, UiScrollState())
        val frame = HollowUiFrame(root = root, nodes = layout.nodes.keys.toList(), layout = layout)
        return frame to HollowUiInputController()
    }

    private fun dispatch(event: UiEvent): Boolean = event.node.dispatch(event)

    @Test
    fun `a press that never moves is still a click`() {
        val row = Row()
        val (frame, input) = frameOf(row)

        input.mouseClicked(frame, 10f, 10f, 0, ::dispatch)
        input.mouseReleased(frame, 10f, 10f, 0, ::dispatch)

        assertEquals(listOf(0), row.clicks)
        assertEquals(emptyList(), row.drags)
    }

    @Test
    fun `a right click reaches the node it was pressed on`() {
        val row = Row()
        val (frame, input) = frameOf(row)

        input.mouseClicked(frame, 10f, 10f, 1, ::dispatch)
        input.mouseReleased(frame, 10f, 10f, 1, ::dispatch)

        assertEquals(listOf(1), row.clicks)
    }

    @Test
    fun `a press that dragged is not a click`() {
        val row = Row()
        val (frame, input) = frameOf(row)

        input.mouseClicked(frame, 10f, 10f, 0, ::dispatch)
        input.mouseDragged(frame, 40f, 10f, 0, 30f, 0f, 0, ::dispatch)
        input.mouseReleased(frame, 40f, 10f, 0, ::dispatch)

        assertEquals(emptyList(), row.clicks)
        assertEquals(listOf(0), row.drags)
    }

    @Test
    fun `an ancestor can capture the middle button over an interactive child`() {
        val buttons = mutableListOf<Int>()
        val child = BoxNode(modifiers = listOf(Modifier.size(100.px, 40.px).input(clickable = true, draggable = true)))
        val parent = BoxNode(modifiers = listOf(
            Modifier.size(200.px, 100.px).input(clickable = true, draggable = true)
                .capturePointerButtons(2).onDrag { buttons += it.button },
        )).also { it.children.add(child) }
        val root = BoxNode().also { it.children.add(parent) }
        parent.layoutState.attachTo(root)
        child.layoutState.attachTo(parent)
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 400f, 400f, UiScrollState())
        val frame = HollowUiFrame(root, layout.nodes.keys.toList(), layout)
        val input = HollowUiInputController()
        input.mouseClicked(frame, 10f, 10f, 2, ::dispatch)
        input.mouseDragged(frame, 20f, 10f, 2, 10f, 0f, 0, ::dispatch)
        assertEquals(listOf(2), buttons)
    }
}
