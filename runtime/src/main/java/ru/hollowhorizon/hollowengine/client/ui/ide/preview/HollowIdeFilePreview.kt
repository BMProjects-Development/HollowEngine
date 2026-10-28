package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeExtensionPoints
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile

private const val ModeLang = "hollowengine.gui.ide.preview.mode"
private const val ViewIcons = "hollowengine:textures/gui/icons/view"

/** How a file that has a preview is shown in its tab. */
enum class HollowIdeViewMode(val icon: String, val tooltip: String) {
    TEXT("$ViewIcons/text.svg", "$ModeLang.text"),
    SPLIT("$ViewIcons/split.svg", "$ModeLang.split"),
    PREVIEW("$ViewIcons/preview.svg", "$ModeLang.preview"),
}

/**
 * A visual view of a text file, shown beside or instead of its text the way IDEA shows Markdown. A file can have
 * several, each with its own button in the tab bar: [icon] and [title], which may be a translation key.
 */
class HollowIdeFilePreview(
    val id: String,
    val defaultMode: HollowIdeViewMode = HollowIdeViewMode.SPLIT,
    val icon: String = HollowIdeViewMode.PREVIEW.icon,
    val title: String = HollowIdeViewMode.PREVIEW.tooltip,
    private val matcher: (path: String) -> Boolean,
    val content: @Composable (HollowIdePreviewContext) -> Unit,
) {
    init {
        require(id.isNotBlank()) { "Preview ID cannot be blank" }
    }

    /** [path] is a project path or a `resource://` one from the asset manager. */
    fun matches(path: String): Boolean = matcher(path)
}

/**
 * Previews by file path: the IDE's own, then those addons contribute. Kept apart from file types so read-only
 * assets get the same preview.
 */
class HollowIdePreviewRegistry {
    private val previews = mutableListOf<HollowIdeFilePreview>()

    fun register(preview: HollowIdeFilePreview) {
        require(previews.none { it.id == preview.id }) { "Preview '${preview.id}' is already registered" }
        previews += preview
    }

    fun find(path: String): List<HollowIdeFilePreview> =
        (previews + HollowIdeExtensionPoints.PREVIEWS.registrations.map { it.value }).filter { it.matches(path) }
}

/** What one open file remembers about its previews while it stays open. */
class HollowIdeFileView internal constructor(val previews: List<HollowIdeFilePreview>) {
    var mode by mutableStateOf(previews.first().defaultMode)

    /** The preview shown in [HollowIdeViewMode.PREVIEW] and beside the text in [HollowIdeViewMode.SPLIT]. */
    var preview by mutableStateOf(previews.first())

    /** The text's share of the width in [HollowIdeViewMode.SPLIT]. */
    var split by mutableStateOf(0.5f)
        internal set

    private val states = HashMap<String, Any>()

    /** State [preview] keeps across tab switches, made on first use. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> state(preview: HollowIdeFilePreview, create: () -> T): T =
        (states[preview.id] as? T) ?: create().also { states[preview.id] = it }
}

class HollowIdePreviewContext internal constructor(
    val file: HollowIdeOpenFile,
    val view: HollowIdeFileView,
    private val preview: HollowIdeFilePreview,
    /** The history of the file's text, which the preview's edits go into. */
    val history: UndoHistory,
    private val write: (next: String, label: UndoLabel?, mergeKey: String?) -> Unit,
) {
    val text: String get() = file.text
    val readOnly: Boolean get() = file.readOnly

    /**
     * Replaces the whole text as one undoable step.
     *
     * @param label what the step is called in the history window.
     * @param mergeKey folds the step into the one just before with the same key, as for typing into one field.
     */
    fun edit(next: String, label: UndoLabel? = null, mergeKey: String? = null) {
        if (!readOnly && next != text) write(next, label, mergeKey)
    }

    fun <T : Any> state(create: () -> T): T = view.state(preview, create)
}

/**
 * A model parsed from the text, kept for as long as the text is the one it came from.
 *
 * Objects the preview hands to its fields (the selection, a name being typed) therefore survive the
 * preview's own edits: after [wrote] the text matches again and nothing is parsed. Only a change made
 * on the text side builds a new model.
 */
class HollowIdeTextModel<T : Any>(private val parse: (String) -> T) {
    private var source: String? = null
    private var parsed: Result<T>? = null

    /** The model for [text], or the reason it does not read; a broken file is never edited over. */
    fun read(text: String): Result<T> {
        parsed?.takeIf { text == source }?.let { return it }
        return runCatching { parse(text) }.also {
            parsed = it
            source = text
        }
    }

    /** Records that [text] is what the current model already says. */
    fun wrote(text: String) {
        if (parsed?.isSuccess == true) source = text
    }

    /** Drops the model, so the next [read] parses the text again. */
    fun invalidate() {
        parsed = null
        source = null
    }
}
