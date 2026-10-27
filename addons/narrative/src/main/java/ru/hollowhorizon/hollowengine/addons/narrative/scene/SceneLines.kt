package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.withTimeoutOrNull
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryInstruction
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.TextPart
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.TextTemplate
import kotlin.time.Duration.Companion.milliseconds

/** The lines and menus of a scene: what the presenter shows and the participants answer. */
internal class SceneLines(
    private val player: ScenePlayer,
    private val answers: SceneAnswers,
    private val calls: SceneCalls,
) {
    private val presentation get() = player.presentation

    /** Shows a line, running its inline commands as typing reaches them; returns false when a track preempted it. */
    suspend fun say(speaker: DialogueCharacter?, template: TextTemplate): Boolean {
        if (!player.preemptible { presentation.beginLine(player, speaker) }) return false
        val buffer = StringBuilder()

        suspend fun flush(): Boolean {
            if (buffer.isEmpty()) return true
            val text = buffer.toString()
            buffer.clear()
            return player.preemptible { presentation.appendText(player, text) }
        }

        for (part in template.parts) {
            when (part) {
                is TextPart.Literal -> buffer.append(part.text)
                is TextPart.Interpolation -> buffer.append(player.evaluate(part.expr).display())
                is TextPart.InlineCall -> if (!flush() || !calls.inline(part.call)) return false
                is TextPart.WaitInput -> if (!flush() || !player.preemptible { presentation.waitForInput(player) }) return false
            }
        }
        if (!flush()) return false
        return player.preemptible { presentation.endLine(player) }
    }

    /**
     * Shows a menu and waits for its decision; returns where the flow goes next: into the chosen option, past the
     * group when no option is available, or null when a track preempted it.
     */
    suspend fun menu(menu: StoryInstruction.Menu): Int? {
        val available = menu.options.filter { option -> option.condition?.let { player.evaluate(it).isTruthy() } ?: true }
        if (available.isEmpty()) return menu.exit

        val presented = available.mapIndexed { index, option ->
            PresentedChoice(
                index = index,
                id = option.id,
                text = player.render(option.text),
                metadata = option.args.associate { arg -> (arg.name ?: "") to player.evaluate(arg.expr) },
            )
        }

        val open = answers.open(presented.size)
        var winner = -1
        try {
            if (!player.preemptible { presentation.showChoices(player, presented) }) return null
            val timeout = player.votePolicy.timeoutMillis
            val decided = player.preemptible {
                if (timeout != null) withTimeoutOrNull(timeout.milliseconds) { open.signal.await() } else open.signal.await()
            }
            if (!decided) return null
            winner = answers.winner(open)
        } finally {
            answers.close()
            runCatching { presentation.hideChoices(player, winner) }
        }

        val choice = presented[winner]
        SceneEvent.Chosen.post(SceneEvent.Chosen(player, winner, choice.id, choice.text, choice.metadata, open.votes.toMap()))
        return available[winner].bodyStart
    }
}
