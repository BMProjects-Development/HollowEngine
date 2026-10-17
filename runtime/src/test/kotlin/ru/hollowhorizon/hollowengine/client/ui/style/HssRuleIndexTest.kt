package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.UiNode
import ru.hollowhorizon.hollowengine.client.ui.UiState
import ru.hollowhorizon.hollowengine.client.ui.UiVec3
import kotlin.test.assertEquals

/** The rule index must never drop a matching rule: multi-tag, id, universal-state and descendant. */
class HssRuleIndexTest {
    private fun resolve(root: UiNode, hss: String) {
        UiModifierResolver(stylesheet = compileHss(hss)).resolve(root, animate = false)
    }

    @Test
    fun `a multi-tag rule matches only nodes carrying all its tags`() {
        val both = BoxNode(tags = listOf("a", "b"))
        resolve(both, ".a.b { scale: 2; }")
        assertEquals(UiVec3(2f, 2f, 1f), both.resolvedSnapshot.scale)

        val one = BoxNode(tags = listOf("a"))
        resolve(one, ".a.b { scale: 2; }")
        assertEquals(UiVec3(1f, 1f, 1f), one.resolvedSnapshot.scale, "missing tag b, so no match")
    }

    @Test
    fun `an id rule matches only that id`() {
        val x = BoxNode(id = "x")
        resolve(x, "#x { scale: 3; }")
        assertEquals(UiVec3(3f, 3f, 1f), x.resolvedSnapshot.scale)

        val y = BoxNode(id = "y")
        resolve(y, "#x { scale: 3; }")
        assertEquals(UiVec3(1f, 1f, 1f), y.resolvedSnapshot.scale)
    }

    @Test
    fun `a universal state rule matches any node in that state`() {
        val node = BoxNode().also { it.states += UiState.of("selected") }
        resolve(node, ":selected { scale: 4; }")
        assertEquals(UiVec3(4f, 4f, 1f), node.resolvedSnapshot.scale)
    }

    @Test
    fun `a descendant selector still matches through the index`() {
        val inner = BoxNode(tags = listOf("inner"))
        val outer = BoxNode(tags = listOf("outer")).also {
            it.children.add(inner)
            inner.layoutState.attachTo(it)
        }
        resolve(outer, ".outer .inner { scale: 5; }")
        assertEquals(UiVec3(5f, 5f, 1f), inner.resolvedSnapshot.scale)
    }

    @Test
    fun `a cached descendant is re-resolved when its ancestor selector context changes`() {
        val inner = BoxNode(tags = listOf("inner"))
        val outer = BoxNode().also {
            it.children.add(inner)
            inner.layoutState.attachTo(it)
        }
        val resolver = UiModifierResolver(stylesheet = compileHss(".outer .inner { scale: 5; }"))

        resolver.resolve(outer, animate = false)
        assertEquals(UiVec3(1f, 1f, 1f), inner.resolvedSnapshot.scale)

        outer.tags += "outer"
        resolver.resolve(outer, animate = false)
        assertEquals(UiVec3(5f, 5f, 1f), inner.resolvedSnapshot.scale)
    }
}
