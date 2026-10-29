package ru.hollowhorizon.hollowengine.addons.narrative.poll

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.StoryValue
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import kotlin.time.Duration

/**
 * One option of a poll. [key] tells it apart from the others, [text] is markup, and [metadata] holds what a story
 * wrote beside it, such as `icon=`.
 */
class PollOption(val key: String, val text: String, val metadata: Map<String, StoryValue> = emptyMap())

/** A vote of [players] between [options], shown by its [PollProvider]. */
class Poll internal constructor(
    val id: Int,
    val players: List<ServerPlayer>,
    val options: List<PollOption>,
    internal val provider: PollProvider,
) {
    private val cast = LinkedHashMap<ServerPlayer, Int>()
    private val decided = CompletableDeferred<Int>()

    /** What each player voted for so far. */
    val votes: Map<ServerPlayer, Int> get() = cast

    /**
     * [player] votes for the option at [index]; false when they take no part, there is no such option or the poll is
     * decided. It is decided once every player still online has voted.
     */
    fun vote(player: ServerPlayer, index: Int): Boolean {
        if (player !in players || index !in options.indices || decided.isCompleted) return false
        cast[player] = index
        provider.update(this)
        if (cast.keys.containsAll(players.filter { !it.hasDisconnected() })) decided.complete(winner())
        return true
    }

    /** Decides the poll for everyone, ignoring the votes: a script answers for the players, a test drives a scene. */
    fun decide(index: Int) {
        decided.complete(index.coerceIn(options.indices))
    }

    /** Waits for the decision, or for [timeout] and then counts the votes cast by then. */
    internal suspend fun await(timeout: Duration?): Int =
        if (timeout == null) decided.await() else withTimeoutOrNull(timeout) { decided.await() } ?: winner()

    /** The option with the most votes, ties broken at random and the first one when nobody voted. */
    private fun winner(): Int {
        if (cast.isEmpty()) return 0
        val tally = IntArray(options.size)
        cast.values.forEach { tally[it]++ }
        val best = tally.max()
        return tally.indices.filter { tally[it] == best }.random()
    }
}

/** Shows a poll to its players: the options, the votes as they come and how it ended. */
interface PollProvider {
    fun show(poll: Poll)

    /** A player voted; called for every vote, not just the deciding one. */
    fun update(poll: Poll) {}

    /** The poll ended with the option at [chosen], or -1 when it was given up. */
    fun close(poll: Poll, chosen: Int) {}
}

/**
 * Players are about to choose between [options]. A listener may add, remove or change options, or take the showing
 * over with [sendPoll]: the poll is then shown by that provider instead of [ChoiceOverlay]; [ChatPollProvider] shows it
 * in chat.
 */
class ChoiceEvent(val players: List<ServerPlayer>, val options: MutableList<PollOption>) : Event {
    var provider: PollProvider = ChoiceOverlay
        private set

    fun sendPoll(provider: PollProvider) {
        this.provider = provider
    }

    companion object : EventHandler<ChoiceEvent>()
}

/** The open polls, which `/hollowengine dialogue choose` votes in. */
object Polls {
    private var nextId = 0
    private val open = ArrayList<Poll>()

    /**
     * Asks [players] to choose one of [options], see [ChoiceEvent], and returns the chosen option; null when no option
     * is left to choose. With [timeout] the votes cast by then decide.
     */
    suspend fun ask(players: List<ServerPlayer>, options: List<PollOption>, timeout: Duration? = null): PollOption? {
        val event = ChoiceEvent.post(ChoiceEvent(players, options.toMutableList()))
        if (event.options.isEmpty()) return null
        val poll = Poll(nextId++, players, event.options.toList(), event.provider)
        open += poll
        var chosen = -1
        try {
            poll.provider.show(poll)
            chosen = poll.await(timeout)
        } finally {
            open -= poll
            poll.provider.close(poll, chosen)
        }
        return poll.options[chosen]
    }

    /** The poll [player] votes in: the latest open one they take part in. */
    fun of(player: ServerPlayer): Poll? = open.lastOrNull { player in it.players }
}
