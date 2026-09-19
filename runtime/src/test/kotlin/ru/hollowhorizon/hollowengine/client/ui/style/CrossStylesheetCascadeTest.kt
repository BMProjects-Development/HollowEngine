package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.style
import kotlin.test.assertEquals

/**
 * Several stylesheets imported on one scope form one cascade, like CSS: specificity decides first,
 * the import order only breaks ties. Before, every later sheet beat every earlier one, so a panel
 * rule in ide.hss lost to a generic widget rule in widgets.hss.
 */
class CrossStylesheetCascadeTest {
    private fun opacityOf(vararg sheets: String): Float {
        val child = BoxNode(tags = listOf("popup", "dialog"))
        val imports = sheets.fold<String, Modifier>(Modifier) { modifier, sheet -> modifier.style(compileHss(sheet)) }
        val root = BoxNode(modifiers = listOf(imports))
        root.children.add(child)
        UiModifierResolver().resolve(root, animate = false)
        return child.resolvedSnapshot.opacity
    }

    @Test
    fun `a more specific rule from an earlier sheet beats a generic rule from a later one`() {
        assertEquals(0.25f, opacityOf(".popup.dialog { opacity: 0.25; }", ".popup { opacity: 0.75; }"))
    }

    @Test
    fun `equal specificity is decided by import order`() {
        assertEquals(0.75f, opacityOf(".dialog { opacity: 0.25; }", ".popup { opacity: 0.75; }"))
        assertEquals(0.25f, opacityOf(".popup { opacity: 0.75; }", ".dialog { opacity: 0.25; }"))
    }
}
