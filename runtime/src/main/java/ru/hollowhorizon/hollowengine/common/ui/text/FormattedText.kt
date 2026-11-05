package ru.hollowhorizon.hollowengine.common.ui.text

/**
 * Parsed dialogue markup. Markup stays a string in the network contract, while this model gives
 * every presenter the same visible text and formatting boundaries.
 */
data class FormattedTextDocument(
    val spans: List<FormattedTextSpan>,
    val diagnostics: List<FormattedTextDiagnostic> = emptyList(),
) {
    val plainText: String by lazy(LazyThreadSafetyMode.NONE) { spans.joinToString("") { it.text } }

    /** Number of Unicode characters shown to the reader; formatting tags never count. */
    val visibleLength: Int by lazy(LazyThreadSafetyMode.NONE) {
        spans.sumOf { it.text.codePointCount(0, it.text.length) }
    }
}

data class FormattedTextSpan(
    val text: String,
    val styles: List<FormattedTextStyle> = emptyList(),
)

sealed interface FormattedTextStyle {
    data object Bold : FormattedTextStyle
    data object Italic : FormattedTextStyle
    data object Underline : FormattedTextStyle
    data object Strikethrough : FormattedTextStyle

    data class Color(
        val red: Int,
        val green: Int,
        val blue: Int,
        val alpha: Int = 255,
    ) : FormattedTextStyle

    data class Gradient(
        val from: Color,
        val to: Color,
        val speed: Float = 0f,
    ) : FormattedTextStyle

    /** Parameters are validated by [FormattedTextParser] before this value is created. */
    data class Animation(
        val type: FormattedTextAnimation,
        val parameters: Map<String, Float> = emptyMap(),
        val flags: Map<String, Boolean> = emptyMap(),
    ) : FormattedTextStyle
}

enum class FormattedTextAnimation {
    RAINBOW,
    PULSE,
    WAVE,
    SHAKE,
    WIGGLE,
    SWING,
    GLITCH,
}

data class FormattedTextDiagnostic(
    val offset: Int,
    val length: Int,
    val message: String,
)

/**
 * Parser for the deliberately small, HTML-like dialogue markup.
 *
 * Supported tags are `<b>`, `<i>`, `<u>`, `<s>`, `<color=#RRGGBB>`, `<gradient from=... to=...>`,
 * and the animation tags `<rainbow>`, `<pulse>`, `<wave>`, `<shake>`, `<wiggle>`, `<swing>` and
 * `<glitch>`. Tags nest. Unknown or invalid tags stay visible instead of silently losing author
 * text. `&lt;`, `&gt;`, `&amp;`, `&quot;` and `&apos;` insert literal reserved characters.
 *
 * Unclosed known tags apply until the current end of the string. This is intentional: a dialogue
 * line can arrive in fragments around an inline command, so the closing tag may arrive later.
 */
object FormattedTextParser {
    fun parse(source: String): FormattedTextDocument = Parser(source).parse()

    private class Parser(private val source: String) {
        private val spans = mutableListOf<FormattedTextSpan>()
        private val diagnostics = mutableListOf<FormattedTextDiagnostic>()
        private val openTags = mutableListOf<OpenTag>()
        private var cursor = 0

        fun parse(): FormattedTextDocument {
            val literal = StringBuilder()

            fun flush() {
                if (literal.isEmpty()) return
                emit(literal.toString())
                literal.clear()
            }

            while (cursor < source.length) {
                decodeEntity()?.let { decoded ->
                    literal.append(decoded)
                    return@let
                } ?: if (source[cursor] == '<') {
                    val token = readTag()
                    if (token == null) {
                        literal.append(source[cursor++])
                    } else {
                        flush()
                        apply(token)
                    }
                } else {
                    literal.append(source[cursor++])
                }
            }
            flush()
            openTags.forEach { tag ->
                diagnostics += FormattedTextDiagnostic(
                    offset = tag.offset,
                    length = tag.length,
                    message = "Unclosed formatting tag '<${tag.name}>'",
                )
            }
            return FormattedTextDocument(spans.toList(), diagnostics.toList())
        }

        /** Returns null without moving [cursor] when this is not a supported entity. */
        private fun decodeEntity(): String? {
            if (source[cursor] != '&') return null
            val entity = Entities.entries.firstOrNull { source.startsWith(it.key, cursor) } ?: return null
            cursor += entity.key.length
            return entity.value
        }

