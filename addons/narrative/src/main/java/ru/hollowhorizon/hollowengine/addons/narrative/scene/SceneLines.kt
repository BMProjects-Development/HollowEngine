package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.addons.narrative.poll.PollOption
import ru.hollowhorizon.hollowengine.addons.narrative.poll.Polls
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryInstruction
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.TextTemplate
import ru.hollowhorizon.hollowengine.addons.narrative.speech.say

/** The lines and menus of a scene, said and asked of its participants through [say] and [Polls]. */
internal class SceneLines(private val player: ScenePlayer) {
    suspend fun say(speaker: DialogueCharacter?, template: TextTemplate) =
        say(speaker, player.render(template), player.onlineParticipants)

    /**
     * Asks the menu and returns where the flow goes next: into the chosen option, or past the group when no option is
     * available or the participants chose one a listener added.
     */
    suspend fun menu(menu: StoryInstruction.Menu): Int {
        val available = menu.options.filter { option -> option.condition?.let { player.evaluate(it).isTruthy() } ?: true }
        val options = available.mapIndexed { index, option ->
            PollOption(
                key = option.id ?: index.toString(),
                text = player.render(option.text),
                metadata = option.args.associate { arg -> (arg.name ?: "") to player.evaluate(arg.expr) },
            )
        }
        val chosen = Polls.ask(player.onlineParticipants, options) ?: return menu.exit
        val index = options.indexOf(chosen)
        SceneEvent.Chosen.post(SceneEvent.Chosen(player, index, available.getOrNull(index)?.id, chosen.text, chosen.metadata))
        return available.getOrNull(index)?.bodyStart ?: menu.exit
    }
}
