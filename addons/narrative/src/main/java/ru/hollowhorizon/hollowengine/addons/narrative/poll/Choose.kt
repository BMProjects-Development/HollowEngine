package ru.hollowhorizon.hollowengine.addons.narrative.poll

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.speech.ChatText
import ru.hollowhorizon.hollowengine.addons.narrative.speech.audience
import ru.hollowhorizon.hollowengine.addons.narrative.story.Action
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.action
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory

/**
 * Asks [to], or the audience of the scene it runs in, to choose one of [options] and returns the index of the chosen
 * one; -1 when they chose an option a listener of [ChoiceEvent] added. In a story the choice is kept: a restart
 * does not ask again.
 *
 * ```
 * when (choose(listOf("Help", "Leave"))) {
 *     0 -> help()
 *     else -> leave()
 * }
 * ```
 */
@EditorCategory("hollowengine.gui.ide.story.category.dialogue")
@Action("choose")
suspend fun choose(options: List<String>, to: List<ServerPlayer>? = null): Int = action(options, to) {
    perform {
        val players = to ?: audience() ?: throw StoryException("choose() outside a scene needs to = players")
        val asked = options.mapIndexed { index, text -> PollOption(index.toString(), text) }
        asked.indexOf(Polls.ask(players, asked))
    }
}

/** Shows a poll as numbered options in chat; a click votes, and the chosen one is repeated once it is decided. */
object ChatPollProvider : PollProvider {
    override fun show(poll: Poll) {
        poll.options.forEachIndexed { index, option ->
            val line = Component.literal("  ${index + 1}. ").withStyle(ChatFormatting.GRAY)
                .append(ChatText.of(option.text).withStyle(ChatFormatting.YELLOW))
                .withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "${ChatText.COMMAND} choose $index")) }
            poll.players.forEach { it.sendSystemMessage(line) }
        }
    }

    override fun close(poll: Poll, chosen: Int) {
        val option = poll.options.getOrNull(chosen) ?: return
        val line = Component.literal("» ").append(ChatText.of(option.text)).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        poll.players.forEach { it.sendSystemMessage(line) }
    }
}
