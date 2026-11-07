package ru.hollowhorizon.hollowengine.client.ui.widgets

/**
 * Works out arithmetic typed into a number field, such as `20 * 60`: numbers, `+ - * / %`, parentheses and a sign
 * before a number. A number may end the way Kotlin writes one, `1.5f` or `20L`.
 */
object UiArithmetic {
    /** The value of [text], or null when it is anything but arithmetic on numbers. */
    fun evaluate(text: String): Double? {
        val reader = Reader(text)
        val value = reader.sum() ?: return null
        reader.skipSpaces()
        return value.takeIf { reader.done && it.isFinite() }
    }

    private class Reader(val text: String) {
        var at = 0

        val done get() = at >= text.length

        fun skipSpaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun take(char: Char): Boolean {
            skipSpaces()
            if (at < text.length && text[at] == char) {
                at++
                return true
            }
            return false
        }

        fun sum(): Double? {
            var value = product() ?: return null
            while (true) value = when {
                take('+') -> value + (product() ?: return null)
                take('-') -> value - (product() ?: return null)
                else -> return value
            }
        }

        fun product(): Double? {
            var value = unary() ?: return null
            while (true) value = when {
                take('*') -> value * (unary() ?: return null)
                take('/') -> value / (unary() ?: return null)
                take('%') -> value % (unary() ?: return null)
                else -> return value
            }
        }

        fun unary(): Double? = when {
            take('-') -> unary()?.unaryMinus()
            take('+') -> unary()
            take('(') -> sum()?.takeIf { take(')') }
            else -> number()
        }

        fun number(): Double? {
            skipSpaces()
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] == '.' || text[at] == '_')) at++
            val value = text.substring(start, at).replace("_", "").toDoubleOrNull() ?: return null
            if (at < text.length && text[at] in SUFFIXES) at++
            return value
        }
    }

    private const val SUFFIXES = "fFL"
}
