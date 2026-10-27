package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A scene being played, as commands and event listeners see it: who takes part, its variables and characters. */
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

/** How a scene ended. */
sealed interface SceneResult {
    /** The story ran to its end (or `@return` popped the last frame). */
    data object Finished : SceneResult

    /** Every participant left; the checkpoint is kept so the script may resume later. */
    data object AllPlayersLeft : SceneResult

    /** The scope was cancelled (server stopping). The checkpoint is kept. */
    data object Cancelled : SceneResult

    /** A statement failed; [error] is the cause, and the checkpoint is kept for inspection. */
    data class Failed(val error: Throwable) : SceneResult
}
