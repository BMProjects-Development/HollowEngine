package ru.hollowhorizon.hollowengine.addons.narrative.poll

import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.data.dataKey
import ru.hollowhorizon.hollowengine.common.ui.net.UiSession
import ru.hollowhorizon.hollowengine.common.ui.net.showOverlay
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * What [ChoiceOverlay] writes into its UI session and reads back. An overlay a `.ui.kts` declares with
 * [ChoiceOverlay.ID] replaces the built-in one and speaks the same keys.
 */
object ChoiceUiKeys {
    val Choice = dataKey<ChoiceView>("choice") { ChoiceView() }

    /** `send { putString(Action, Vote); putInt(Index, index) }` votes for the option at that index. */
    const val Action = "action"
    const val Vote = "vote"
    const val Index = "index"
}

/**
 * The open poll as one of its players sees it.
 *
 * @param options the options as story markup.
 * @param myVote what this player voted for, or -1.
 * @param votes how many players have voted, of [voters].
 * @param decided the chosen option once the poll is over, or -1.
 */
@Serializable
data class ChoiceView(
    val options: List<String> = emptyList(),
    val myVote: Int = -1,
    val votes: Int = 0,
    val voters: Int = 0,
    val decided: Int = -1,
)

/** Shows a poll in its own HUD window beside the crosshair, the default of [ChoiceEvent]. */
object ChoiceOverlay : PollProvider {
    val ID: ResourceLocation = "hollowengine-narrative:choice".rl

    private val sessions = HashMap<Int, List<UiSession>>()

    override fun show(poll: Poll) {
        sessions[poll.id] = poll.players.map { player ->
            player.showOverlay(ID) {
                put(ChoiceUiKeys.Choice, view(poll, player))
                onEvent { payload ->
                    if (payload.getString(ChoiceUiKeys.Action) == ChoiceUiKeys.Vote) {
                        poll.vote(player, payload.getInt(ChoiceUiKeys.Index))
                    }
                }
            }
        }
    }

    override fun update(poll: Poll) {
        sessions[poll.id]?.forEach { it.put(ChoiceUiKeys.Choice, view(poll, it.player)) }
    }

    /** Marks the chosen option and lets the window play itself out. */
    override fun close(poll: Poll, chosen: Int) {
        sessions.remove(poll.id)?.forEach { session ->
            session.put(ChoiceUiKeys.Choice, view(poll, session.player).copy(decided = chosen))
            session.close()
        }
    }

    private fun view(poll: Poll, player: ServerPlayer) = ChoiceView(
        options = poll.options.map { it.text },
        myVote = poll.votes[player] ?: -1,
        votes = poll.votes.size,
        voters = poll.players.size,
    )
}