        private fun readTag(): TagToken? {
            val start = cursor
            val end = findTagEnd(start)
            if (end < 0) {
                diagnostics += FormattedTextDiagnostic(start, source.length - start, "Unterminated formatting tag")
                return null
            }

            val raw = source.substring(start, end + 1)
            val inner = source.substring(start + 1, end).trim()
            val parsed = parseToken(start, raw, inner) ?: return null
            cursor = end + 1
            return parsed
        }

        private fun findTagEnd(start: Int): Int {
            var quote: Char? = null
            var index = start + 1
            while (index < source.length) {
                val char = source[index]
                if (quote != null) {
                    if (char == quote) quote = null
                } else {
                    when (char) {
                        '\'', '"' -> quote = char
                        '>' -> return index
                    }
                }
                index++
            }
            return -1
        }

        private fun parseToken(offset: Int, raw: String, inner: String): TagToken? {
            if (inner.isEmpty()) return malformed(offset, raw, "Empty formatting tag")
            val closing = inner.startsWith('/')
            val body = if (closing) inner.drop(1).trim() else inner.removeSuffix("/").trim()
            val selfClosing = !closing && inner.endsWith('/')
            val nameEnd = body.indexOfFirst { it.isWhitespace() || it == '=' }.let { if (it < 0) body.length else it }
            val name = body.substring(0, nameEnd).lowercase()
            if (name.isEmpty() || name.any { !it.isLetterOrDigit() && it != '-' }) {
                return malformed(offset, raw, "Invalid formatting tag name")
            }

            val tail = body.substring(nameEnd)
            if (closing && tail.isNotBlank()) {
                return malformed(offset, raw, "Closing formatting tags cannot have attributes")
            }
            val attributes = if (closing) emptyMap() else parseAttributes(offset, raw, tail) ?: return null
            return TagToken(offset, raw, name, closing, selfClosing, attributes)
        }

        private fun parseAttributes(offset: Int, raw: String, tail: String): Map<String, String>? {
            var index = 0
            val attributes = linkedMapOf<String, String>()
            if (tail.trimStart().startsWith('=')) {
                index = tail.indexOf('=') + 1
                val value = tail.substring(index).trim().trimMatchingQuotes()
                if (value.isEmpty()) return malformed(offset, raw, "Expected a tag value")
                return mapOf(ValueAttribute to value)
            }

            while (index < tail.length) {
                while (index < tail.length && tail[index].isWhitespace()) index++
                if (index >= tail.length) break
                val keyStart = index
                while (index < tail.length && (tail[index].isLetterOrDigit() || tail[index] == '-')) index++
                if (index == keyStart) return malformed(offset, raw, "Expected an attribute name")
                val key = tail.substring(keyStart, index).lowercase()
                while (index < tail.length && tail[index].isWhitespace()) index++
                if (index >= tail.length || tail[index] != '=') {
                    return malformed(offset, raw, "Expected '=' after attribute '$key'")
                }
                index++
                while (index < tail.length && tail[index].isWhitespace()) index++
                if (index >= tail.length) return malformed(offset, raw, "Expected a value for attribute '$key'")

                val value = if (tail[index] == '"' || tail[index] == '\'') {
                    val quote = tail[index++]
                    val valueStart = index
                    while (index < tail.length && tail[index] != quote) index++
                    if (index >= tail.length) return malformed(offset, raw, "Unterminated value for attribute '$key'")
                    tail.substring(valueStart, index++)
                } else {
                    val valueStart = index
                    while (index < tail.length && !tail[index].isWhitespace()) index++
                    tail.substring(valueStart, index)
                }
                if (attributes.put(key, value) != null) {
                    return malformed(offset, raw, "Duplicate attribute '$key'")
                }
            }
            return attributes
        }

