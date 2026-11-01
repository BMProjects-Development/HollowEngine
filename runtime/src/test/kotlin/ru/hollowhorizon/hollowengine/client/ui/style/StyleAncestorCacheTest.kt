package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals

class StyleAncestorCacheTest {
    private val sheet = compileHss(
        """
        .icon { tint: #808080; }
        .button.loud .icon { tint: #FFFFFF; }
        """.trimIndent(),
    )

    private fun tree(): Pair<BoxNode, BoxNode> {
        val icon = BoxNode(tags = listOf("icon"))
        val button = BoxNode(tags = listOf("button")).also { it.children.add(icon) }
        icon.layoutState.attachTo(button)
        return button to icon
    }

    private fun recomputed(resolver: UiModifierResolver, root: UiNode): Int {
        val profiler = UiProfiler().apply { enabled = true }
        val frame = checkNotNull(profiler.beginFrame())
        resolver.resolve(root, animate = false, profile = frame)
        return frame.styleRecomputedNodes
    }

    @Test
    fun `a modifier of an ancestor leaves the styles under it alone`() {
        val (button, _) = tree()
        val resolver = UiModifierResolver(stylesheet = sheet)
        resolver.resolve(button, animate = false)

        button.modifiers += Modifier.translate(4f, 0f)

        assertEquals(1, recomputed(resolver, button), "only the changed node is restyled")
    }

    @Test
    fun `a tag of an ancestor restyles what selectors find under it`() {
        val (button, icon) = tree()
        val resolver = UiModifierResolver(stylesheet = sheet)
        resolver.resolve(button, animate = false)

        button.tags += "loud"
        resolver.resolve(button, animate = false)

        assertEquals(UiColor.White, icon.resolvedSnapshot.tint)
    }
}
