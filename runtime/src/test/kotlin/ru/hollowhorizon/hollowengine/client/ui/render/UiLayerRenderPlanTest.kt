package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import kotlin.test.assertEquals

class UiLayerRenderPlanTest {
    @Test
    fun `plan collects only top-level framebuffer ranges`() {
        val nested = BoxNode(
            id = "nested",
            modifiers = listOf(Modifier.size(20.px, 20.px).rotate(x = 12f).background(UiColor.White)),
        )
        val first = BoxNode(
            id = "first",
            modifiers = listOf(Modifier.size(40.px, 40.px).rotate(y = 18f).background(UiColor.White)),
        ).also { it.children += nested }
        val second = BoxNode(
            id = "second",
            modifiers = listOf(Modifier.size(40.px, 40.px).rotate(x = 8f).background(UiColor.White)),
        )
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also {
            it.children += first
            it.children += second
        }
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 200f, 200f, UiScrollState())
        val commands = UiCommandRenderer().collect(root, layout)

        val plan = UiLayerRenderPlan.create(commands)

        assertEquals(listOf("first", "second"), plan.layers.map { it.command.node.id })
        val firstRange = commands.subList(plan.layers.first().startIndex, plan.layers.first().endIndex + 1)
        assertEquals(2, firstRange.count { it is BeginLayerCommand })
    }
}
