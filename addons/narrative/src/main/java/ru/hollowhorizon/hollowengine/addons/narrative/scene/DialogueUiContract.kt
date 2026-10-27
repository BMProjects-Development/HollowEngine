package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueUiKeys.ChooseAction
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueUiKeys.Index
import ru.hollowhorizon.hollowengine.common.data.dataKey

/**
 * What [UiDialoguePresentation] writes into the UI session, and what the screen reads back.
 *
 * It is a public contract on purpose: the built-in screen is only the default, and a `.ui.kts`
 * script that declares the same id (or any other id passed to the presenter) replaces it while
 * still being driven by these keys. Everything the screen needs to draw a frame is in here, so a
 * custom UI never has to reach into the engine.
 */
object DialogueUiKeys {
    /** The line being read. */
    val Line = dataKey<DialogueLineView>("line") { DialogueLineView() }

    /** The open menu; [DialogueChoicesView.options] is empty while no menu is up. */
    val Choices = dataKey<DialogueChoicesView>("choices") { DialogueChoicesView() }

    /** Where the dialogue is as a whole; see [DialoguePhase]. */
    val Phase = dataKey<String>("phase") { DialoguePhase.LINE }

    /** `send { putString(Action, ...) }`, what a screen sends back. */
    const val Action = "action"

    /** Index of the option a player voted for, alongside [ChooseAction]. */
    const val Index = "index"

    /** Moves past the current line. Ignored unless the dialogue is waiting for it. */
    const val AdvanceAction = "advance"

    /** Votes for the option at [Index]. */
    const val ChooseAction = "choose"
}

/** Values of [DialogueUiKeys.Phase]. */
object DialoguePhase {
    /** A line is being typed out or waiting to be advanced. */
    const val LINE = "line"

    /** A menu is open. */
    const val CHOICES = "choices"

    /** The dialogue is over; the screen plays its exit animation and the server closes it. */
    const val CLOSING = "closing"
}

/**
 * The line on screen. The text arrives whole and the client types it out at [charDelay], which
 * keeps the animation off the network while staying in step with the server: the story waits
 * exactly as long as the typing takes.
 */
@Serializable
data class DialogueLineView(
    /** Bumped for every new line; a change is what resets the typewriter. */
    val id: Int = 0,
    /** Empty for narration, which hides the name plate. */
    val speaker: String = "",
    /**
     * The speaker's entity as this client knows it, or -1 when the speaker is a plain name. An
     * entity cannot travel, so what travels is its id and the client draws the one it already has,
     * live equipment, pose and all. See `UiEntityRef`.
     */
    val speakerEntity: Int = -1,
    val text: String = "",
    /** Number of rendered Unicode characters in [text], excluding formatting markup. */
    val visibleLength: Int = 0,
    /** Milliseconds per character; 0 shows the whole line at once. */
    val charDelay: Int = 0,
    /** Bumped when a reader asked to see the rest of the line immediately. */
    val skips: Int = 0,
    /** Whether the line is finished and the dialogue is waiting for a click. */
    val awaiting: Boolean = false,
)

/** The open menu, as one participant sees it. */
@Serializable
data class DialogueChoicesView(
    val options: List<DialogueChoiceView> = emptyList(),
    /** What this player voted for, or -1. */
    val myVote: Int = -1,
    /** How many participants have voted, and how many are expected; shown while waiting. */
    val votes: Int = 0,
    val voters: Int = 0,
    /** The winning option once the vote is settled, or -1. Drives the selection animation. */
    val decided: Int = -1,
)

/** One button of the menu. */
@Serializable
data class DialogueChoiceView(
    val index: Int = 0,
    val text: String = "",
    /**
     * Texture drawn to the left of the button, from the `icon=` parameter of `@choice`. Empty
     * leaves the slot out entirely.
     */
    val icon: String = "",
)
