package ru.hollowhorizon.hollowengine.addons.narrative.client

import androidx.compose.runtime.*
import net.minecraft.client.gui.screens.ChatScreen
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.poll.ChoiceOverlay
import ru.hollowhorizon.hollowengine.addons.narrative.poll.ChoiceUiKeys
import ru.hollowhorizon.hollowengine.addons.narrative.poll.ChoiceView
import ru.hollowhorizon.hollowengine.addons.narrative.speech.SpeechOverlay
import ru.hollowhorizon.hollowengine.addons.narrative.speech.SpeechUiKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.ScreenEvent
import ru.hollowhorizon.hollowengine.common.ui.*
import ru.hollowhorizon.hollowengine.common.ui.hud.VanillaHudLayers

@ClientOnly
internal object DialogueOverlays {
    const val STYLESHEET = "hollowengine-narrative:ui/styles/dialogue-overlay.hss"
    var speechBounds by mutableStateOf<UiRect?>(null)

    var screenOpen by mutableStateOf(false)
        private set

    @SubscribeEvent
    fun onScreenOpen(event: ScreenEvent.Open) {
        screenOpen = true
    }

    @SubscribeEvent
    fun onScreenClose(event: ScreenEvent.Close) {
        screenOpen = false
    }

    private val anchors = listOf(
        OverlayAnchor.HudLayer(VanillaHudLayers.CHAT, HudPlacement.AFTER),
        OverlayAnchor.OnScreen(ScreenMatch.OfType(ChatScreen::class.java.name, subclasses = true), ScreenLevel.CONTENT),
    )

    val speech = UiOverlayDefinition(
        id = SpeechOverlay.ID,
        anchors = anchors,
        autoShow = false,
        input = OverlayInput.INTERACTIVE,
        rebuildEveryFrame = false,
        exitDuration = 520L,
        content = { Speech() },
    )

    val choice = UiOverlayDefinition(
        id = ChoiceOverlay.ID,
        anchors = anchors,
        autoShow = false,
        input = OverlayInput.INTERACTIVE,
        rebuildEveryFrame = false,
        exitDuration = 520L,
        content = { Choice() },
    )
}

@Composable
private fun UiScope.Speech() {
    val line = data[SpeechUiKeys.Line]
    var shown by remember(line.id) { mutableStateOf(if (line.charMillis <= 0) line.visibleLength else 0) }
    val typed = shown >= line.visibleLength
    LaunchedEffect(line.id) {
        var start = -1L
        while (shown < line.visibleLength) {
            withFrameNanos { now ->
                if (start < 0L) start = now
                val typing = ((now - start) / 1_000_000L / line.charMillis).toInt()
                shown = typing.coerceIn(shown, line.visibleLength)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { DialogueOverlays.speechBounds = null } }

    fun skip() {
        if (typed) send { putString(SpeechUiKeys.Action, SpeechUiKeys.Advance) } else shown = line.visibleLength
    }

    val screenOpen = DialogueOverlays.screenOpen
    var root = Modifier.style(DialogueOverlays.STYLESHEET).inputTransparent().focusScope()
    if (!screenOpen) {
        root = root.onKeyInput { input ->
            if (line.text.isEmpty() || !isSkipKey(input)) return@onKeyInput
            skip()
            input.consume()
        }
    }
    var box = Modifier.onPlaced { DialogueOverlays.speechBounds = it }
    if (screenOpen) box = box.input(hoverable = true, clickable = true).onClick { skip() }

    Box(id = "speech-root", modifier = root) {
        if (line.text.isEmpty()) return@Box
        Column(
            id = "speech",
            tags = if (line.speaker.isEmpty()) listOf("narration") else emptyList(),
            modifier = box,
        ) {
            if (line.speaker.isNotEmpty()) Text(line.speaker, id = "speech-name")
            Text(id = "speech-text") {
                FormattedTextSpans(line.text, shown, listOf("speech-pending"))
                if (typed) Span(" »", id = "speech-next")
            }
        }
    }
}

private fun isSkipKey(input: UiKeyInput) = input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER

@Composable
private fun UiScope.Choice() {
    val choice = data[ChoiceUiKeys.Choice]
    val count = choice.options.size
    var highlighted by remember { mutableStateOf(0) }
    val open = choice.decided < 0

    fun vote(index: Int) {
        if (!open || index !in 0 until count) return
        highlighted = index
        send {
            putString(ChoiceUiKeys.Action, ChoiceUiKeys.Vote)
            putInt(ChoiceUiKeys.Index, index)
        }
    }

    var root = Modifier.style(DialogueOverlays.STYLESHEET).focusScope()
    if (!DialogueOverlays.screenOpen) {
        root = root.input(clickable = true).onClick { vote(highlighted) }.onScroll { event ->
                if (count > 0 && event.scrollY != 0f) highlighted =
                    (highlighted + if (event.scrollY > 0f) -1 else 1).mod(count)
                event.consume()
            }.onKeyInput { input ->
                if (choiceKey(input, count, highlighted, ::vote) {
                        highlighted = it
                    }) input.consume()
            }
    }

    var size by remember { mutableStateOf<UiRect?>(null) }
    val speech = DialogueOverlays.speechBounds
    val own = size
    var modifier = Modifier.onPlaced { size = it }
    if (speech != null && own != null) {
        modifier = modifier.position((speech.x + speech.width - own.width).px, (speech.y - 3f - own.height).px)
    }

    Box(id = "choice-root", modifier = root) {
        Column(id = "choice", tags = if (speech != null) listOf("docked") else emptyList(), modifier = modifier) {
            choice.options.forEachIndexed { index, text -> Option(index, text, choice, highlighted) { vote(index) } }
            if (choice.voters > 1) Text("${choice.votes}/${choice.voters}", id = "choice-votes")
        }
    }
}

@Composable
private fun Option(index: Int, text: String, choice: ChoiceView, highlighted: Int, vote: () -> Unit) {
    val states = buildList {
        if (index == highlighted && choice.decided < 0) add("highlighted")
        if (index == choice.myVote) add("voted")
        if (index == choice.decided) add("won")
        if (choice.decided >= 0 && index != choice.decided) add("lost")
    }
    var modifier = Modifier.state(*states.toTypedArray())
    if (choice.decided < 0) modifier = modifier.input(hoverable = true, clickable = true).onClick { vote() }
    Row(id = "choice-option-$index", tags = listOf("choice-option"), modifier = modifier) {
        Text("${index + 1}", tags = listOf("choice-number"))
        FormattedText(text, tags = listOf("choice-text"))
    }
}

private fun choiceKey(
    input: UiKeyInput,
    count: Int,
    highlighted: Int,
    vote: (Int) -> Unit,
    move: (Int) -> Unit,
): Boolean {
    if (count == 0) return false
    val number = when (input.key) {
        in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 -> input.key - GLFW.GLFW_KEY_1
        in GLFW.GLFW_KEY_KP_1..GLFW.GLFW_KEY_KP_9 -> input.key - GLFW.GLFW_KEY_KP_1
        else -> null
    }
    return when {
        number != null -> (number < count).also { if (it) vote(number) }
        input.key == GLFW.GLFW_KEY_UP -> true.also { move((highlighted - 1).mod(count)) }
        input.key == GLFW.GLFW_KEY_DOWN -> true.also { move((highlighted + 1).mod(count)) }
        input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER -> true.also { vote(highlighted) }
        else -> false
    }
}
