package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.Section
import ru.hollowhorizon.hollowengine.client.ui.widgets.ExpressionEditing
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeInsight
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextFieldMode
import ru.hollowhorizon.hollowengine.common.utils.expressions.Coercions
import ru.hollowhorizon.hollowengine.common.utils.expressions.Declarations
import ru.hollowhorizon.hollowengine.common.utils.expressions.Expression
import ru.hollowhorizon.hollowengine.common.utils.expressions.mathNamespace

/**
 * The data an editor previews a montage or an effect with, in place of an entity or a handle: a short script of
 * assignments, such as `d.waiting = true; d.hp = 12;`, run once each time the preview starts over.
 */
internal object PlaybackPreviewData {
    class Store {
        val values = LinkedHashMap<String, Float>()
    }

    private val declarations: Declarations<Store> = Declarations {
        val data = dynamic(
            name = "data",
            read = { owner, key -> (owner as Store).values[key] ?: 0f },
            write = { owner, key, value -> (owner as Store).values[key] = Coercions.toFloat(value) },
            nested = true,
        )
        property("data", data, alias = "d") { it }
        property("math", mathNamespace()) { it }
    }

    private val language: Expression<Store> = Expression { declarations(declarations) }

    /** Highlighting, completion and diagnostics for the field the script is written in. */
    val editing = ExpressionEditing(language)

    /** What [script] leaves in `d.`, by path; whatever fails to read is left out. */
    fun run(script: String): Map<String, Float> {
        if (script.isBlank()) return emptyMap()
        val store = Store()
        runCatching { language.bake(script).asFloat().eval(store) }
        return store.values
    }
}

/** The preview data of one open file: the script as written, and what it left in `d.` when last run. */
@Stable
internal class PlaybackPreview {
    var script by mutableStateOf("")
        private set

    var values: Map<String, Float> = emptyMap()
        private set

    /** Starts the preview over, told after the data are set as they are written. */
    var onReset: (() -> Unit)? = null

    /** Takes the script as the author changes it; the preview reads the new data from its next frame. */
    fun edit(text: String) {
        script = text
        values = PlaybackPreviewData.run(text)
    }

    /** Sets the data as the script writes them and starts the preview over, from its first moment. */
    fun reset() {
        values = PlaybackPreviewData.run(script)
        onReset?.invoke()
    }
}

/** The field the preview data are written in, with the button that starts the preview over with them. */
@Composable
internal fun PreviewDataSection(preview: PlaybackPreview) {
    Section(playbackText("preview_data"), help = playbackText("preview_data_hint")) {
        TextField(
            value = preview.script,
            id = "playback-preview-data",
            mode = UiTextFieldMode.MULTI_LINE,
            insight = UiCodeInsight(
                completions = PlaybackPreviewData.editing.completions,
                highlighter = PlaybackPreviewData.editing.highlighter,
                diagnostics = PlaybackPreviewData.editing.diagnostics(preview.script),
            ),
            placeholder = "d.waiting = true;",
            fontSize = 9f,
            onChange = preview::edit,
            tags = listOf("insp-input"),
            modifier = Modifier.size(100.percent, PREVIEW_DATA_HEIGHT.px),
        )
        InspectorButton(playbackText("preview_reset"), icon = PREVIEW_RESET_ICON) { preview.reset() }
    }
}

internal const val PREVIEW_RESET_ICON = "hollowengine:textures/gui/icons/vfx/restart.svg"
private const val PREVIEW_DATA_HEIGHT = 64
