package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.*
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextParser
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextSpan
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextStyle
import kotlin.time.Duration.Companion.milliseconds

/**
 * Plays the dialogue in chat: a line as a message under its speaker's name, a menu as options to click. It needs no
 * screen, so it is what scenes use until the dialogue screen is redesigned.
 *
 * @param autoAdvance whether a line moves on by itself once it is read; a `[-]` pause always waits for a click.
 * @param minReadMillis the shortest time a line stays before it moves on by itself.
 * @param readMillisPerChar how long a character takes to read.
 */
class ChatDialoguePresentation(
    val autoAdvance: Boolean = true,
    val minReadMillis: Long = 1200L,
    val readMillisPerChar: Long = 45L,
) : DialoguePresenter {
    private var speaker: DialogueCharacter? = null
    private val text = StringBuilder()
    private var options: List<PresentedChoice> = emptyList()

    override suspend fun beginLine(session: SceneSession, speaker: DialogueCharacter?) {
        this.speaker = speaker
        text.clear()
    }

    override suspend fun appendText(session: SceneSession, text: String) {
        this.text.append(text)
    }

    override suspend fun waitForInput(session: SceneSession) {
        if (send(session) > 0) session.awaitAdvance()
    }

    override suspend fun endLine(session: SceneSession) {
        val length = send(session)
        if (length == 0) return
        if (!autoAdvance) return session.awaitAdvance()
        val reading = maxOf(minReadMillis, length * readMillisPerChar)
        withTimeoutOrNull(reading.milliseconds) { session.awaitAdvance() }
    }

    override suspend fun showChoices(session: SceneSession, options: List<PresentedChoice>) {
        this.options = options
        options.forEach { option ->
            val line = Component.literal("  ${option.index + 1}. ").withStyle(ChatFormatting.GRAY)
                .append(formatted(option.text).withStyle(ChatFormatting.YELLOW)).withStyle {
                    it.withClickEvent(
                        ClickEvent(
                            ClickEvent.Action.RUN_COMMAND,
                            "$COMMAND choose ${option.index}"
                        )
                    )
                }
            session.onlineParticipants.forEach { it.sendSystemMessage(line) }
        }
    }

    override suspend fun hideChoices(session: SceneSession, chosen: Int) {
        val option = options.firstOrNull { it.index == chosen }
        options = emptyList()
        if (option == null) return
        val line =
            Component.literal("» ").append(formatted(option.text)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        session.onlineParticipants.forEach { it.sendSystemMessage(line) }
    }

    /** Sends what the line has so far with a button to move on; returns how many characters it shows. */
    private fun send(session: SceneSession): Int {
        val document = FormattedTextParser.parse(text.toString())
        text.clear()
        if (document.plainText.isBlank()) return 0
        val message = Component.empty()
        speaker?.let { message.append(Component.literal("${it.name}: ").withStyle(ChatFormatting.GOLD)) }
        message.append(formatted(document.spans).let { if (speaker == null) it.withStyle(ChatFormatting.ITALIC) else it })
        message.append(" ").append(
            Component.translatable(ADVANCE).withStyle(ChatFormatting.DARK_GRAY)
                .withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "$COMMAND advance")) },
        )
        session.onlineParticipants.forEach { it.sendSystemMessage(message) }
        return document.visibleLength
    }

    private fun formatted(markup: String): MutableComponent = formatted(FormattedTextParser.parse(markup).spans)

    /** The markup as chat can show it: animations are dropped and a gradient keeps its first color. */
    private fun formatted(spans: List<FormattedTextSpan>): MutableComponent {
        val result = Component.empty()
        spans.forEach { span -> result.append(Component.literal(span.text).withStyle(styleOf(span.styles))) }
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

    private companion object {
        const val COMMAND = "/hollowengine dialogue"
        const val ADVANCE = "hollowengine.dialogue.advance"
    }
}
