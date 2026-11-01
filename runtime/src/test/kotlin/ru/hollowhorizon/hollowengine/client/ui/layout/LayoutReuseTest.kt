package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LayoutReuseTest {
    private val leaf = BoxNode(modifiers = listOf(Modifier.size(20.px, 10.px)))
    private val still = column(leaf)
    private val grown = BoxNode(modifiers = listOf(Modifier.size(20.px, 10.px)))
    private val changing = column(grown)
    private val root = column(changing, still)
    private val runtime = HollowUiRuntime()

    private fun column(vararg children: BoxNode) = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { node ->
        children.forEach {
            node.children.add(it)
            it.layoutState.attachTo(node)
        }
    }

    private fun frame() = runtime.frame(root, 200f, 200f, -1f, -1f, 0L).layout

    private fun BoxNode.resize(height: Int) {
        modifiers.clear()
        modifiers.add(Modifier.size(20.px, height.px))
    }

    @Test
    fun `a subtree pushed down by its sibling is placed again`() {
        val before = frame()

        grown.resize(30)
        val after = frame()

        assertEquals(30f, after[grown].rect.height)
        assertNotSame(before[still], after[still])
        assertEquals(before[leaf].rect.y + 20f, after[leaf].rect.y)
    }

    @Test
    fun `a child raised to another layer moves up the draw order`() {
        frame()

        changing.modifiers.add(Modifier.layer(1))

        assertEquals(listOf<UiNode>(still, changing), frame().childrenOf(root))
    }

    @Test
    fun `changing the end of a column leaves what is above it`() {
        val before = frame()
        val above = before[changing]

        leaf.resize(30)
        val after = frame()

        assertSame(above, after[changing])
        assertSame(before[grown], after[grown])
        assertEquals(30f, after[leaf].rect.height)
        assertEquals(before.traversalOrder.toSet(), after.traversalOrder.toSet())
        assertEquals(listOf(grown), after.childrenOf(changing))
    }

    @Test
    fun `a removed child leaves the layout`() {
        frame()

        still.children.remove(leaf)
        leaf.layoutState.attachTo(null)
        still.layoutState.invalidateLayout()
        val after = frame()

        assertFalse(leaf in after.nodes)
        assertEquals(emptyList(), after.childrenOf(still))
    }

    @Test
    fun `a node that starts to need a layer is placed again`() {
        leaf.resize(10)
        assertFalse(frame()[still].needsFramebuffer)

        still.modifiers.add(Modifier.opacity(0.5f))

        assertTrue(frame()[still].needsFramebuffer)
    }
}
