package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The read-and-input side of a running dialogue, as presenters and story functions see it.
 * The implementation is [DialogueController], which is the session itself, there is no separate
 * session object to keep track of.
 */
interface DialogueSession {
    val address: String
    val server: MinecraftServer

    /** Participants fixed when the dialogue started; offline players stay in the list. */
    val participants: List<ServerPlayer>

    /** Participants that are currently online, the ones a vote actually waits for. */
    val onlineParticipants: List<ServerPlayer>

    /** Story variables. Writing here from Kotlin is allowed and lands in the next checkpoint. */
    val variables: MutableMap<String, StoryValue>

    /** Characters bound by name. */
    val characters: Map<String, DialogueCharacter>

    /** Advances past the current line (a click, a button, whatever the presenter uses). */
    fun advance(player: ServerPlayer)

    /** Records [player]'s vote for the menu option at [index]. */
    fun choose(player: ServerPlayer, index: Int)

    /** Cancels a running async track by name. */
    fun cancelTrack(name: String)
}

/**
 * The dialogue a command runs in. A command that borrows the screen of the players, a fade or the camera, reads it to
 * hand the screen back when the dialogue ends.
 */
class CurrentDialogue(val session: DialogueSession) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CurrentDialogue>
}

/** How a menu decides between several participants. */
data class VotePolicy(
    /** Milliseconds to wait for the remaining voters; null waits indefinitely. */
    val timeoutMillis: Long? = null,
    /** Whether players who have not voted when the timeout hits count as abstaining. */
    val abstainOnTimeout: Boolean = true,
)
