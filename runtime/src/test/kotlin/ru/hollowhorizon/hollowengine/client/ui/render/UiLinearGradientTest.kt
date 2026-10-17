package ru.hollowhorizon.hollowengine.client.ui.render

import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.UiResolvedPaint
import ru.hollowhorizon.hollowengine.client.ui.shape.SvgFileParser
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UiLinearGradientTest {
    private val stops = listOf(UiGradientStop(0f, UiColor.Black), UiGradientStop(1f, UiColor.White))

    @Test
    fun `CSS cardinal angles shade toward the named edge`() {
        assertGray(1f, color(0f, 100f, 0f))
        assertGray(0f, color(0f, 100f, 100f))
        assertGray(1f, color(90f, 200f, 50f))
        assertGray(0f, color(90f, 0f, 50f))
        assertGray(1f, color(180f, 100f, 100f))
        assertGray(0f, color(180f, 100f, 0f))
        assertGray(1f, color(270f, 0f, 50f))
        assertGray(0f, color(270f, 200f, 50f))
    }

    @Test
    fun `oblique gradients use the box dimensions rather than a square UV space`() {
        assertGray(0f, color(135f, 0f, 0f))
        assertGray(1f, color(135f, 200f, 100f))
        assertGray(2f / 3f, color(135f, 200f, 0f))
        assertGray(1f / 3f, color(135f, 0f, 100f))
        assertGray(0.5f, color(135f, 100f, 50f))
    }

    @Test
    fun `negative angles and full turns preserve the same direction`() {
        for ((angle, equivalent) in listOf(-90f to 270f, 360f to 0f, 450f to 90f, -675f to 45f)) {
            assertEquals(color(angle, 75f, 25f), color(equivalent, 75f, 25f))
            assertEquals(direction(angle), direction(equivalent))
        }
    }

    @Test
    fun `both GPU paint renderers receive CSS direction vectors`() {
        for ((angle, expected) in listOf(0f to (0f to -1f), 90f to (1f to 0f), 180f to (0f to 1f), 270f to (-1f to 0f))) {
            val actual = direction(angle)
            assertEquals(expected.first, actual.first, 0.000001f, "x at $angle degrees")
            assertEquals(expected.second, actual.second, 0.000001f, "y at $angle degrees")
        }
    }

    @Test
    fun `SVG axes preserve their authored shading after conversion to CSS angles`() {
        for ((x2, y2, angle) in listOf(Triple(200, 0, 90f), Triple(0, 100, 180f), Triple(200, 100, 116.56505f))) {
            val document = SvgFileParser.parse("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100">
                  <defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="$x2" y2="$y2">
                    <stop offset="0" stop-color="black"/><stop offset="1" stop-color="white"/>
                  </linearGradient></defs>
                  <rect width="200" height="100" fill="url(#g)"/>
                </svg>
            """.trimIndent())
            val paint = assertIs<UiPaint.LinearGradient>(document.elements.single().paint)
            assertEquals(angle, paint.angleDegrees, 0.00001f)
            assertGray(0f, gradientColorAt(0f, 0f, 200f, 100f, paint.angleDegrees, paint.stops))
            assertGray(1f, gradientColorAt(x2.toFloat(), y2.toFloat(), 200f, 100f, paint.angleDegrees, paint.stops))
        }
    }

    private fun color(angle: Float, x: Float, y: Float): UiColor = gradientColorAt(x, y, 200f, 100f, angle, stops)

    private fun direction(angle: Float): Pair<Float, Float> {
        val paints = UiFloatArrayBuilder()
        UiPaintBufferEncoder(paints, UiFloatArrayBuilder()).append(
            UiResolvedPaint.LinearGradient(angle, stops), 1f, UiFilterChain.Empty, 200f, 100f,
        )
        val buffer = FloatBuffer.allocate(16)
        paints.writeTo(buffer)
        return buffer[10] to buffer[11]
    }

    private fun assertGray(expected: Float, actual: UiColor) {
        assertEquals(expected, actual.red, 0.00001f)
        assertEquals(expected, actual.green, 0.00001f)
        assertEquals(expected, actual.blue, 0.00001f)
        assertEquals(1f, actual.alpha, 0.00001f)
    }
}
