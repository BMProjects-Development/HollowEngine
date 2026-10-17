package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals

/**
 * `font-size: 85%` is a share of the surrounding text, resolved while the style is built
 * an inlay hint keeps up with the editor's font instead of pinning a size of its own.
 */
class RelativeFontSizeTest {
    private fun tree(parent: Modifier, child: Modifier): Pair<BoxNode, BoxNode> {
        val inner = BoxNode(tags = listOf("child"), modifiers = listOf(child))
        val outer = BoxNode(tags = listOf("parent"), modifiers = listOf(parent)).also { it.children.add(inner) }
        inner.layoutState.attachTo(outer)
        return outer to inner
    }

    private fun resolve(sheet: String, parent: Modifier = Modifier, child: Modifier = Modifier): Pair<Float, Float> {
        val (outer, inner) = tree(parent, child)
        UiModifierResolver(stylesheet = compileHss(sheet)).resolve(outer, animate = false)
        return outer.resolvedSnapshot.fontSize to inner.resolvedSnapshot.fontSize
    }

    @Test
    fun `a share resolves against the inherited size`() {
        val (parent, child) = resolve(".parent { font-size: 20px; } .child { font-size: 85%; }")
        assertEquals(20f, parent)
        assertEquals(17f, child)
    }

    @Test
    fun `em says the same thing as a percentage`() {
        val (_, child) = resolve(".parent { font-size: 20px; } .child { font-size: 0.85em; }")
        assertEquals(17f, child)
    }

    @Test
    fun `shares compound down the tree`() {
        val inner = BoxNode(tags = listOf("leaf"))
        val (outer, middle) = tree(Modifier, Modifier)
        middle.children.add(inner)
        inner.layoutState.attachTo(middle)

        UiModifierResolver(
            stylesheet = compileHss(".parent { font-size: 20px; } .child { font-size: 50%; } .leaf { font-size: 50%; }"),
        ).resolve(outer, animate = false)

        assertEquals(10f, middle.resolvedSnapshot.fontSize)
        assertEquals(5f, inner.resolvedSnapshot.fontSize)
    }

    @Test
    fun `an absolute size later in the cascade wins over a share`() {
        val (_, child) = resolve(".child { font-size: 50%; } .child { font-size: 9px; }")
        assertEquals(9f, child)
    }

    @Test
    fun `a share on the root falls back to the engine default`() {
        val (parent, _) = resolve(".parent { font-size: 50%; }")
        assertEquals(DefaultUiFontSize * 0.5f, parent)
    }

    @Test
    fun `modifiers can ask for a share too`() {
        val (parent, child) = resolve("", parent = Modifier.fontSize(16f), child = Modifier.fontScale(0.75f))
        assertEquals(16f, parent)
        assertEquals(12f, child)
    }

    @Test
    fun `a span inherits the resolved pixels, not the share`() {
        val span = SpanNode("hi")
        val (outer, child) = tree(Modifier.fontSize(20f), Modifier.fontScale(0.5f))
        child.children.add(span)
        span.layoutState.attachTo(child)

        UiModifierResolver().resolve(outer, animate = false)

        assertEquals(10f, span.resolvedSnapshot.fontSize, "the span reads its parent's resolved size")
    }
}
