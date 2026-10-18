package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.hollowhorizon.hollowengine.client.ui.text.*
import ru.hollowhorizon.hollowengine.common.dialogue.text.FormattedTextAnimation
import ru.hollowhorizon.hollowengine.common.dialogue.text.FormattedTextDocument
import ru.hollowhorizon.hollowengine.common.dialogue.text.FormattedTextParser
import ru.hollowhorizon.hollowengine.common.dialogue.text.FormattedTextStyle
import ru.hollowhorizon.hollowengine.client.ui.text.Gradient as UiGradient

/**
 * Renders the lightweight markup understood by [FormattedTextParser] as an ordinary inline-flow
 * text node. [visibleCharacters] counts rendered Unicode characters, not source markup.
 *
 * When [pendingTags] is non-empty, unrevealed text remains in layout with those tags. This is useful
 * for a typewriter whose box must not jump as characters appear. With no pending tags it is omitted.
 */
@Composable
fun FormattedText(
    value: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    visibleCharacters: Int = Int.MAX_VALUE,
    pendingTags: Iterable<String> = emptyList(),
) {
    val document = remember(value) { FormattedTextParser.parse(value) }
    FormattedText(document, id, tags, modifier, attributes, visibleCharacters, pendingTags)
}

@Composable
fun FormattedText(
    document: FormattedTextDocument,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    visibleCharacters: Int = Int.MAX_VALUE,
    pendingTags: Iterable<String> = emptyList(),
) {
    Text(id = id, tags = tags, modifier = modifier, attributes = attributes) {
        FormattedTextSpans(document, visibleCharacters, pendingTags)
    }
}

/** Emits only spans, allowing formatted text to share one inline flow with a custom prefix. */
@Composable
fun FormattedTextSpans(
    value: String,
    visibleCharacters: Int = Int.MAX_VALUE,
    pendingTags: Iterable<String> = emptyList(),
) {
    val document = remember(value) { FormattedTextParser.parse(value) }
    FormattedTextSpans(document, visibleCharacters, pendingTags)
}

@Composable
fun FormattedTextSpans(
    document: FormattedTextDocument,
    visibleCharacters: Int = Int.MAX_VALUE,
    pendingTags: Iterable<String> = emptyList(),
) {
    val visibleLimit = visibleCharacters.coerceAtLeast(0)
    val hiddenTags = pendingTags.toList()
    var consumed = 0

    for (span in document.spans) {
        val spanLength = span.text.codePointCount(0, span.text.length)
        val visibleInSpan = (visibleLimit - consumed).coerceIn(0, spanLength)
        val split = span.text.offsetByCodePoints(0, visibleInSpan)
        val modifier = span.styles.toModifier()

        if (split > 0) Span(span.text.substring(0, split), modifier = modifier)
        if (split < span.text.length && hiddenTags.isNotEmpty()) {
            Span(span.text.substring(split), tags = hiddenTags, modifier = modifier)
        }
        consumed += spanLength
    }
}

private fun List<FormattedTextStyle>.toModifier(): Modifier? {
    if (isEmpty()) return null
    val effects = map { it.toUiEffect() }
    return Modifier.textEffects(*effects.toTypedArray())
}

private fun FormattedTextStyle.toUiEffect(): UiTextEffect = when (this) {
    FormattedTextStyle.Bold -> Bold()
    FormattedTextStyle.Italic -> Italic()
    FormattedTextStyle.Underline -> Underline()
    FormattedTextStyle.Strikethrough -> Strikethrough()
    is FormattedTextStyle.Color -> TextColor(toUiColor())
    is FormattedTextStyle.Gradient -> UiGradient(
        colors = listOf(from.toUiColor(), to.toUiColor()),
        speed = speed,
    )

    is FormattedTextStyle.Animation -> when (type) {
        FormattedTextAnimation.RAINBOW -> Rainbow(
            frequency = number("frequency", 0.5f),
            saturation = number("saturation", 1f),
            brightness = number("brightness", 1f),
            speed = number("speed", 0.5f),
            phaseOffset = number("phase", 0f),
        )

        FormattedTextAnimation.PULSE -> Pulse(
            frequency = number("frequency", 1.5f),
            amplitude = number("amplitude", 0.4f),
            minAlpha = number("min-alpha", 0.3f),
        )

        FormattedTextAnimation.WAVE -> Wave(
            amplitude = number("amplitude", 3f),
            frequency = number("frequency", 1.5f),
            speed = number("speed", 2f),
            phaseOffset = number("phase", 0f),
        )

        FormattedTextAnimation.SHAKE -> Shake(
            amplitude = number("amplitude", 2f),
            frequency = number("frequency", 10f),
            seed = number("seed", 42f),
        )

        FormattedTextAnimation.WIGGLE -> Wiggle(
            amplitude = number("amplitude", 2f),
            frequency = number("frequency", 2f),
            speed = number("speed", 1.5f),
            angleDegrees = number("angle", 0f),
        )

        FormattedTextAnimation.SWING -> Swing(
            amplitude = number("amplitude", 5f),
            frequency = number("frequency", 0.8f),
            speed = number("speed", 1.2f),
        )

        FormattedTextAnimation.GLITCH -> Glitch(
            frequency = number("frequency", 3f),
            intensity = number("intensity", 2f),
            chromaticAberration = flags["chromatic"] ?: true,
        )
    }
}

private fun FormattedTextStyle.Animation.number(name: String, default: Float): Float = parameters[name] ?: default

private fun FormattedTextStyle.Color.toUiColor() = UiColor(
    red = red / 255f,
    green = green / 255f,
    blue = blue / 255f,
    alpha = alpha / 255f,
)