        private fun apply(token: TagToken) {
            if (token.closing) {
                val open = openTags.lastOrNull()
                if (open?.name == token.name) {
                    openTags.removeLast()
                } else {
                    diagnostics += FormattedTextDiagnostic(token.offset, token.raw.length, "Unexpected closing tag '${token.name}'")
                    emit(token.raw)
                }
                return
            }

            if (token.name == "br") {
                if (token.attributes.isEmpty()) emit("\n")
                else invalid(token, "Tag '<br>' does not accept attributes")
                return
            }

            val style = styleOf(token) ?: return
            if (token.selfClosing) {
                invalid(token, "Formatting tag '<${token.name}>' cannot be self-closing")
                return
            }
            openTags += OpenTag(token.name, style, token.offset, token.raw.length)
        }

        private fun styleOf(token: TagToken): FormattedTextStyle? = when (token.name) {
            "b", "strong" -> noAttributes(token, FormattedTextStyle.Bold)
            "i", "em" -> noAttributes(token, FormattedTextStyle.Italic)
            "u" -> noAttributes(token, FormattedTextStyle.Underline)
            "s", "strike" -> noAttributes(token, FormattedTextStyle.Strikethrough)
            "color" -> color(token)
            "gradient" -> gradient(token)
            "rainbow" -> animation(token, FormattedTextAnimation.RAINBOW, RainbowParameters)
            "pulse" -> animation(token, FormattedTextAnimation.PULSE, PulseParameters)
            "wave" -> animation(token, FormattedTextAnimation.WAVE, WaveParameters)
            "shake" -> animation(token, FormattedTextAnimation.SHAKE, ShakeParameters)
            "wiggle" -> animation(token, FormattedTextAnimation.WIGGLE, WiggleParameters)
            "swing" -> animation(token, FormattedTextAnimation.SWING, SwingParameters)
            "glitch" -> animation(token, FormattedTextAnimation.GLITCH, GlitchParameters, setOf("chromatic"))
            else -> {
                diagnostics += FormattedTextDiagnostic(token.offset, token.raw.length, "Unknown formatting tag '${token.name}'")
                emit(token.raw)
                null
            }
        }

        private fun noAttributes(token: TagToken, style: FormattedTextStyle): FormattedTextStyle? {
            if (token.attributes.isNotEmpty()) {
                invalid(token, "Tag '<${token.name}>' does not accept attributes")
                return null
            }
            return style
        }

        private fun color(token: TagToken): FormattedTextStyle? {
            if (!hasOnly(token, setOf(ValueAttribute))) return null
            val value = token.attributes[ValueAttribute]
                ?: return invalid(token, "Tag '<color>' expects a color, for example <color=#FF5555>")
            return parseColor(value) ?: invalid(token, "Unknown color '$value'")
        }

        private fun gradient(token: TagToken): FormattedTextStyle? {
            if (!hasOnly(token, setOf("from", "to", "speed"))) return null
            val fromValue = token.attributes["from"]
                ?: return invalid(token, "Tag '<gradient>' expects a 'from' color")
            val toValue = token.attributes["to"]
                ?: return invalid(token, "Tag '<gradient>' expects a 'to' color")
            val from = parseColor(fromValue) ?: return invalid(token, "Unknown color '$fromValue'")
            val to = parseColor(toValue) ?: return invalid(token, "Unknown color '$toValue'")
            val speed = token.attributes["speed"]?.let { readFloat(token, "speed", it) ?: return null } ?: 0f
            return FormattedTextStyle.Gradient(from, to, speed)
        }

        private fun animation(
            token: TagToken,
            type: FormattedTextAnimation,
            floatParameters: Set<String>,
            booleanParameters: Set<String> = emptySet(),
        ): FormattedTextStyle? {
            if (!hasOnly(token, floatParameters + booleanParameters)) return null
            val parameters = linkedMapOf<String, Float>()
            val flags = linkedMapOf<String, Boolean>()
            for ((name, value) in token.attributes) {
                if (name in floatParameters) {
                    parameters[name] = readFloat(token, name, value) ?: return null
                } else {
                    flags[name] = value.toBooleanStrictOrNull()
                        ?: return invalid(token, "Attribute '$name' expects true or false")
                }
            }
            return FormattedTextStyle.Animation(type, parameters, flags)
        }

        private fun readFloat(token: TagToken, name: String, value: String): Float? {
            val number = value.toFloatOrNull()
            if (number == null || !number.isFinite()) {
                invalid(token, "Attribute '$name' expects a finite number")
                return null
            }
            return number
        }

