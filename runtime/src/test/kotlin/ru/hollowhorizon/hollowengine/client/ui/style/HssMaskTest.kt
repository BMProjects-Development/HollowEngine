package ru.hollowhorizon.hollowengine.client.ui.style

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.flattenModifiers
import ru.hollowhorizon.hollowengine.client.ui.toStylePatch
import kotlin.test.*

/**
 * `mask: linear-gradient(...)` follows CSS closely enough that a recipe copied from the web works,
 * which is only true if the defaults match: top-to-bottom without an angle, and stops without a
 * position spread evenly between the ones that have one.
 */
class HssMaskTest {
    private fun styleOf(hss: String) =
        compileHss(".x { $hss }").rules.single().patch.modifiers().flattenModifiers().toStylePatch().resolve()

    private fun mask(gradient: String) = assertNotNull(styleOf("mask: $gradient;").filter.linearMask())

    @Test
    fun `the everyday recipe reads as written`() {
        val gradient = mask("linear-gradient(to bottom, transparent, white 20%, white 80%, transparent)")

        assertEquals(180f, gradient.angle)
        assertEquals(listOf(0f, 0.2f, 0.8f, 1f), gradient.stops.map { it.position })
        assertEquals(listOf(0f, 1f, 1f, 0f), gradient.stops.map { it.alpha })
    }

    @Test
    fun `stops without a position are spread evenly`() {
        val gradient = mask("linear-gradient(transparent, white, white, transparent)")

        assertEquals(listOf(0f, 1f / 3f, 2f / 3f, 1f), gradient.stops.map { it.position })
        // No angle written means top to bottom, as in CSS.
        assertEquals(180f, gradient.angle)
    }

    @Test
    fun `only the alpha of a stop matters`() {
        // Black and white both show; `transparent` and an explicit alpha hide.
        val gradient = mask("linear-gradient(black, #ffffff, rgba(255, 0, 0, 0.25), transparent)")

        assertEquals(listOf(1f, 1f, 0.25f, 0f), gradient.stops.map { it.alpha })
    }

    @Test
    fun `a masked node is composited on its own layer`() {
        // Without this the mask would have nothing to apply to: the node has to be drawn to a texture.
        assertTrue(mask("linear-gradient(to bottom, white, transparent)").requiresLayer)
    }

    @Test
    fun `none clears the mask and leaves other filters alone`() {
        val style = styleOf("filter: blur(4px); mask: linear-gradient(white, transparent); mask: none;")

        assertNull(style.filter.linearMask())
        assertEquals(4f, style.filter.blurRadius(), "the blur must survive clearing the mask")
    }

    @Test
    fun `a gradient the shader cannot express is reported, not silently mangled`() {
        fun apply(gradient: String) {
            val modifier = assertNotNull(HssSchema.compile("mask", gradient))
            listOf(modifier).flattenModifiers().toStylePatch().resolve()
        }

        assertFailsWith<IllegalArgumentException> { apply("radial-gradient(white, transparent)") }
        assertFailsWith<IllegalArgumentException> { apply("linear-gradient()") }
    }
}
