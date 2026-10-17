package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.flattenModifiers
import ru.hollowhorizon.hollowengine.client.ui.toStylePatch
import kotlin.test.*

class HssLinearGradientTest {
    @Test
    fun `omitted angle points down as in CSS`() {
        assertEquals(180f, paint("linear-gradient(black, white)").angleDegrees)
        assertEquals(180f, mask("linear-gradient(black, white)").angle)
    }

    @Test
    fun `coloured gradients and masks agree on directions and degree angles`() {
        for ((direction, angle) in listOf("to top" to 0f, "to right" to 90f, "to bottom" to 180f, "to left" to 270f,
            "45deg" to 45f, "-90deg" to -90f, "450deg" to 450f, "0" to 0f)) {
            val source = "linear-gradient($direction, black, white)"
            assertEquals(angle, paint(source).angleDegrees, source)
            assertEquals(angle, mask(source).angle, source)
        }
    }

    @Test
    fun `background border and shape fill accept the same direction`() {
        for (declaration in listOf("background", "border-color", "shape-fill")) {
            assertNotNull(HssSchema.compile(declaration, "linear-gradient(to right, black, white)"))
        }
        assertNotNull(HssSchema.compile("border", "1px linear-gradient(to bottom, black, white)"))
    }

    @Test
    fun `unsupported directions and malformed degree angles are reported`() {
        assertFailsWith<IllegalArgumentException> { paint("linear-gradient(to nowhere, black, white)") }
        assertFailsWith<IllegalArgumentException> { paint("linear-gradient(wrongdeg, black, white)") }
    }

    private fun paint(source: String): UiPaint.LinearGradient = assertIs<UiPaint.LinearGradient>(parsePaint(source))

    private fun mask(source: String): UiFilterEffect.LinearMask = assertNotNull(
        compileHss(".x { mask: $source; }").rules.single().patch.modifiers()
            .flattenModifiers().toStylePatch().resolve().filter.linearMask(),
    )
}
