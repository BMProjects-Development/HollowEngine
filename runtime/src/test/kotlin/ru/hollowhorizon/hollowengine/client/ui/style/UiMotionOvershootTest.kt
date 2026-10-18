package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiMotionOvershootTest {
    @Test
    fun `cubic bezier output and interpolation preserve overshoot`() {
        val easing = TransitionEasing.CubicBezier(0.34f, 1.56f, 0.64f, 1f)
        val progress = easing.transform(0.5f)
        val from = listOf(Modifier.translate(0f, 0f)).flattenModifiers().toStylePatch().resolve()
        val to = listOf(Modifier.translate(100f, 0f)).flattenModifiers().toStylePatch().resolve()

        val interpolated = from.interpolate(to, UiTransitionProgress.all(progress))

        assertTrue(progress > 1f)
        assertTrue(interpolated.translate.x > 100f)
    }

    @Test
    fun `hss transition resolver preserves cubic bezier overshoot`() {
        val stylesheet = compileHss(
            """
                #logo {
                    scale: 0.9;
                    rotate: 0 0 0;
                    transition: all 420ms cubic-bezier(0.34, 1.56, 0.64, 1);
                }
                #logo:hover {
                    scale: 1.2;
                    rotate: 0 0 360;
                }
            """.trimIndent(),
        )
        val node = BoxNode(id = "logo")
        val resolver = UiModifierResolver(stylesheet = stylesheet)
        resolver.resolve(node, nowMillis = 0L)
        node.states += UiState.HOVER
        resolver.resolve(node, nowMillis = 1L)

        resolver.resolve(node, nowMillis = 211L)

        assertTrue(node.resolvedSnapshot.scale.x > 1.2f, "scale was ${node.resolvedSnapshot.scale.x}")
        assertTrue(node.resolvedSnapshot.rotate.z > 360f, "rotation was ${node.resolvedSnapshot.rotate.z}")
    }

    @Test
    fun `an overshooting easing never pushes opacity out of range`() {
        val easing = TransitionEasing.CubicBezier(0.34f, 1.76f, 0.64f, 1f)
        val progress = easing.transform(0.5f)
        val from = listOf(Modifier.opacity(0f).translate(0f, 22f)).flattenModifiers().toStylePatch().resolve()
        val to = listOf(Modifier.opacity(1f).translate(0f, 0f)).flattenModifiers().toStylePatch().resolve()

        val interpolated = from.interpolate(to, UiTransitionProgress.all(progress))

        assertTrue(progress > 1f, "the curve has to overshoot for this to mean anything")
        assertEquals(1f, interpolated.opacity, "opacity is clamped even while the curve overshoots")
        assertTrue(interpolated.translate.y < 0f, "the transform still overshoots its target")
    }

    @Test
    fun `stroke alias participates in hss transitions`() {
        val stylesheet = compileHss(
            """
                .key { stroke: #000000; transition: stroke 200ms linear; }
                .key:hover { stroke: #ffffff; }
            """.trimIndent(),
        )
        val node = BoxNode(tags = listOf("key"))
        val resolver = UiModifierResolver(stylesheet = stylesheet)
        resolver.resolve(node, nowMillis = 0L)
        node.states += UiState.HOVER
        resolver.resolve(node, nowMillis = 1L)
        resolver.resolve(node, nowMillis = 101L)

        val color = (node.resolvedSnapshot.shapeStroke as UiPaint.Color).color
        assertTrue(color.red in 0.45f..0.55f, "stroke changed without interpolation: ${color.red}")
    }
}
