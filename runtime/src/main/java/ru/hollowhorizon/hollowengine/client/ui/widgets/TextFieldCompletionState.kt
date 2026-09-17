package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.common.scripting.ide.matchCompletion

/**
 * Compose-observable completion model for an [EditableTextField]: the popup items, the selected
 * row and the replacement range the accepted item will overwrite. The widget drives it from
 * typing/key/frame hooks; it owns no UI. The popup stays open while the caret remains on the
 * line it was invoked on, re-querying the [contributor] as the text or [analysis revision]
 * [onFrame] changes.
 */
class TextFieldCompletionState(
    private val state: TextFieldState,
) {
    /** Completion source; when null the popup never opens. */
    var contributor: UiCompletionContributor? = null

    var items: List<UiTextCompletion> by mutableStateOf(emptyList())
        private set
    var selectedIndex: Int by mutableStateOf(0)
        private set

    /** Document offset the popup is anchored to (the caret at the time it opened). */
    var anchor: Int by mutableStateOf(0)
        private set

    val active: Boolean get() = items.isNotEmpty()

    private var opened = false
    private var autoOpenPending = false
    private var replacementStart = 0
    private var replacementEnd = 0
    private var lineStart = 0
    private var lineEnd = 0
    private var lastText: String? = null
    private var lastRevision = Long.MIN_VALUE
    private var lastPrefix: String? = null
    private var selectionPinned = false

    /** Opens (or re-queries) the popup at the current caret. */
    fun open(): Boolean {
        if (state.readOnly) return false
        val contributor = contributor ?: return false
        val text = state.text
        val caret = state.caret
        lastText = text
        val replacement = completionWordRange(text, caret)
        val prefix = text.substring(replacement.first.coerceAtMost(text.length), replacement.last.coerceAtMost(text.length))
        val found = rankAgainst(contributor.complete(UiCompletionContext(text, caret)), prefix)
        val line = completionLineRange(text, caret)
        val keepSelection = opened && prefix == lastPrefix
        if (!keepSelection) selectionPinned = false
        val selected = if (keepSelection && selectionPinned) items.getOrNull(selectedIndex) else null
        anchor = caret
        lastPrefix = prefix
        replacementStart = replacement.first
        replacementEnd = replacement.last
        lineStart = line.first
        lineEnd = line.last
        if (found.isEmpty()) {
            // Nothing yet (e.g. analysis still running) - keep retrying while the caret stays put.
            autoOpenPending = true
            val narrowed = if (opened) rankAgainst(items, prefix) else emptyList()
            if (narrowed.isNotEmpty()) {
                val stillSelected = items.getOrNull(selectedIndex)
                items = narrowed
                selectedIndex = stillSelected
                    ?.let { previous -> narrowed.indexOfFirst { it.sameCandidate(previous) } }
                    ?.takeIf { it >= 0 }
                    ?: 0
                return false
            }
            items = emptyList()
            opened = false
            selectedIndex = 0
            return false
        }
        val changed = !opened || items != found
        items = found
        opened = true
        autoOpenPending = false
        selectedIndex = when {
            selected != null -> found.indexOfFirst { it.sameCandidate(selected) }.coerceAtLeast(0)
            !keepSelection || changed -> 0
            else -> selectedIndex.coerceIn(0, found.lastIndex)
        }
        return changed
    }

    fun close(): Boolean {
        val hadState = items.isNotEmpty() || opened || autoOpenPending
        items = emptyList()
        opened = false
        autoOpenPending = false
        selectedIndex = 0
        lastPrefix = null
        selectionPinned = false
        return hadState
    }

    fun moveSelection(delta: Int): Boolean {
        if (items.isEmpty()) return false
        val previous = selectedIndex
        selectedIndex = (selectedIndex + delta).floorMod(items.size)
        selectionPinned = true
        return previous != selectedIndex
    }

    fun select(index: Int): Boolean {
        if (items.isEmpty()) return false
        val normalized = index.coerceIn(0, items.lastIndex)
        selectionPinned = true
        if (selectedIndex == normalized) return false
        selectedIndex = normalized
        return true
    }

    /** Inserts the chosen item over the replacement range (adding its import when it carries one). */
    fun accept(index: Int = selectedIndex): Boolean {
        if (state.readOnly) return false
        val item = items.getOrNull(index) ?: return false
        val insertText = item.insertText.ifEmpty { item.label }

        val text = state.text
        val replacement = if (item.wordChars.isEmpty()) replacementStart..replacementEnd
        else completionWordRange(text, state.caret, item.wordChars)
        val originalStart = replacement.first.coerceIn(0, text.length)
        val replacementEnd = replacement.last
        val importResult = item.importFqName
            ?.takeIf { it.isNotBlank() }
            ?.let { text.withSortedKotlinImport(it, originalStart) }
            ?: UiTextImportResult(text, 0)
        val imported = importResult.text
        val start = (originalStart + importResult.shiftBeforeReference).coerceIn(0, imported.length)
        val end = (replacementEnd + importResult.shiftBeforeReference).coerceIn(start, imported.length)
        val next = imported.substring(0, start) + insertText + imported.substring(end)
        val caret = start + (item.caretOffset ?: insertText.length).coerceIn(0, insertText.length)
        val changed = state.applyEdit(next, listOf(UiTextCaret(caret)))
        close()
        return changed
    }

    /**
     * Reacts to a typed character (after the state applied it): a trigger character or an already
     * open/pending popup re-queries the contributor at the new caret.
     */
    fun onCharTyped(char: Char) {
        val contributorTrigger = contributor?.triggerCharacters?.contains(char) == true
        if (char.isCompletionTrigger() || contributorTrigger || opened || autoOpenPending) open()
        else lastText = state.text
    }

    /**
     * Per-composition upkeep: closes after non-typing edits or when the caret leaves the invocation
     * line, retries a pending auto-open, and re-queries when the analysis [revision] advances.
     */
    fun onFrame(revision: Long = 0L) {
        if (!opened && !autoOpenPending) {
            lastText = state.text
            lastRevision = revision
            return
        }
        val textChanged = lastText != null && state.text != lastText
        lastText = state.text
        if (textChanged) {
            // Edits made by typing re-open from onCharTyped before we get here; anything else
            // (backspace, paste, undo...) drops the popup like the old field did.
            close()
            lastRevision = revision
            return
        }
        if (state.caret < lineStart || state.caret > lineEnd) {
            close()
            lastRevision = revision
            return
        }
        if (autoOpenPending || (opened && revision != lastRevision)) open()
        lastRevision = revision
    }
}

