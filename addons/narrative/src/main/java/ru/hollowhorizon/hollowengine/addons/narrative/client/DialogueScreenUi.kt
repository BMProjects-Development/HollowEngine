package ru.hollowhorizon.hollowengine.addons.narrative.client

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.rememberScrollState
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.*
import ru.hollowhorizon.hollowengine.common.ui.UiGuiScale
import ru.hollowhorizon.hollowengine.common.ui.UiScope
import ru.hollowhorizon.hollowengine.common.ui.UiScreenDefinition
import ru.hollowhorizon.hollowengine.common.ui.send

/**
 * The dialogue window the engine ships with.
 */
object DialogueScreenUi {
    const val STYLESHEET = "hollowengine-narrative:ui/styles/dialogue.hss"
    const val EXIT_DURATION_MILLIS = 760L

    /** The built-in screen. Scripts declaring the same id take over from it. */
    val definition = UiScreenDefinition(
        id = UiDialoguePresentation.DEFAULT_SCREEN,
        title = "Dialogue",
        closeOnEscape = false,
        pausesGame = false,
        rebuildEveryFrame = false,
        guiScale = UiGuiScale.Auto,
        exitDuration = EXIT_DURATION_MILLIS,
        content = { DialogueScreen() },
    )
}

private const val CURSOR_TEXTURE = "hollowengine-narrative:textures/gui/dialogues/cursor.png"
private const val DESC_TEXTURE = "hollowengine-narrative:textures/gui/dialogues/desc.png"

/** `icon="none"` on a `@choice` drops the icon that otherwise sits left of the button. */
private const val NO_ICON = "none"

/** Beyond this the appearance stagger stops growing, or a long menu would crawl in. */
private const val MAX_STAGGERED_CHOICE = 7

/** `#dialogue-continue:ready`, the line is finished and waiting to be advanced. */
private val READY_STATE = UiState.of("ready")

private val ADVANCE_KEYS = intArrayOf(
    GLFW.GLFW_KEY_ENTER,
    GLFW.GLFW_KEY_KP_ENTER,
    GLFW.GLFW_KEY_SPACE,
    GLFW.GLFW_KEY_TAB,
)

@Composable
private fun UiScope.DialogueScreen() {
    val line = data[DialogueUiKeys.Line]
    val choices = data[DialogueUiKeys.Choices]

    val root = Modifier
        .style(DialogueScreenUi.STYLESHEET)
        .focusScope()
        .input(clickable = true)
        .onClick { advance() }
        .onKeyInput { input ->
            if (input.key in ADVANCE_KEYS) {
                input.consume()
                advance()
            }
        }

    Box(id = "dialogue-root", mode = UiBoxMode.FREE, modifier = root) {
        Box(id = "dialogue-dim")
        if (choices.options.isNotEmpty()) ChoiceList(choices)
        if (line.text.isNotEmpty()) DialogueBox(line, showContinue = choices.options.isEmpty())
    }
}

@Composable
private fun DialogueBox(line: DialogueLineView, showContinue: Boolean) {
    Column(id = "dialogue-box") {
        if (line.speaker.isNotEmpty()) {
            Box(id = "dialogue-name") { Text(line.speaker, tags = listOf("dialogue-name-text")) }
        }
        Box(id = "dialogue-body") {
            TypedText(line)

            val ready = showContinue && line.awaiting
            Image(
                CURSOR_TEXTURE,
                id = "dialogue-continue",
                modifier = if (ready) Modifier.state(READY_STATE) else null,
            )
        }
    }
}

@Composable
private fun TypedText(line: DialogueLineView) {
    val reveal = remember(line.id) { mutableStateOf(0) }
    val textLength = line.visibleLength

    LaunchedEffect(line.id, textLength, line.charDelay) {
        if (line.charDelay <= 0) {
            reveal.value = textLength
            return@LaunchedEffect
        }
        val from = reveal.value
        var startNanos = -1L
        while (reveal.value < textLength) {
            withFrameNanos { now ->
                if (startNanos < 0L) startNanos = now
                val elapsedMillis = (now - startNanos) / 1_000_000L
                reveal.value = (from + elapsedMillis / line.charDelay).toInt().coerceAtMost(textLength)
            }
        }
    }

    LaunchedEffect(line.id, line.skips) {
        if (line.skips > 0) reveal.value = textLength
    }

    FormattedText(
        value = line.text,
        tags = listOf("dialogue-text"),
        visibleCharacters = reveal.value.coerceIn(0, textLength),
        pendingTags = listOf("dialogue-text-pending"),
    )
}

@Composable
private fun UiScope.ChoiceList(choices: DialogueChoicesView) {
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableStateOf(0f) }
    var firstHeight by remember { mutableStateOf(0f) }
    var lastHeight by remember { mutableStateOf(0f) }
    val lastIndex = choices.options.lastIndex

    fun edge(optionHeight: Float) =
        if (viewportHeight <= 0f || optionHeight <= 0f) 0f else ((viewportHeight - optionHeight) / 2f).coerceAtLeast(0f)

    Box(
        id = "choice-viewport",
        modifier = Modifier
            .scrollable(horizontal = false, hasVerticalScrollbar = false, state = scroll)
            .input(draggable = true)
            .onDrag { event -> scroll.scrollBy(deltaY = -event.deltaY) }
            .onPlaced { rect -> viewportHeight = rect.height },
    ) {
        Column(
            id = "choice-list",
            modifier = Modifier.padding(0.px, edge(firstHeight).px, 0.px, edge(lastHeight).px),
        ) {
            choices.options.forEach { option ->
                Choice(
                    option = option,
                    choices = choices,
                    onMeasured = { height ->
                        if (option.index == 0) firstHeight = height
                        if (option.index == lastIndex) lastHeight = height
                    },
                )
            }
        }
    }
}

@Composable
private fun UiScope.Choice(option: DialogueChoiceView, choices: DialogueChoicesView, onMeasured: (Float) -> Unit) {
    val voted = choices.myVote >= 0
    val mine = choices.myVote == option.index
    val won = choices.decided == option.index
    val lost = choices.decided >= 0 && !won

    var modifier = Modifier.onPlaced { rect -> onMeasured(rect.height) }
    if (voted && !mine) modifier = modifier.state(UiState.DISABLED)
    if (mine || won) modifier = modifier.state(UiState.SELECTED)
    if (won) modifier = modifier.state(UiState.of("won"))
    if (lost) modifier = modifier.state(UiState.of("lost"))
    if (!voted && choices.decided < 0) {
        modifier = modifier.input(hoverable = true, clickable = true)
            .onClick { choose(option.index) }
    }

    val order = "choice-${option.index.coerceIn(0, MAX_STAGGERED_CHOICE)}"
    Row(tags = listOf("choice", order), modifier = modifier) {
        if (!option.icon.equals(NO_ICON, ignoreCase = true)) {
            Image(option.icon.ifEmpty { DESC_TEXTURE }, tags = listOf("choice-desc"))
        }
        Box(tags = listOf("choice-button")) { FormattedText(option.text, tags = listOf("choice-text")) }
        Image(CURSOR_TEXTURE, tags = listOf("choice-cursor"))
    }
}

private fun UiScope.advance() = send { putString(DialogueUiKeys.Action, DialogueUiKeys.AdvanceAction) }

private fun UiScope.choose(index: Int) = send {
    putString(DialogueUiKeys.Action, DialogueUiKeys.ChooseAction)
    putInt(DialogueUiKeys.Index, index)
}
