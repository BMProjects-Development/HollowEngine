package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.attribute
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HssAttributeSelectorTest {
    @Test
    fun `attribute value selector matches an attribute modifier`() {
        val rule = compileHss(".button[variant=ghost] { opacity: 0.5; }").rules.single()
        val plain = BoxNode(tags = listOf("button"))
        assertFalse(rule.selector.matches(plain))

        val ghost = BoxNode(tags = listOf("button"), modifiers = listOf(Modifier.attribute("variant", "ghost")))
        assertTrue(rule.selector.matches(ghost))

        val other = BoxNode(tags = listOf("button"), modifiers = listOf(Modifier.attribute("variant", "solid")))
        assertFalse(rule.selector.matches(other))
    }

    @Test
    fun `bare attribute selector matches presence`() {
        val rule = compileHss(".field[disabled] { opacity: 0.3; }").rules.single()
        assertFalse(rule.selector.matches(BoxNode(tags = listOf("field"))))
        val disabled = BoxNode(tags = listOf("field"), modifiers = listOf(Modifier.attribute("disabled")))
        assertTrue(rule.selector.matches(disabled))
    }

    @Test
    fun `attribute names are case-insensitive`() {
        val rule = compileHss(".x[data-role=nav] { opacity: 0.5; }").rules.single()
        val node = BoxNode(tags = listOf("x"), modifiers = listOf(Modifier.attribute("Data-Role", "nav")))
        assertTrue(rule.selector.matches(node))
    }
}
