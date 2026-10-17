package ru.hollowhorizon.hollowengine.client.ui.layout

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import ru.hollowhorizon.hollowengine.client.ui.text.Bold
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A modifier/state/tag change re-resolves the node and lets the layout fingerprint decide whether to
 * relayout, rather than any of them forcing a relayout on their own.
 */
class StyleInvalidationTest {
    private fun <T : UiNode> T.attached(): T = apply {
        children.forEach { it.layoutState.attachTo(this); it.attached() }
    }

    private fun BoxNode.setModifier(modifier: Modifier) {
        modifiers.clear()
        modifiers += modifier
    }

    @Test
    fun `a draw-only modifier change keeps the layout even when a layout modifier is present`() {
        val box = BoxNode(id = "b", modifiers = listOf(Modifier.size(40.px, 40.px)))
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(box) }.attached()
        val runtime = HollowUiRuntime()
        val f1 = runtime.frame(root, 200f, 200f, -1f, -1f, 0L)

        box.setModifier(Modifier.size(40.px, 40.px).opacity(0.5f))
        val f2 = runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        assertSame(f1.layout, f2.layout, "a draw-only change reuses the layout")

        box.setModifier(Modifier.size(60.px, 40.px).opacity(0.5f))
        val f3 = runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        assertNotSame(f2.layout, f3.layout, "a size change rebuilds the layout")
    }

    @Test
    fun `removing a layout modifier rebuilds the layout via the fingerprint`() {
        val child = BoxNode(modifiers = listOf(Modifier.size(20.px, 20.px)))
        val box = BoxNode(id = "b", modifiers = listOf(Modifier.padding(8.px))).also { it.children.add(child) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(box) }.attached()
        val runtime = HollowUiRuntime()
        val f1 = runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        val paddedHeight = f1.layout[box].rect.height

        box.modifiers.clear()
        val f2 = runtime.frame(root, 200f, 200f, -1f, -1f, 0L)
        assertNotSame(f1.layout, f2.layout, "removing the padding modifier rebuilds the layout")
        assertNotEquals(paddedHeight, f2.layout[box].rect.height, "the box shrank by the removed padding")
    }

    @Test
    fun `text-effects and clip participate in the layout fingerprint`() {
        fun fingerprint(vararg modifiers: Modifier) =
            modifiers.asList().flattenModifiers().toStylePatch().resolve().layoutFingerprint()
        val plain = fingerprint()
        assertNotEquals(plain, fingerprint(Modifier.textEffects(Bold())), "bold changes glyph metrics")
        assertNotEquals(plain, fingerprint(Modifier.clip()), "clip is baked into the layout node")
    }

    @Test
    fun `hover applies to the hovered node and its ancestors`() {
        val child = BoxNode(
            id = "c",
            modifiers = listOf(Modifier.size(40.px, 40.px).background(UiColor.White).input(hoverable = true)),
        )
        val parent = BoxNode(id = "p", measurePolicy = UiMeasurePolicies.Column).also { it.children.add(child) }
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children.add(parent) }.attached()
        val runtime = HollowUiRuntime()
        runtime.frame(root, 200f, 200f, 10f, 10f, 0L)
        runtime.frame(root, 200f, 200f, 10f, 10f, 0L)
        assertTrue(UiState.HOVER in child.effectiveStates(), "the hovered leaf is hovered")
        assertTrue(UiState.HOVER in parent.effectiveStates(), "its ancestor is hovered too")
    }

    @Test
    fun `an active sibling transition reuses an unchanged base style`() {
        val animated = BoxNode(modifiers = listOf(Modifier.scale(1f)))
        val stable = BoxNode(modifiers = listOf(Modifier.size(20.px, 20.px)))
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also {
            it.children.add(animated)
            it.children.add(stable)
        }.attached()
        val resolver = UiModifierResolver()
        resolver.resolve(root, nowMillis = 0L)

        animated.modifiers[0] = Modifier.scale(2f)
        resolver.resolve(root, nowMillis = 0L)
        val stableStyle = stable.resolvedSnapshot

        resolver.resolve(root, nowMillis = 50L)
        assertSame(stableStyle, stable.resolvedSnapshot)
    }
}
