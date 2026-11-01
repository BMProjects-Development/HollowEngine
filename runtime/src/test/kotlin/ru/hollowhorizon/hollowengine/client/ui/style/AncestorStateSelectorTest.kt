package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `.parent:hover .child` - a rule whose *ancestor* carries the state. The child has no state
 * of its own, so the rule has to follow the ancestor's state both when it matches and when
 * the resolver decides the cached style is stale.
 */
class AncestorStateSelectorTest {
    private val sheet = compileHss(
        """
        .icon { tint: #808080; }
        .button:hover .icon { tint: #FFFFFF; }
        """.trimIndent(),
    )

    private fun tree(): Pair<BoxNode, BoxNode> {
        val icon = BoxNode(tags = listOf("icon"))
        val button = BoxNode(tags = listOf("button")).also { it.children.add(icon) }
        icon.layoutState.attachTo(button)
        return button to icon
    }

    @Test
    fun `the child follows the ancestor state`() {
        val (button, icon) = tree()
        val resolver = UiModifierResolver(stylesheet = sheet)

        resolver.resolve(button, animate = false)
        assertEquals(UiColor(0.5019608f, 0.5019608f, 0.5019608f, 1f), icon.resolvedSnapshot.tint)

        button.setRuntimeStates(setOf(UiState.HOVER))
        resolver.resolve(button, animate = false)
        assertEquals(UiColor.White, icon.resolvedSnapshot.tint, "hovering the ancestor restyles the child")

        button.setRuntimeStates(emptySet())
        resolver.resolve(button, animate = false)
        assertEquals(
            UiColor(0.5019608f, 0.5019608f, 0.5019608f, 1f),
            icon.resolvedSnapshot.tint,
            "leaving the ancestor puts the child back",
        )
    }

    @Test
    fun `a state no selector asks of a node leaves the nodes below it alone`() {
        val (button, icon) = tree()
        val panel = BoxNode().also { it.children.add(button) }
        button.layoutState.attachTo(panel)
        val resolver = UiModifierResolver(stylesheet = sheet)
        resolver.resolve(panel, animate = false)
        val style = icon.resolvedSnapshot

        panel.setRuntimeStates(setOf(UiState.HOVER))
        resolver.resolve(panel, animate = false)
        assertSame(style, icon.resolvedSnapshot)
    }

    @Test
    fun `an ancestor state rule stacks like a state rule, not like a base rule`() {
        val stacking = compileHss(
            """
            .icon { scale: 1; }
            .button:hover .icon { scale: 2; }
            .icon:selected { scale: 3; }
            """.trimIndent(),
        )
        val (button, icon) = tree()
        icon.states += UiState.SELECTED
        button.setRuntimeStates(setOf(UiState.HOVER))

        UiModifierResolver(stylesheet = stacking).resolve(button, animate = false)
        assertEquals(
            UiVec3(6f, 6f, 1f),
            icon.resolvedSnapshot.scale,
            "both active state rules stack (2 * 3), as two states on one node would",
        )
    }
}
