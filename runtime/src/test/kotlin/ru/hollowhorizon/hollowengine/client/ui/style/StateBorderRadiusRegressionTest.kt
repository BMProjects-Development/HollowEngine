package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.UiState
import kotlin.test.assertEquals

class StateBorderRadiusRegressionTest {
    private val sheet = compileHss(
        """
        .effect-card { border-radius: 14px; border: 2px rgba(232,238,255,0.34); }
        .grayscale-card { border: 2px rgba(218,230,246,0.5); }
        .grayscale-card:hover { border: 2px rgba(218,230,246,0.5); scale: 1.04; }
        """.trimIndent()
    )

    private fun radius(vararg states: UiState): Float {
        val node = BoxNode(tags = listOf("effect-card", "grayscale-card"))
        states.forEach { node.states += it }
        UiModifierResolver(stylesheet = sheet).resolve(node, animate = false)
        return node.resolvedSnapshot.border.radius
    }

    @Test
    fun `a hover rule setting border width does not wipe the radius`() {
        assertEquals(14f, radius(), 1e-5f)
        assertEquals(14f, radius(UiState.HOVER), 1e-5f)
    }

    @Test
    fun `border shorthand still applies width`() {
        val node = BoxNode(tags = listOf("effect-card"))
        UiModifierResolver(stylesheet = sheet).resolve(node, animate = false)
        assertEquals(2f, node.resolvedSnapshot.border.width.left.resolve(100f), 1e-5f)
        assertEquals(14f, node.resolvedSnapshot.border.radius, 1e-5f)
    }
}
