package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards that `width`/`height` are animatable via `transition`.
 */
class UiSizeTransitionTest {
    @Test
    fun `mismatched length kinds snap to the target instead of holding stale`() {
        val from = listOf(Modifier.size(UiLength.Fit, 5.px)).flattenModifiers().toStylePatch().resolve()
        val to = listOf(Modifier.size(200.px, 5.px)).flattenModifiers().toStylePatch().resolve()

        val mid = from.interpolate(to, UiTransitionProgress.all(0.5f))

        assertEquals(UiLength.Px(200f), mid.width)
    }

    @Test
    fun `size transition group animates a width change through the resolver`() {
        val stylesheet = compileHss(
            """
                #bar { width: 100px; height: 5px; transition: size 200ms linear; }
                #bar:hover { width: 200px; }
            """.trimIndent(),
        )
        val node = BoxNode(id = "bar")
        val resolver = UiModifierResolver(stylesheet = stylesheet)
        resolver.resolve(node, nowMillis = 0L)
        node.states += UiState.HOVER
        resolver.resolve(node, nowMillis = 1L)

        // Halfway through the 200ms transition the width must be mid-way, not already at the target.
        resolver.resolve(node, nowMillis = 101L)

        val width = (node.resolvedSnapshot.width as UiLength.Px).value
        assertTrue(width > 120f && width < 180f, "width should be animating mid-transition, was $width")
    }
}
