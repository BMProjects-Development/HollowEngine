package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/**
 * Dialogue events on the engine's event bus, so an addon can react to any dialogue without holding
 * its controller. The per-controller handlers (`controller.onChoice { }` and friends) are still the
 * way a script reacts to *its own* dialogue; these fire for every dialogue in the game.
 */
open class DialogueEvent(val session: DialogueSession) : Event {
    /** A dialogue began playing. Fires on every `start`, including a resume. */
    class Started(session: DialogueSession) : DialogueEvent(session) {
        companion object : EventHandler<Started>()
    }

    /** A dialogue stopped, for any reason. */
    class Ended(session: DialogueSession, val result: DialogueResult) : DialogueEvent(session) {
        companion object : EventHandler<Ended>()
    }

    /** A menu was decided. */
    class Chosen(
        session: DialogueSession,
        val index: Int,
        val id: String?,
        val text: String,
        val metadata: Map<String, StoryValue>,
        val votes: Map<ServerPlayer, Int>,
    ) : DialogueEvent(session) {
        /** Matches [id] when the story declared one, otherwise the text as written. */
        val key: String get() = id ?: text

        companion object : EventHandler<Chosen>()
    }

    /** A `tag=`-marked action began. */
    class ActionStarted(
        session: DialogueSession,
        val tag: String,
        val function: String,
        val args: StoryArguments,
    ) : DialogueEvent(session) {
        companion object : EventHandler<ActionStarted>()
    }

    /** A `tag=`-marked action finished, was cancelled or failed. */
    class ActionEnded(
        session: DialogueSession,
        val tag: String,
        val function: String,
        val args: StoryArguments,
        val reason: StoryActionEnd,
    ) : DialogueEvent(session) {
        companion object : EventHandler<ActionEnded>()
    }
}

/**
 * A tagged action starting or finishing, as the controller's own handlers see it.
 */
class StoryActionEvent(
    val session: DialogueSession,
    val tag: String,
    /** Name of the function that carried the tag, e.g. `wait` or `play-video`. */
    val function: String,
    /** Its arguments, including named parameters no signature declared. */
    val args: StoryArguments,
)

/** Why a tagged action finished, an overlay usually hides itself either way. */
enum class StoryActionEnd { COMPLETED, CANCELLED, FAILED }

class StoryActionEndEvent(
    val session: DialogueSession,
    val tag: String,
    val function: String,
    val args: StoryArguments,
    val reason: StoryActionEnd,
)

/** A menu decision, after the vote has been resolved. */
class StoryChoiceEvent(
    val session: DialogueSession,
    val index: Int,
    val id: String?,
    /**
     * The option's text as written in the story that is playing. Since a translated story is a file
     * of its own, only [id] is stable across locales, give a choice an `id=` if Kotlin matches on it.
     */
    val text: String,
    val metadata: Map<String, StoryValue>,
    /** What each participant voted for; empty for a single-player dialogue that just clicked. */
    val votes: Map<ServerPlayer, Int>,
) {
    /** Matches [id] when the story declared one, otherwise the text. */
    val key: String get() = id ?: text
}
