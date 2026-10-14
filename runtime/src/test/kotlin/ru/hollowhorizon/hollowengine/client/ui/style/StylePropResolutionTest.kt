package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StylePropResolutionTest {
    private fun resolve(vararg modifiers: Modifier): UiComputedStyle =
        modifiers.asList().flattenModifiers().toStylePatch().resolve()

    // --- Custom states ---

    @Test
    fun `custom state names are accepted by the hss parser and match nodes`() {
        val sheet = compileHss(".panel:expanded { opacity: 0.5; }")
        val rule = sheet.rules.single()
        assertTrue(rule.selector.states.contains(UiState.of("expanded")))

        val node = BoxNode(tags = listOf("panel"))
        assertFalse(rule.selector.matches(node))
        node.states += UiState.of("expanded")
        assertTrue(rule.selector.matches(node))
    }

    @Test
    fun `state name casing is normalized`() {
        assertEquals(UiState.of("Hover"), UiState.HOVER)
        assertEquals(UiState.of("  FOCUS "), UiState.FOCUS)
        // A mixed-case Modifier.state should satisfy a lowercase :expanded selector.
        val node = BoxNode(modifiers = listOf(Modifier.state("Expanded")))
        val rule = compileHss("box:expanded { opacity: 0.5; }").rules.single()
        assertTrue(rule.selector.matches(node))
    }

    // --- Input capabilities as independent props ---

    @Test
    fun `event modifiers accumulate input capabilities instead of clobbering`() {
        val style = resolve(Modifier.onClick {}.onScroll {})
        assertTrue(style.clickable, "onClick keeps clickable")
        assertTrue(style.hoverable)
        assertFalse(style.scrollable, "onScroll alone does not make a scroll container")
    }

    @Test
    fun `scroll participates in the layout fingerprint but hover does not`() {
        val base = resolve()
        val hoverable = resolve(Modifier.input(hoverable = true))
        val scrollable = resolve(Modifier then scrollModifier())
        assertEquals(base.layoutFingerprint(), hoverable.layoutFingerprint(), "hoverable is input-only")
        assertTrue(base.layoutFingerprint() != scrollable.layoutFingerprint(), "scroll affects layout")
    }

    @Test
    fun `a later rule can turn an input capability off`() {
        val patch = UiStylePatch()
        patch.clickable = true
        val disable = UiStylePatch()
        disable.clickable = false
        patch.merge(disable)
        assertFalse(patch.resolve().clickable)
    }

    // --- Combining semantics: only simultaneously-active states stack ---

    private fun resolvedStyle(sheet: CompiledHss, tag: String, vararg states: UiState): UiComputedStyle {
        val node = BoxNode(tags = listOf(tag))
        states.forEach { node.states += it }
        UiModifierResolver(stylesheet = sheet).resolve(node, animate = false)
        return node.resolvedSnapshot
    }

    @Test
    fun `a dsl transform chain is last-wins, not combined`() {
        assertEquals(UiVec3(3f, 3f, 1f), resolve(Modifier.scale(2f).scale(3f)).scale)
        assertEquals(UiVec3(4f, 1f, 0f), resolve(Modifier.translate(10f, 5f).translate(4f, 1f)).translate)
    }

    @Test
    fun `two simultaneously active states stack their transforms`() {
        val sheet = compileHss(".btn:hover { scale: 2; } .btn:selected { scale: 3; }")
        assertEquals(UiVec3(6f, 6f, 1f), resolvedStyle(sheet, "btn", UiState.HOVER, UiState.SELECTED).scale)
    }

    @Test
    fun `active states stack tint multiplicatively`() {
        val sheet = compileHss(".btn:hover { tint: #808080; } .btn:selected { tint: #808080; }")
        val tint = resolvedStyle(sheet, "btn", UiState.HOVER, UiState.SELECTED).tint
        assertEquals(0.252f, tint.red, 5e-3f)
    }

    @Test
    fun `base does not stack with an active state`() {
        val sheet = compileHss(".btn { scale: 5; } .btn:hover { scale: 2; }")
        assertEquals(UiVec3(2f, 2f, 1f), resolvedStyle(sheet, "btn", UiState.HOVER).scale)
    }
}