        private fun hasOnly(token: TagToken, allowed: Set<String>): Boolean {
            val unknown = token.attributes.keys - allowed
            if (unknown.isEmpty()) return true
            invalid(token, "Unknown attribute '${unknown.first()}' on tag '<${token.name}>'")
            return false
        }

        private fun emit(text: String) {
            if (text.isEmpty()) return
            val styles = openTags.map { it.style }
            val previous = spans.lastOrNull()
            if (previous?.styles == styles) {
                spans[spans.lastIndex] = previous.copy(text = previous.text + text)
            } else {
                spans += FormattedTextSpan(text, styles)
            }
        }

        private fun invalid(token: TagToken, message: String): Nothing? {
            diagnostics += FormattedTextDiagnostic(token.offset, token.raw.length, message)
            emit(token.raw)
            return null
        }

        private fun malformed(offset: Int, raw: String, message: String): Nothing? {
            diagnostics += FormattedTextDiagnostic(offset, raw.length, message)
            return null
        }
    }

    private data class TagToken(
        val offset: Int,
        val raw: String,
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attributes: Map<String, String>,
    )

    private data class OpenTag(
        val name: String,
        val style: FormattedTextStyle,
        val offset: Int,
        val length: Int,
    )

    private fun String.trimMatchingQuotes(): String {
        if (length < 2) return this
        val quoted = (first() == '"' && last() == '"') || (first() == '\'' && last() == '\'')
        return if (quoted) substring(1, lastIndex) else this
    }

    private fun parseColor(value: String): FormattedTextStyle.Color? {
        val normalized = NamedColors[value.lowercase()] ?: value
        if (!normalized.startsWith('#')) return null
        val hex = normalized.drop(1)
        val expanded = when (hex.length) {
            3, 4 -> buildString(hex.length * 2) { hex.forEach { append(it).append(it) } }
            6, 8 -> hex
            else -> return null
        }
        val rgba = expanded.toLongOrNull(16) ?: return null
        return if (expanded.length == 6) {
            FormattedTextStyle.Color(
                red = ((rgba shr 16) and 0xFF).toInt(),
                green = ((rgba shr 8) and 0xFF).toInt(),
                blue = (rgba and 0xFF).toInt(),
            )
        } else {
            FormattedTextStyle.Color(
                red = ((rgba shr 24) and 0xFF).toInt(),
                green = ((rgba shr 16) and 0xFF).toInt(),
                blue = ((rgba shr 8) and 0xFF).toInt(),
                alpha = (rgba and 0xFF).toInt(),
            )
        }
    }

    private const val ValueAttribute = "value"

    private val RainbowParameters = setOf("frequency", "saturation", "brightness", "speed", "phase")
    private val PulseParameters = setOf("frequency", "amplitude", "min-alpha")
    private val WaveParameters = setOf("amplitude", "frequency", "speed", "phase")
    private val ShakeParameters = setOf("amplitude", "frequency", "seed")
    private val WiggleParameters = setOf("amplitude", "frequency", "speed", "angle")
    private val SwingParameters = setOf("amplitude", "frequency", "speed")
    private val GlitchParameters = setOf("frequency", "intensity")

    private val Entities = mapOf(
        "&lt;" to "<",
        "&gt;" to ">",
        "&amp;" to "&",
        "&quot;" to "\"",
        "&apos;" to "'",
    )

    private val NamedColors = mapOf(
        "black" to "#000000",
        "dark-blue" to "#0000AA",
        "dark_blue" to "#0000AA",
        "dark-green" to "#00AA00",
        "dark_green" to "#00AA00",
        "dark-aqua" to "#00AAAA",
        "dark_aqua" to "#00AAAA",
        "dark-red" to "#AA0000",
        "dark_red" to "#AA0000",
        "dark-purple" to "#AA00AA",
        "dark_purple" to "#AA00AA",
        "gold" to "#FFAA00",
        "gray" to "#AAAAAA",
        "dark-gray" to "#555555",
        "dark_gray" to "#555555",
        "blue" to "#5555FF",
        "green" to "#55FF55",
        "aqua" to "#55FFFF",
        "red" to "#FF5555",
        "light-purple" to "#FF55FF",
        "light_purple" to "#FF55FF",
        "yellow" to "#FFFF55",
        "white" to "#FFFFFF",
    )
}
