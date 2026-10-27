package ru.hollowhorizon.hollowengine.addons.narrative.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.server.level.ServerPlayer
import kotlin.time.Duration

/** The players reading a line, and the click on it that moves them on: `/hollowengine dialogue advance`. */
object Reading {
    private val waiting = HashMap<ServerPlayer, CompletableDeferred<Unit>>()

    /**
     * Waits until one of [players] moves on, or [timeout] passes when it is not null; returns whether someone moved
     * on. A later wait of the same player takes the click from an earlier one.
     */
    suspend fun awaitAdvance(players: List<ServerPlayer>, timeout: Duration? = null): Boolean {
        val signal = CompletableDeferred<Unit>()
        players.forEach { waiting[it] = signal }
        try {
            return if (timeout == null) {
                signal.await()
                true
            } else withTimeoutOrNull(timeout) { signal.await() } != null
        } finally {
            players.forEach { player -> if (waiting[player] === signal) waiting.remove(player) }
        }
    }

    /** Moves [player] on; false when they read nothing right now. */
    fun advance(player: ServerPlayer): Boolean = waiting.remove(player)?.complete(Unit) == true
}
