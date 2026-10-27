package ru.hollowhorizon.hollowengine.addons.narrative.speech

import kotlinx.coroutines.currentCoroutineContext
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueCharacter
import ru.hollowhorizon.hollowengine.addons.narrative.scene.EntityCharacter
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextDocument
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextParser
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** How far a line said outside a scene carries: the players this close to its speaker hear it. */
private const val HEARING_DISTANCE = 24.0

/**
 * A line [speaker] says to [audience], narration when [speaker] is null. [markup] is the text as written, with the
 * tags of [FormattedTextParser].
 */
class Line(val speaker: DialogueCharacter?, val markup: String, val audience: List<ServerPlayer>) {
    val text: FormattedTextDocument by lazy(LazyThreadSafetyMode.NONE) { FormattedTextParser.parse(markup) }
}

/** Shows a line and returns once its audience has read it. */
fun interface LineProvider {
    suspend fun show(line: Line)
}

/**
 * A line is about to be shown. A listener may change [line], or take the showing over with [sendLine]: the line is
 * then shown by that provider instead of in chat.
 */
class SayEvent(var line: Line) : Event {
    var provider: LineProvider = ChatLineProvider
        private set

    fun sendLine(provider: LineProvider) {
        this.provider = provider
    }

    companion object : EventHandler<SayEvent>()
}

/** Who hears the lines and votes in the choices of what runs inside it, as a scene sets for its participants. */
class StoryAudience(val players: List<ServerPlayer>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StoryAudience>
}

/**
 * Says [text] and returns once it is read. It is heard by [to], or else by the audience of the scene it runs in, or
 * else by the players near [speaker].
 */
@EditorCategory("hollowengine.gui.ide.story.category.dialogue")
@SimpleAction("say")
suspend fun say(speaker: DialogueCharacter?, text: String, to: List<ServerPlayer>? = null) {
    val audience = (to ?: audience() ?: speaker.listeners()).filter { !it.hasDisconnected() }
    val event = SayEvent.post(SayEvent(Line(speaker, text, audience)))
    event.provider.show(event.line)
}

/** [text] said by [speaker], see [say]. */
suspend fun say(speaker: LivingEntity, text: String, to: List<ServerPlayer>? = null) =
    say(DialogueCharacter.of(speaker), text, to)

/** The players of the scene the caller runs in, or null outside one. */
internal suspend fun audience(): List<ServerPlayer>? = currentCoroutineContext()[StoryAudience]?.players

private fun DialogueCharacter?.listeners(): List<ServerPlayer> {
    val entity = (this as? EntityCharacter)?.entity
        ?: throw StoryException("A line of ${this?.name ?: "the narrator"} outside a scene needs to = players")
    return entity.level().players().filterIsInstance<ServerPlayer>()
        .filter { it.distanceToSqr(entity) <= HEARING_DISTANCE * HEARING_DISTANCE }
}
