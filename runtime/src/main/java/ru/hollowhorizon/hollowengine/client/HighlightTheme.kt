package ru.hollowhorizon.hollowengine.client

import ru.hollowhorizon.hollowengine.common.utils.Color

object HighlightTheme {
    val COMMENT = Color.LIGHT_GRAY
    val TODO_COMMENT = Color("A8C023")
    val KEYWORD = Color("CF8E6D")
    val STRING = Color("6AAB73")
    val PROPERTY_IDENTIFIER = Color("C77DBB")
    val EXTENSION_RECEIVER = Color("56A8F5")
    val ANNOTATION = Color("B3AE60")
    val VALUE_ARGUMENT_NAME = Color("57AAF7")
    val NAME_REFERENCE = Color("BCBEC4")
    val NUMERIC_LITERAL = Color("2AACB8")
    val PARAMETER = Color("A9B7C6")
    val TOP_LEVEL = Color("F2F4F2")

    val VARIABLE = Color("A9B7C6")
    val CLASS = Color("A9B7C6")
    val INTERFACE = Color("A9B7C6")
    val FUNCTION = Color("FFC66D")
    val METHOD = Color("FFC66D")

    /** Fallback for a DSL builder whose marker annotation could not be named. */
    val DSL_MARKER = Color("67C5A8")

    val DEFAULT = Color.WHITE

    fun dslMarkerArgb(markerFqName: String): Int {
        val hue = ((markerFqName.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()
        return hsvToArgb(hue, DslMarkerSaturation, DslMarkerValue)
    }

    /**
     * The color of slot [index] of a set of things told apart by color. Each slot turns the hue by the golden angle,
     * so neighboring slots are always far apart, however many there are.
     */
    fun paletteArgb(index: Int): Int = hsvToArgb((index * GoldenAngle) % 360f, PaletteSaturation, DslMarkerValue)

    private const val GoldenAngle = 137.50776f
    private const val PaletteSaturation = 0.6f
    private const val DslMarkerSaturation = 0.45f
    private const val DslMarkerValue = 0.88f

    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val sector = (hue / 60f).toInt() % 6
        val fraction = hue / 60f - (hue / 60f).toInt()
        val p = value * (1f - saturation)
        val q = value * (1f - saturation * fraction)
        val t = value * (1f - saturation * (1f - fraction))
        val (red, green, blue) = when (sector) {
            0 -> Triple(value, t, p)
            1 -> Triple(q, value, p)
            2 -> Triple(p, value, t)
            3 -> Triple(p, q, value)
            4 -> Triple(t, p, value)
            else -> Triple(value, p, q)
        }
        fun channel(component: Float) = (component.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }
}
