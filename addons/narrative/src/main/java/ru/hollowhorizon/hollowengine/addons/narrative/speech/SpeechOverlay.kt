package ru.hollowhorizon.hollowengine.addons.narrative.speech

import kotlinx.serialization.Serializable
import net.minecraft.Util
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.poll.Polls
import ru.hollowhorizon.hollowengine.common.data.dataKey
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.ui.net.UiSession
import ru.hollowhorizon.hollowengine.common.ui.net.showOverlay
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [SpeechOverlay] writes into its UI session. An overlay a `.ui.kts` declares with [SpeechOverlay.ID] replaces
 * the built-in one and reads the same keys.
 */
object SpeechUiKeys {
    val Line = dataKey<SpeechLineView>("line") { SpeechLineView() }

    /** `send { putString(Action, Advance) }` moves past the line, as `/hollowengine dialogue advance` does. */
    const val Action = "action"
    const val Advance = "advance"
}

/**
 * The line on screen. The text arrives whole and the client types it out, so the animation stays off the network.
 *
 * @param id bumped for every line; a change restarts the typing.
 * @param speaker the name shown above the text, empty for narration.
 * @param text story markup.
 * @param visibleLength the characters of [text] without its markup.
 * @param charMillis milliseconds the typing spends on a character, 0 to show the line at once.
 * @param readMillis how long the line stays before the story moves on.
 */
@Serializable
data class SpeechLineView(
    val id: Int = 0,
    val speaker: String = "",
    val text: String = "",
    val visibleLength: Int = 0,
    val charMillis: Int = 0,
    val readMillis: Long = 0,
)

/**
 * Shows lines in a HUD overlay above the hotbar, the default of [SayEvent]. A line moves on once it is read; the
 * overlay stays a while after the last one and fades out unless another line comes or a choice is open.
 */
object SpeechOverlay : LineProvider {
    val ID: ResourceLocation = "hollowengine-narrative:speech".rl

    private class Shown(val session: UiSession, var line: Int) {
        var hideAt = Long.MAX_VALUE
    }

    private val shown = HashMap<UUID, Shown>()
    private var nextId = 0

    override suspend fun show(line: Line) {
        if (line.text.plainText.isBlank()) return
        val id = ++nextId
        val length = line.text.visibleLength
        val read = maxOf(1500L, length * 50L)
        val view = SpeechLineView(id, line.speaker?.name.orEmpty(), line.markup, length, 18, read)
        line.audience.forEach { player ->
            val overlay = shown[player.uuid] ?: open(player)
            overlay.session.put(SpeechUiKeys.Line, view)
            overlay.line = id
            overlay.hideAt = Long.MAX_VALUE
        }
        try {
            Reading.awaitAdvance(line.audience, read.milliseconds)
        } finally {
            val hideAt = Util.getMillis() + 2500L
            line.audience.forEach { player -> shown[player.uuid]?.takeIf { it.line == id }?.hideAt = hideAt }
        }
    }

    private fun open(player: ServerPlayer): Shown {
        lateinit var overlay: Shown
        val session = player.showOverlay(ID) {
            onEvent { payload -> if (payload.getString(SpeechUiKeys.Action) == SpeechUiKeys.Advance) Reading.advance(player) }
            onClose { shown.remove(player.uuid, overlay) }
        }
        overlay = Shown(session, 0)
        shown[player.uuid] = overlay
        return overlay
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.Server) {
        if (shown.isEmpty()) return
        val now = Util.getMillis()
        val expired = shown.values.filter { now >= it.hideAt && Polls.of(it.session.player) == null }
        expired.forEach { overlay ->
            shown.remove(overlay.session.player.uuid, overlay)
            overlay.session.close()
        }
    }
}
