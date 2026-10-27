package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A scene being played, as presenters and commands see it: who takes part, and what they read and answer. */
interface SceneSession {
    val address: String
    val server: MinecraftServer

    /** Participants fixed when the scene started; offline players stay in the list. */
    val participants: List<ServerPlayer>

    /** Participants that are currently online, the ones a vote actually waits for. */
    val onlineParticipants: List<ServerPlayer>

    /** Variables of the scene itself; those of its story are not here. */
    val variables: MutableMap<String, StoryValue>

    /** Characters bound by name. */
    val characters: Map<String, DialogueCharacter>

    /** Options of the menu on screen, or 0 when none is open. */
    val openMenuSize: Int

    /** Advances past the current line (a click, a button, whatever the presenter uses). */
    fun advance(player: ServerPlayer)

    /** Records [player]'s vote for the menu option at [index]. */
    fun choose(player: ServerPlayer, index: Int)

    /** Suspends until a participant advances; presenters call it from [DialoguePresenter.endLine]. */
    suspend fun awaitAdvance()

    /** Cancels a running `@async` track by name. */
    fun cancelTrack(name: String)
}

/**
 * The scene a command runs in. A command that borrows the screen of the players, a fade or the camera, reads it to
 * hand the screen back when the scene ends.
 */
class CurrentScene(val session: SceneSession) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CurrentScene>
}

/** How a menu decides between several participants. */
data class VotePolicy(
    /** Milliseconds to wait for the remaining voters; null waits indefinitely. */
    val timeoutMillis: Long? = null,
    /** Whether players who have not voted when the timeout hits count as abstaining. */
    val abstainOnTimeout: Boolean = true,
)
