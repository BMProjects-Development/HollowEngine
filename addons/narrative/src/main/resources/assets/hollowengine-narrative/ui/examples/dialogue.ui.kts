package assets.hollowengine.ui.examples

import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueChoicesView
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueLineView
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueUiKeys

val STYLE = "hollowengine-narrative:ui/examples/dialogue_overlay.hss"

surface("project:dialogue") {
    title = "Dialogue"
    anchor = "chat"
    placement = HudPlacement.AFTER
    interactive()
    closeOnEscape = false
    guiScale = UiGuiScale.Auto
    exitDuration = 260L

    mode { data ->
        if (data[DialogueUiKeys.Choices].options.isEmpty()) UiSurfaceKind.OVERLAY else UiSurfaceKind.SCREEN
    }

    content {
        val line = data[DialogueUiKeys.Line]
        val choices = data[DialogueUiKeys.Choices]

        Box(id = "dialogue-root", mode = UiBoxMode.FREE, modifier = Modifier.style(STYLE)) {
            if (choices.options.isEmpty()) {
                LineOverlay(line)
            } else {
                ChoiceWindow(choices)
            }
        }
    }
}

@Composable
fun UiScope.LineOverlay(line: DialogueLineView) {
    val waiting = line.awaiting
    var catcher: Modifier = Modifier
    if (waiting) {
        catcher = catcher.input(clickable = true).onClick { event ->
            if (event.isRightClick()) send { putString(DialogueUiKeys.Action, DialogueUiKeys.AdvanceAction) }
        }
    }
    Box(id = "advance-catcher", modifier = catcher)

    Box(id = "line-panel", mode = UiBoxMode.FREE) {
        Row(id = "line-box") {
            if (line.speakerEntity >= 0) {
                Box(id = "portrait-frame") { Entity(line.speakerEntity, id = "portrait") }
            }
            TypedText(line)
        }

        Box(
            id = "advance-hint",
            modifier = if (waiting) Modifier.state(UiState.of("ready")) else null,
        ) {
            Image("hollowengine:textures/gui/icons/mouse_right.png", id = "advance-hint-icon")
        }
    }
}

@Composable
fun TypedText(line: DialogueLineView) {
    val reveal = remember(line.id) { mutableStateOf(if (line.awaiting) line.visibleLength else 0) }

    LaunchedEffect(line.id, line.visibleLength, line.charDelay) {
        if (line.charDelay <= 0) {
            reveal.value = line.visibleLength
            return@LaunchedEffect
        }
        val from = reveal.value
        var startNanos = -1L
        while (reveal.value < line.visibleLength) {
            withFrameNanos { now ->
                if (startNanos < 0L) startNanos = now
                val elapsed = (now - startNanos) / 1_000_000L
                reveal.value = (from + elapsed / line.charDelay).toInt().coerceAtMost(line.visibleLength)
            }
        }
    }

    LaunchedEffect(line.id, line.skips) {
        if (line.skips > 0) reveal.value = line.visibleLength
    }

    val shown = reveal.value.coerceIn(0, line.visibleLength)
    Text(tags = listOf("line-text")) {
        if (line.speaker.isNotEmpty()) Span("[${line.speaker}]: ", tags = listOf("speaker-text"))
        FormattedTextSpans(
            value = line.text,
            visibleCharacters = shown,
            pendingTags = listOf("line-text-pending"),
        )
    }
}

@Composable
fun UiScope.ChoiceWindow(choices: DialogueChoicesView) {
    Column(id = "choice-window") {
        choices.options.forEach { option ->
            val voted = choices.myVote >= 0
            var button: Modifier = Modifier
            if (voted && choices.myVote != option.index) button = button.state(UiState.DISABLED)
            if (choices.myVote == option.index || choices.decided == option.index) {
                button = button.state(UiState.SELECTED)
            }
            if (!voted && choices.decided < 0) {
                button = button.input(hoverable = true, clickable = true).onClick {
                    send {
                        putString(DialogueUiKeys.Action, DialogueUiKeys.ChooseAction)
                        putInt(DialogueUiKeys.Index, option.index)
                    }
                }
            }

            Box(tags = listOf("choice"), modifier = button) {
                FormattedText(option.text, tags = listOf("choice-text"))
            }
        }
    }
}
