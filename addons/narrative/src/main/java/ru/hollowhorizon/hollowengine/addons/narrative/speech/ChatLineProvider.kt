package ru.hollowhorizon.hollowengine.addons.narrative.speech

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shows a line in chat under its speaker's name and moves on once it is read, or when it is clicked.
 */
object ChatLineProvider : LineProvider {
    /** The shortest time a line stays. */
    var minReadMillis = 1200L

    /** How long a character takes to read. */
    var readMillisPerChar = 45L

    override suspend fun show(line: Line) {
        val length = line.text.visibleLength
        if (line.text.plainText.isBlank()) return
        val message = Component.empty()
        line.speaker?.let { message.append(Component.literal("${it.name}: ").withStyle(ChatFormatting.GOLD)) }
        val text = ChatText.of(line.text)
        message.append(if (line.speaker == null) text.withStyle(ChatFormatting.ITALIC) else text)
        message.withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "${ChatText.COMMAND} advance")) }
        line.audience.forEach { it.sendSystemMessage(message) }
        Reading.awaitAdvance(line.audience, maxOf(minReadMillis, length * readMillisPerChar).milliseconds)
    }
}