private fun rankAgainst(candidates: List<UiTextCompletion>, prefix: String): List<UiTextCompletion> =
    candidates.asSequence()
        .filter { it.label.isNotBlank() || it.insertText.isNotBlank() }
        .mapIndexedNotNull { index, item ->
            val filterMatch = matchCompletion(prefix, item.filterText)
            val labelMatch = if (item.filterText == item.label) filterMatch else matchCompletion(prefix, item.label)
            val match = filterMatch ?: labelMatch ?: return@mapIndexedNotNull null
            val displayRanges = labelMatch?.ranges ?: filterMatch?.ranges
                ?.shiftInto(item.filterText, item.label)
                .orEmpty()
            RankedCompletion(item.copy(matchRanges = displayRanges), match.score, index)
        }
        .sortedWith(
            compareBy<RankedCompletion> { it.score }
                .thenByDescending { it.item.closeness }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.item.filterText }
                .thenBy { it.sourceIndex },
        )
        .map(RankedCompletion::item)
        .toList()

private data class RankedCompletion(
    val item: UiTextCompletion,
    val score: Int,
    val sourceIndex: Int,
)

private fun UiTextCompletion.sameCandidate(other: UiTextCompletion): Boolean =
    label == other.label && insertText == other.insertText && detail == other.detail

private fun List<IntRange>.shiftInto(filterText: String, label: String): List<IntRange> {
    val start = label.indexOf(filterText, ignoreCase = true)
    if (start < 0) return emptyList()
    return map { range -> (range.first + start)..(range.last + start) }
}

internal fun Char.isCompletionTrigger(): Boolean = this == '.' || this == '_' || isLetterOrDigit()

/** The word around [caret] that an accepted completion replaces. */
internal fun completionWordRange(text: String, caret: Int, extraWordChars: String = ""): IntRange {
    fun Char.isWordChar() = isCompletionWordChar() || this in extraWordChars

    var end = caret.coerceIn(0, text.length)
    var start = end
    while (start > 0 && text[start - 1].isWordChar()) start--
    while (end < text.length && text[end].isWordChar()) end++
    return start..end
}

private fun completionLineRange(text: String, caret: Int): IntRange {
    val index = caret.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
    return start..end
}

private fun Char.isCompletionWordChar(): Boolean = this == '_' || isLetterOrDigit()

private fun Int.floorMod(modulo: Int): Int {
    if (modulo <= 0) return 0
    val result = this % modulo
    return if (result < 0) result + modulo else result
}
