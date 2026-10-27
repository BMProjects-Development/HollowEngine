package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CompletableDeferred
import net.minecraft.server.level.ServerPlayer

/**
 * What the participants answer a scene with: advancing past a line, and their votes in the open menu.
 */
internal class SceneAnswers(private val session: SceneSession, private val presentation: DialoguePresenter) {
    /** The menu on screen: the votes cast so far and the signal that it is decided. */
    class Menu(val optionCount: Int) {
        val votes = LinkedHashMap<ServerPlayer, Int>()
        val signal = CompletableDeferred<Unit>()

        /** Set when a script decides for everyone; skips the vote entirely. */
        var forced: Int? = null
    }

    private var advanceSignal: CompletableDeferred<Unit>? = null

    /** An advance that arrived while nobody was waiting; consumed by the next wait. */
    private var pendingAdvance = false

    private var menu: Menu? = null

    val openMenuSize: Int get() = menu?.optionCount ?: 0

    /** Advances on everyone's behalf. */
    fun advance() {
        val waiting = advanceSignal
        if (waiting == null) pendingAdvance = true else waiting.complete(Unit)
    }

    suspend fun awaitAdvance() {
        if (pendingAdvance) {
            pendingAdvance = false
            return
        }
        val signal = CompletableDeferred<Unit>()
        advanceSignal = signal
        try {
            signal.await()
        } finally {
            advanceSignal = null
            pendingAdvance = false
        }
    }

    fun open(optionCount: Int): Menu = Menu(optionCount).also { menu = it }

    fun close() {
        menu = null
    }

    /** Records the vote of [player]; the menu is decided once every participant online has voted. */
    fun choose(player: ServerPlayer, index: Int) {
        val menu = menu ?: return
        if (index !in 0 until menu.optionCount) return
        menu.votes[player] = index
        presentation.onVote(session, menu.votes.toMap())
        if (menu.votes.keys.containsAll(session.onlineParticipants)) menu.signal.complete(Unit)
    }

    /** Decides the open menu for everyone, ignoring votes: a script answers for the players, a test drives a scene. */
    fun forceChoice(index: Int) {
        val menu = menu ?: return
        menu.forced = index
        menu.signal.complete(Unit)
    }

    /** The option [menu] went to: a forced one, or the most votes, ties broken at random and the first without votes. */
    fun winner(menu: Menu): Int {
        menu.forced?.let { return it.coerceIn(0, menu.optionCount - 1) }
        if (menu.votes.isEmpty()) return 0
        val tally = IntArray(menu.optionCount)
        menu.votes.values.forEach { if (it in 0 until menu.optionCount) tally[it]++ }
        val best = tally.max()
        val leaders = tally.indices.filter { tally[it] == best }
        return if (leaders.size == 1) leaders.first() else leaders.random()
    }
}
