package ru.hollowhorizon.hollowengine.addons.narrative.speech

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextDocument
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextParser
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextStyle

/** Story markup as chat can show it: animations are dropped and a gradient keeps its first color. */
internal object ChatText {
    /** The command chat buttons of lines and choices run. */
    const val COMMAND = "/hollowengine dialogue"

    fun of(markup: String): MutableComponent = of(FormattedTextParser.parse(markup))

    fun of(document: FormattedTextDocument): MutableComponent {
        val result = Component.empty()
        document.spans.forEach { span -> result.append(Component.literal(span.text).withStyle(styleOf(span.styles))) }
        return result
    }

    private fun styleOf(styles: List<FormattedTextStyle>): Style = styles.fold(Style.EMPTY) { style, it ->
        when (it) {
            FormattedTextStyle.Bold -> style.withBold(true)
            FormattedTextStyle.Italic -> style.withItalic(true)
            FormattedTextStyle.Underline -> style.withUnderlined(true)
            FormattedTextStyle.Strikethrough -> style.withStrikethrough(true)
            is FormattedTextStyle.Color -> style.withColor(it.rgb)
            is FormattedTextStyle.Gradient -> style.withColor(it.from.rgb)
            is FormattedTextStyle.Animation -> style
        }
    }

    private val FormattedTextStyle.Color.rgb: TextColor get() = TextColor.fromRgb(red shl 16 or (green shl 8) or blue)
}
