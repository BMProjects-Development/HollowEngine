package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.data.write
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextParser
import ru.hollowhorizon.hollowengine.common.ui.net.UiSession
import ru.hollowhorizon.hollowengine.common.ui.net.UiSessionManager
import ru.hollowhorizon.hollowengine.common.ui.net.UiSurfaceKind
import kotlin.time.Duration.Companion.milliseconds

/**
 * Plays the dialogue in a scripted UI screen: the visual-novel presentation.
 */
class UiDialoguePresentation(
    /** Screen to open; the engine's own is the default and any `.ui.kts` screen can take its place. */
    val screen: ResourceLocation = DEFAULT_SCREEN,
    /**
     * What [screen] declares. A plain `screen { }` is the default; point this at
     * [UiSurfaceKind.ADAPTIVE] for a `surface { }` that reads as an overlay while a line is up and
     * becomes a screen for the menu.
     */
    val kind: UiSurfaceKind = UiSurfaceKind.SCREEN,
    /** Milliseconds per character in word. */
    val charDelay: Int = 25,
    /** How long the chosen option is left on screen so everyone sees what won. */
    val choiceRevealDelay: Long = 520L,
) : DialoguePresenter {
    private val sessions = HashMap<ServerPlayer, UiSession>()

    private var line = DialogueLineView()
    private var choices = DialogueChoicesView()

    private var phase = DialoguePhase.LINE

    override suspend fun onDialogueStart(session: SceneSession) {
        line = DialogueLineView()
        choices = DialogueChoicesView()
        phase = DialoguePhase.LINE
        session.onlineParticipants.forEach { player -> open(session, player) }
    }

    override suspend fun onDialogueEnd(session: SceneSession, result: SceneResult) {
        phase = DialoguePhase.CLOSING
        broadcast { it[DialogueUiKeys.Phase] = phase }
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
    }

    override suspend fun beginLine(session: SceneSession, speaker: DialogueCharacter?) {
        openMissing(session)
        phase = DialoguePhase.LINE
        line = DialogueLineView(
            id = line.id + 1,
            speaker = speaker?.name.orEmpty(),
            speakerEntity = (speaker as? EntityCharacter)?.entity?.id ?: -1,
            charDelay = charDelay,
        )
        broadcast {
            it.edit {
                put(DialogueUiKeys.Phase, phase)
                put(DialogueUiKeys.Line, line)
            }
        }
    }

    override suspend fun appendText(session: SceneSession, text: String) {
        if (text.isEmpty()) return
        val formatted = FormattedTextParser.parse(line.text + text)
        val appendedCharacters = (formatted.visibleLength - line.visibleLength).coerceAtLeast(0)
        line = line.copy(
            text = line.text + text,
            visibleLength = formatted.visibleLength,
            awaiting = false,
        )
        broadcast { it[DialogueUiKeys.Line] = line }
        typeOut(session, appendedCharacters)
    }

    override suspend fun waitForInput(session: SceneSession) = awaitAdvance(session)

    override suspend fun endLine(session: SceneSession) = awaitAdvance(session)

    override suspend fun showChoices(session: SceneSession, options: List<PresentedChoice>) {
        openMissing(session)
        phase = DialoguePhase.CHOICES
        choices = DialogueChoicesView(
            options = options.map { option ->
                DialogueChoiceView(
                    index = option.index,
                    text = option.text,
                    icon = (option.metadata[ICON_PARAMETER] as? StoryString)?.value.orEmpty(),
                )
            },
            voters = session.onlineParticipants.size,
        )
        broadcast {
            it.edit {
                put(DialogueUiKeys.Phase, phase)
                put(DialogueUiKeys.Choices, choices)
            }
        }
    }

    override fun onVote(session: SceneSession, votes: Map<ServerPlayer, Int>) {
        if (choices.options.isEmpty()) return
        choices = choices.copy(votes = votes.size, voters = session.onlineParticipants.size)
        sessions.forEach { (player, ui) ->
            ui[DialogueUiKeys.Choices] = choices.copy(myVote = votes[player] ?: -1)
        }
    }

    override suspend fun hideChoices(session: SceneSession, chosen: Int) {
        if (choices.options.isEmpty()) return
        if (chosen >= 0) {
            choices = choices.copy(decided = chosen)
            sessions.forEach { (_, ui) -> ui[DialogueUiKeys.Choices] = choices }
            delay(choiceRevealDelay.milliseconds)
        }
        choices = DialogueChoicesView()
        broadcast { it[DialogueUiKeys.Choices] = choices }
    }

    private fun openMissing(session: SceneSession) {
        session.onlineParticipants.forEach { player -> if (!sessions.containsKey(player)) open(session, player) }
        sessions.keys.retainAll(session.onlineParticipants.toSet())
    }

    private fun open(session: SceneSession, player: ServerPlayer) {
        val initial = CompoundTag().apply {
            write(DialogueUiKeys.Phase, phase)
            write(DialogueUiKeys.Line, line)
            write(DialogueUiKeys.Choices, choices)
        }
        sessions[player] = UiSessionManager.open(player, screen, kind, initial) {
            onEvent { payload -> handle(session, player, payload) }
            onClose { sessions.remove(player) }
        }
    }

    private fun handle(session: SceneSession, player: ServerPlayer, payload: CompoundTag) {
        when (payload.getString(DialogueUiKeys.Action)) {
            DialogueUiKeys.AdvanceAction -> if (phase == DialoguePhase.LINE) session.advance(player)
            DialogueUiKeys.ChooseAction -> session.choose(player, payload.getInt(DialogueUiKeys.Index))
        }
    }

    private suspend fun typeOut(session: SceneSession, characters: Int) {
        val duration = characters.toLong() * charDelay
        if (duration <= 0L) return
        val skipped = withTimeoutOrNull(duration.milliseconds) { session.awaitAdvance() } != null
        if (skipped) {
            line = line.copy(skips = line.skips + 1)
            broadcast { it[DialogueUiKeys.Line] = line }
        }
    }

    private suspend fun awaitAdvance(session: SceneSession) {
        line = line.copy(awaiting = true)
        broadcast { it[DialogueUiKeys.Line] = line }
        session.awaitAdvance()
    }

    private fun broadcast(block: (UiSession) -> Unit) = sessions.values.forEach(block)

    companion object {
        val DEFAULT_SCREEN: ResourceLocation = ResourceLocation.fromNamespaceAndPath("hollowengine", "dialogue")

        const val ICON_PARAMETER = "icon"
    }
}
