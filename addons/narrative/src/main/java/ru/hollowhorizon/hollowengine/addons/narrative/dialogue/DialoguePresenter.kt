package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import net.minecraft.server.level.ServerPlayer

/**
 * How a dialogue reaches players. The engine drives it one fragment at a time, so a presenter never
 * has to understand the story language: inline commands and `[-]` pauses are already split out by the
 * time [appendText] is called.
 *
 * Implementations decide how a line is advanced, a click, a delay, a button - and how the UI looks;
 * the default ships as a scripted overlay, so re-skinning needs no engine code.
 */
interface DialoguePresenter {
    /** Opens the dialogue UI. Runs before the first line, and again after resume. */
    suspend fun onDialogueStart(session: DialogueSession) {}

    /** Closes the dialogue UI. Runs on every ending, including cancellation. */
    suspend fun onDialogueEnd(session: DialogueSession, result: DialogueResult) {}

    /** Starts a new line; [speaker] is null for narrator text. */
    suspend fun beginLine(session: DialogueSession, speaker: DialogueCharacter?)

    /** Appends the next fragment and returns once it has been shown (typed out, if animated). */
    suspend fun appendText(session: DialogueSession, text: String)

    /** `[-]`: pauses inside a line until the participants confirm. */
    suspend fun waitForInput(session: DialogueSession)

    /** Ends the line and returns once the participants have advanced past it. */
    suspend fun endLine(session: DialogueSession)

    /** Shows every option of a menu at once and returns immediately; votes arrive via the session. */
    suspend fun showChoices(session: DialogueSession, options: List<PresentedChoice>)

    /**
     * A participant voted. Called for every vote, not just the deciding one, so a UI can show what
     * is already settled. Unlike the rest of the presenter this runs wherever the vote arrived,
     * outside the story coroutine, and so cannot suspend.
     */
    fun onVote(session: DialogueSession, votes: Map<ServerPlayer, Int>) {}

    /**
     * Hides the menu. [chosen] is the winning option, or -1 when the menu ended without a decision
     * (an `@sync` track took over, or the dialogue was cancelled).
     */
    suspend fun hideChoices(session: DialogueSession, chosen: Int)
}

/** One button of a menu as the presenter sees it. */
data class PresentedChoice(
    /** Index inside the menu, what [DialogueSession.choose] expects. */
    val index: Int,
    val id: String?,
    val text: String,
    /** Named parameters written on `@choice` that the signature did not claim (icons, colors, etc.). */
    val metadata: Map<String, StoryValue>,
)

/** How a dialogue ended. */
sealed interface DialogueResult {
    /** The story ran to its end (or `@return` popped the last frame). */
    data object Finished : DialogueResult

    /** Every participant left; the checkpoint is kept so the script may resume later. */
    data object AllPlayersLeft : DialogueResult

    /** The scope was cancelled (server stopping). The checkpoint is kept. */
    data object Cancelled : DialogueResult

    /** A statement failed; [error] is the cause, and the checkpoint is kept for inspection. */
    data class Failed(val error: Throwable) : DialogueResult
}
