package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UiFrameTreeSnapshotTest {
    @Test
    fun `completed frame keeps its tree when compose children change`() {
        val content = BoxNode(
            id = "captured-content",
            modifiers = listOf(Modifier.size(40.px, 40.px).background(UiColor.White)),
        )
        val scrollable = BoxNode(
            id = "captured-scrollable",
            modifiers = listOf(
                Modifier.size(80.px, 80.px)
                    .background(UiColor.White)
                    .then(scrollModifier()),
            ),
        ).also { it.children.add(content) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(scrollable) }
        UiModifierResolver().resolve(root)
        val layout = UiLayoutPipeline().compute(root, 200f, 200f, UiScrollState())
        val frame = HollowUiFrame(root, layout.nodes.keys.toList(), layout)

        val replacement = BoxNode(
            id = "replacement",
            modifiers = listOf(Modifier.size(40.px, 40.px).background(UiColor.White)),
        )
        root.children.clear()
        root.children.add(replacement)
        scrollable.children.clear()

        val commands = UiCommandRenderer().collect(root, layout)

        assertTrue(commands.any { it.node === scrollable })
        assertTrue(commands.any { it.node === content })
        assertFalse(commands.any { it.node === replacement })
        assertTrue(frame.hitsVisible(20f, 20f))
        assertSame(scrollable, frame.scrollTargetAt(20f, 20f))
    }
}
