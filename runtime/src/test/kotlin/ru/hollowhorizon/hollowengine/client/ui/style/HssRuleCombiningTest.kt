package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals

/** Declarations within one HSS rule (and separate base rules) are last-wins. */
class HssRuleCombiningTest {
    private fun styleOf(modifiers: List<Modifier>): UiComputedStyle =
        modifiers.flattenModifiers().toStylePatch().resolve()

    @Test
    fun `duplicate declarations in one rule do not stack`() {
        val rule = compileHss(".x { scale: 2; scale: 3; }").rules.single()
        val style = styleOf(rule.patch.modifiers())
        assertEquals(UiVec3(3f, 3f, 1f), style.scale, "within a rule, last-wins (no 6)")
    }

    @Test
    fun `separate base rules are last-wins, not combined`() {
        val sheet = compileHss(".x { scale: 2; } .x { scale: 3; }")
        val node = BoxNode(tags = listOf("x"))
        UiModifierResolver(stylesheet = sheet).resolve(node, animate = false)
        assertEquals(UiVec3(3f, 3f, 1f), node.resolvedSnapshot.scale)
    }

    @Test
    fun `unrelated shorthand declarations in one rule both apply`() {
        val rule = compileHss(".x { padding-left: 5px; padding-top: 3px; }").rules.single()
        val style = styleOf(rule.patch.modifiers())
        assertEquals(5f, style.padding.left.resolve(100f), 1e-5f)
        assertEquals(3f, style.padding.top.resolve(100f), 1e-5f)
    }
}
