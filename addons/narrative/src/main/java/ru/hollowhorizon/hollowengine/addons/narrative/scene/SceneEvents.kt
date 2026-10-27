package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/** Scene events on the engine's event bus, so an addon or a script can react to any scene that plays. */
open class SceneEvent(val session: SceneSession) : Event {
    /** A scene began playing. Fires on every start, including a resume. */
    class Started(session: SceneSession) : SceneEvent(session) {
        companion object : EventHandler<Started>()
    }

    /** A scene stopped, for any reason. */
    class Ended(session: SceneSession, val result: SceneResult) : SceneEvent(session) {
        companion object : EventHandler<Ended>()
    }

    /**
     * A menu was decided. [text] is the option as written in the story that is playing; a translated story is a
     * file of its own, so only [id] stays the same across locales.
     */
    class Chosen(
        session: SceneSession,
        val index: Int,
        val id: String?,
        val text: String,
        val metadata: Map<String, StoryValue>,
    ) : SceneEvent(session) {
        /** Matches [id] when the story declared one, otherwise the text as written. */
        val key: String get() = id ?: text

        companion object : EventHandler<Chosen>()
    }

    /** A `tag=`-marked command began. */
    class ActionStarted(
        session: SceneSession,
        val tag: String,
        val function: String,
        val args: StoryArguments,
    ) : SceneEvent(session) {
        companion object : EventHandler<ActionStarted>()
    }

    /** A `tag=`-marked command finished, was cancelled or failed. */
    class ActionEnded(
        session: SceneSession,
        val tag: String,
        val function: String,
        val args: StoryArguments,
        val reason: StoryActionEnd,
    ) : SceneEvent(session) {
        companion object : EventHandler<ActionEnded>()
    }
}

/** Why a tagged command finished; an overlay usually hides itself either way. */
enum class StoryActionEnd { COMPLETED, CANCELLED, FAILED }
