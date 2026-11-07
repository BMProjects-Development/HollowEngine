package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryRowField
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.ColorPicker
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeEditor
import kotlin.math.roundToInt

private const val WIDTH = 560
private const val HEIGHT = 340
private const val OPAQUE = 0xFF shl 24
private const val HEX = 16
private const val CHANNEL = 255f
private const val CloseIcon = "hollowengine:textures/gui/icons/close.png"

/** Kotlin of a field opened in a full editor. */
internal class StoryCodeField(val key: StoryFieldKey, val title: String, val value: String, val write: (String) -> Unit)

/** The full editor of [field], with line numbers and everything analysis knows. Every change is written at once. */
@Composable
internal fun StoryCodeDialog(state: StoryEditorState, field: StoryCodeField) {
    var text by remember(field) { mutableStateOf(field.value) }
    val expected = StoryInsight.expected(state, field.key)
    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = UiPopupAlignment.Centered,
        id = "story-code-dialog",
        tags = listOf("dropdown-popup", "story-code-dialog"),
        layer = 100,
        modal = true,
        onDismiss = { state.codeField = null },
    ) {
        Column(tags = listOf("story-code-frame"), modifier = Modifier.size(WIDTH.px, HEIGHT.px)) {
            Row(tags = listOf("story-code-head")) {
                Text(field.title, tags = listOf("story-code-title"), modifier = Modifier.grow(1f))
                expected?.let { Text(it, tags = listOf("story-code-type")) }
                InspectorIconButton(CloseIcon, storyLang("code.close"), id = "story-code-close") {
                    state.codeField = null
                }
            }
            Box(tags = listOf("story-code-body"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
                UiCodeEditor(
                    value = text,
                    onChange = {
                        text = it
                        field.write(it)
                    },
                    insight = StoryInsight.field(state, field.key, text),
                    readOnly = state.context?.readOnly == true,
                    fontSize = 9f,
                    id = "story-code-editor",
                )
            }
        }
    }
}

/** The color of [field], written as `#RRGGBB` as it is picked. */
@Composable
internal fun StoryColorDialog(state: StoryEditorState, field: StoryRowField) {
    var color by remember(field.key) { mutableStateOf(storyColor(field.value)) }
    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = UiPopupAlignment.Centered,
        id = "story-color-dialog",
        tags = listOf("dropdown-popup", "story-color-dialog"),
        layer = 100,
        modal = true,
        onDismiss = { state.colorField = null },
    ) {
        ColorPicker(
            color,
            {
                color = it
                field.write(hex(it))
            },
            showAlpha = false,
            id = "story-color-picker",
        )
    }
}

/** The color a field of `#RRGGBB` holds. */
internal fun storyColor(value: String): UiColor =
    value.trim().removePrefix("#").toIntOrNull(HEX)?.let { UiColor.fromArgb(OPAQUE or it) } ?: UiColor(0f, 0f, 0f)

private fun hex(color: UiColor): String {
    fun channel(value: Float) = (value * CHANNEL).roundToInt().toString(HEX).padStart(2, '0')
    return "#" + (channel(color.red) + channel(color.green) + channel(color.blue)).uppercase()
}
