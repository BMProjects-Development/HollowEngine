package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayerOverflowTest {
    private fun layoutOf(root: BoxNode) = HollowUiRuntime().frame(root, 200f, 200f, -1f, -1f, 0L).layout

    @Test
    fun `a child hanging above its parent is reserved for`() {
        val plate = BoxNode(
            modifiers = listOf(Modifier.size(40.px, 16.px).margin(0.px, (-30).px, 0.px, 0.px)),
        )
        val panel = BoxNode(
            measurePolicy = UiMeasurePolicies.Column,
            modifiers = listOf(Modifier.size(100.px, 60.px).opacity(0.5f)),
        ).also { it.children.add(plate) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(panel) }

        val overflow = layoutOf(root).layerOverflows()[panel]

        assertEquals(30f, overflow, "the plate sits 30px above the panel it belongs to")
        assertTrue(
            layerPadding(UiFilterChain.Empty, overflow!!) >= 30f,
            "the reserved padding has to cover the overhang, not just the guard band",
        )
    }

    @Test
    fun `a clipping child bounds its own subtree`() {
        val overflowing = BoxNode(
            modifiers = listOf(Modifier.size(40.px, 400.px)),
        )
        val viewport = BoxNode(
            measurePolicy = UiMeasurePolicies.Column,
            modifiers = listOf(Modifier.size(40.px, 20.px).clip()),
        ).also { it.children.add(overflowing) }
        val panel = BoxNode(
            measurePolicy = UiMeasurePolicies.Column,
            modifiers = listOf(Modifier.size(100.px, 60.px).opacity(0.5f)),
        ).also { it.children.add(viewport) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(panel) }

        assertEquals(
            0f,
            layoutOf(root).layerOverflows()[panel],
            "content the viewport clips away never reaches the panel's layer",
        )
    }

    @Test
    fun `a layout with nothing to composite asks for nothing`() {
        val child = BoxNode(modifiers = listOf(Modifier.size(40.px, 16.px)))
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(child) }

        assertEquals(emptyMap(), layoutOf(root).layerOverflows())
    }
}
