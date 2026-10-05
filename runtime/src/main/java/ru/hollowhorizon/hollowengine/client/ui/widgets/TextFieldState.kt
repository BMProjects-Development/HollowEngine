package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.history.*
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.style.DefaultUiFontSize
import ru.hollowhorizon.hollowengine.client.ui.text.Shadow

/**
 * Compose-observable holder for an editable text field. Owns the whole edit model - text plus a
 * list of carets/selections, and exposes edit operations directly. [caretRanges] is the single
 * source of truth; the primary caret is always the last range. Also carries the field's view
 * config (font, wrap, colors) so the widget itself stays a thin `EditableTextField(state)`.
 */
class TextFieldState(
    initialText: String = "",
    initialCaret: Int = initialText.length,
    var multiline: Boolean = false,
    var readOnly: Boolean = false,
    var filter: UiTextInputFilter = UiTextInputFilter.ANY,
    var indentSize: Int? = 4,
    var autoPairs: Boolean = true,
    var multiCaret: Boolean = true,
    var pasteTransformer: UiTextPasteTransformer? = null,
    fontSize: Float = DefaultUiFontSize,
    fontFamily: String? = null,
    wrap: Boolean = false,
    caretColor: UiColor = DefaultTextFieldCaretColor,
    selectionColor: UiColor = DefaultTextFieldSelectionColor,
    textShadow: Shadow? = Shadow(offsetX = 1f, offsetY = 1f),
    historyMergeWindowNanos: Long = TextFieldStateHistoryMergeWindowNanos,
    nanoTime: () -> Long = System::nanoTime,
) : UndoOwner {
    val scroll: UiScrollHandle = UiScrollHandle()

    var fontSize: Float by mutableStateOf(fontSize)
    var fontFamily: String? by mutableStateOf(fontFamily)
    var wrap: Boolean by mutableStateOf(wrap)
    var caretColor: UiColor by mutableStateOf(caretColor)
    var selectionColor: UiColor by mutableStateOf(selectionColor)
    var textShadow: Shadow? by mutableStateOf(textShadow)

    var text: String by mutableStateOf(initialText.normalizeEditorLineEndings())
        private set

    var caretRanges: List<UiTextCaret> by mutableStateOf(
        listOf(UiTextCaret(initialCaret.coerceIn(0, text.length))),
    )
        private set

    /** Bumped whenever carets move or edit, so the caret blink can reset to fully-visible. */
    var caretVisibilityRevision: Int by mutableStateOf(0)
        private set

    var offsetAtPoint: ((Float, Float) -> Int)? = null
        internal set

    var focused: Boolean by mutableStateOf(false)
        private set

    val primaryCaret: UiTextCaret get() = caretRanges.last()
    val caret: Int get() = primaryCaret.position
    val selectionAnchor: Int? get() = primaryCaret.selectionAnchor
    val hasSelection: Boolean get() = caretRanges.any { it.hasSelection }

    /** The text and carets before each burst of typing; a pause or a caret move starts the next one. */
    override val history = UndoHistory(TextFieldStateHistoryLimit, mergeWindowNanos = historyMergeWindowNanos, nanoTime = nanoTime)
    private var restoringHistory = false

    fun insert(insertion: String): Boolean {
        if (readOnly || insertion.isEmpty()) return false
        val normalized = insertion.normalizeEditorLineEndings()
        val sanitized = if (multiline) normalized else normalized.replace('\n', ' ')
        return replaceSelectedRanges(sanitized)
    }

    fun paste(insertion: String): Boolean {
        if (readOnly || insertion.isEmpty()) return false
        val normalized = insertion.normalizeEditorLineEndings()
        val sanitized = if (multiline) normalized else normalized.replace('\n', ' ')
        val ranges = activeCaretRanges()
        val lines = if (multiline) sanitized.split('\n') else emptyList()
        val distributed = lines.size == ranges.size && ranges.size > 1
        val transformer = pasteTransformer
        return if (transformer != null) {
            val replacements = if (distributed) lines else List(ranges.size) { sanitized }
            replaceRangesIndividually(
                ranges.map { TextEditRange(it.selectionStart, it.selectionEnd) },
                ranges.zip(replacements) { range, replacement ->
                    transformer.transform(text, range, replacement)
                },
                selectInserted = false,
            )
        } else if (distributed) {
            replaceRangesIndividually(
                ranges.map { TextEditRange(it.selectionStart, it.selectionEnd) },
                lines,
                selectInserted = false,
            )
        } else {
            replaceSelectedRanges(sanitized)
        }
    }

    fun duplicateSelections(): Boolean {
        if (readOnly) return false
        val selections = activeCaretRanges()
            .filter { it.hasSelection }
            .sortedWith(compareBy<UiTextCaret> { it.selectionStart }.thenBy { it.selectionEnd })
        if (selections.isEmpty()) return false
        return replaceRangesIndividually(
            selections.map { TextEditRange(it.selectionEnd, it.selectionEnd) },
            selections.map { text.substring(it.selectionStart, it.selectionEnd) },
            selectInserted = true,
        )
    }

    fun typeCharacter(char: Char): Boolean {
        if (!autoPairs) return insert(char.toString())
        if (trySkipClosingPair(char)) return true

        val closing = AutoPairClosings[char] ?: return insert(char.toString())
        if (activeCaretRanges().any { it.hasSelection }) return insert(char.toString())

        val changed = insert("$char$closing")
        if (changed) moveCarets({ range -> range.position - 1 })
        return changed
    }

    fun backspace(word: Boolean = false): Boolean {
        if (readOnly) return false
        if (activeCaretRanges().any { it.hasSelection }) return replaceSelectedRanges("")
        if (!word && multiline && deleteWhitespaceOnlyLineBeforeCaret()) return true
        if (!word && autoPairs) {
            val pairRanges = activeCaretRanges().mapNotNull { range ->
                val opening = text.getOrNull(range.position - 1) ?: return@mapNotNull null
                val closing = text.getOrNull(range.position) ?: return@mapNotNull null
                if (AutoPairClosings[opening] == closing) TextEditRange(range.position - 1, range.position + 1) else null
            }
            if (pairRanges.size == activeCaretRanges().size && pairRanges.isNotEmpty()) {
                return replaceRanges(pairRanges, "")
            }
        }
        val ranges = activeCaretRanges().mapNotNull { range ->
            if (range.position <= 0) null
            else TextEditRange(if (word) wordLeft(text, range.position) else range.position - 1, range.position)
        }
        return replaceRanges(ranges, "")
    }

    fun deleteForward(word: Boolean = false): Boolean {
        if (readOnly) return false
        if (activeCaretRanges().any { it.hasSelection }) return replaceSelectedRanges("")
        val ranges = activeCaretRanges().mapNotNull { range ->
            if (range.position >= text.length) null
            else TextEditRange(range.position, if (word) wordRight(text, range.position) else range.position + 1)
        }
        return replaceRanges(ranges, "")
    }

    fun indent(): Boolean {
        if (readOnly) return false
        val size = indentSize ?: return false
        if (!multiline) return false
        if (activeCaretRanges().all { !it.hasSelection }) return insert(" ".repeat(size))

        val lineStarts = selectedLineStarts()
        if (lineStarts.isEmpty()) return false
        return replaceRanges(lineStarts.map { TextEditRange(it, it) }, " ".repeat(size))
    }

    fun unindent(): Boolean {
        if (readOnly) return false
        val size = indentSize ?: return false
        if (!multiline) return false
        val ranges = selectedLineStarts().mapNotNull { start ->
            val end = (start + size).coerceAtMost(text.length)
            val removable = text.substring(start, end).takeWhile { it == ' ' }.length
            removable.takeIf { it > 0 }?.let { TextEditRange(start, start + it) }
        }
        return replaceRanges(ranges, "")
    }

    fun insertNewlineWithIndent(): Boolean {
        if (readOnly || !multiline) return false
        val size = indentSize ?: return insert("\n")
        if (activeCaretRanges().any { it.hasSelection }) return insert("\n")

        val position = caret.coerceIn(0, text.length)
        val lineStart = if (position == 0) 0 else {
            val lastLineBreak = text.lastIndexOf('\n', position - 1)
            if (lastLineBreak < 0) 0 else lastLineBreak + 1
        }
        val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val column = position - lineStart
        val context = newlineIndentContext(lineStart, line, column, size)
        val baseIndent = context.baseIndent
        val beforeCaret = line.substring(0, column).trimEnd()
        val afterCaret = line.substring(column).trimStart()
        val opensBlock = beforeCaret.endsWith("{") || beforeCaret.endsWith("(") || beforeCaret.endsWith("[") ||
                line.isBlank() && context.previousOpensBlock
        val closesBlock = afterCaret.startsWith("}") || afterCaret.startsWith(")") || afterCaret.startsWith("]")

        return if (opensBlock && closesBlock) {
            val innerIndent = baseIndent + " ".repeat(size)
            val changed = insert("\n$innerIndent\n$baseIndent")
            if (changed) moveCarets({ range -> range.position - baseIndent.length - 1 })
            changed
        } else {
            val nextIndent = if (opensBlock) baseIndent + " ".repeat(size) else baseIndent
            insert("\n$nextIndent")
        }
    }

    /**
     * Applies a programmatic edit (e.g. accepting a completion item) as a single undoable step:
     * the whole next text plus the carets it should leave behind.
     */
    fun applyEdit(next: String, carets: List<UiTextCaret>): Boolean {
        if (readOnly) return false
        val normalized = next.normalizeEditorLineEndings()
        if (!filter.accepts(normalized)) return false
        if (normalized == text) {
            setCarets(carets)
            return false
        }
        recordHistorySnapshot()
        text = normalized
        setCaretsAfterEdit(carets)
        return true
    }

    /** Replaces the whole text (e.g. external/programmatic change); resets undo history. */
    fun setText(next: String, moveCaretToEnd: Boolean = true) {
        val normalized = next.normalizeEditorLineEndings()
        if (!filter.accepts(normalized)) return
        val changed = normalized != text
        text = normalized
        if (changed) history.clear()
        if (moveCaretToEnd) setCarets(listOf(UiTextCaret(text.length)))
        else setCarets(caretRanges.map { it.coerceIn(text.length) })
    }

    fun moveCaret(
        position: Int,
        select: Boolean = false,
        inlayAffinity: UiInlayCaretAffinity = UiInlayCaretAffinity.AFTER,
    ) {
        val previous = primaryCaret
        val anchor = if (select) previous.selectionAnchor ?: previous.position else null
        setCarets(listOf(UiTextCaret(position.coerceIn(0, text.length), anchor, inlayAffinity)))
        if (caretRanges.last() != previous) breakHistoryGroup()
    }

    fun moveCarets(transform: (UiTextCaret) -> Int, select: Boolean = false) {
        val next = activeCaretRanges().map { range ->
            val position = transform(range).coerceIn(0, text.length)
            UiTextCaret(position, if (select) range.selectionAnchor ?: range.position else null)
        }
        setCarets(next)
    }

    internal fun moveCaretsWithAffinity(
        transform: (UiTextCaret) -> UiTextCaret,
        select: Boolean = false,
    ) {
        val next = activeCaretRanges().map { range ->
            val moved = transform(range).coerceIn(text.length)
            moved.copy(selectionAnchor = if (select) range.selectionAnchor ?: range.position else null)
        }
        setCarets(next)
    }

    fun addCaret(position: Int, inlayAffinity: UiInlayCaretAffinity = UiInlayCaretAffinity.AFTER) {
        if (!multiCaret) return moveCaret(position, inlayAffinity = inlayAffinity)
        val caret = UiTextCaret(position.coerceIn(0, text.length), inlayAffinity = inlayAffinity)
        val ranges = activeCaretRanges()
        val next = if (ranges.any { !it.hasSelection && it.position == caret.position }) {
            ranges.filterNot { !it.hasSelection && it.position == caret.position }
        } else {
            ranges + caret
        }
        setCarets(next.ifEmpty { listOf(caret) })
    }

    fun addCaretRange(range: UiTextCaret) {
        if (!multiCaret) return setCarets(listOf(range))
        val normalized = range.coerceIn(text.length)
        val next = activeCaretRanges().filterNot { it.conflictsWith(normalized) } + normalized
        setCarets(next.ifEmpty { listOf(normalized) })
    }

    fun removeCaretRangeAt(position: Int): Boolean {
        val normalized = position.coerceIn(0, text.length)
        val ranges = activeCaretRanges()
        val index = ranges.indexOfFirst { range ->
            if (range.hasSelection) normalized in range.selectionStart..range.selectionEnd else range.position == normalized
        }
        if (index < 0) return false
        val next = ranges.toMutableList().also { it.removeAt(index) }
        setCarets(next.ifEmpty { listOf(UiTextCaret(normalized)) })
        return true
    }

    fun removeCaretRange(range: UiTextCaret): Boolean {
        val normalized = range.coerceIn(text.length)
        val ranges = activeCaretRanges()
        val index = ranges.indexOfFirst { existing ->
            existing.selectionStart == normalized.selectionStart && existing.selectionEnd == normalized.selectionEnd
        }
        if (index < 0) return false
        val next = ranges.toMutableList().also { it.removeAt(index) }
        setCarets(next.ifEmpty { listOf(UiTextCaret(normalized.position)) })
        return true
    }

    fun updateLastCaretRange(anchor: Int, active: Int) {
        val range = UiTextCaret(active.coerceIn(0, text.length), anchor.coerceIn(0, text.length))
        if (!multiCaret) return setCarets(listOf(range))
        val ranges = activeCaretRanges().dropLast(1).filterNot { it.conflictsWith(range) } + range
        setCarets(ranges.ifEmpty { listOf(range) })
    }

    fun setSelection(anchor: Int, active: Int) {
        val a = anchor.coerceIn(0, text.length)
        setCarets(listOf(UiTextCaret(active.coerceIn(0, text.length), a)))
    }

    fun selectAll() = setCarets(listOf(UiTextCaret(text.length, 0)))

    fun clearSelection() = setCarets(activeCaretRanges().map { UiTextCaret(it.position) })

    fun focus() {
        if (focused) return
        focused = true
        caretVisibilityRevision++
    }

    fun unfocus() {
        focused = false
    }

    fun selectedText(): String? {
        val selections = activeCaretRanges().filter { it.hasSelection }
        if (selections.isEmpty()) return null
        return selections.sortedBy { it.selectionStart }.joinToString("\n") { text.substring(it.selectionStart, it.selectionEnd) }
    }

    fun setCarets(ranges: List<UiTextCaret>) {
        val previous = caretRanges
        applyCaretRanges(ranges)
        if (caretRanges != previous) caretVisibilityRevision++
    }

    fun undo(): Boolean = !readOnly && history.undo()

    fun redo(): Boolean = !readOnly && history.redo()

    private fun replaceSelectedRanges(replacement: String): Boolean {
        val ranges = activeCaretRanges().map { TextEditRange(it.selectionStart, it.selectionEnd) }
        return replaceRanges(ranges, replacement)
    }

    private fun replaceRanges(ranges: List<TextEditRange>, replacement: String): Boolean {
        if (readOnly) return false
        val edits = ranges
            .map { TextEditRange(it.start.coerceIn(0, text.length), it.end.coerceIn(0, text.length)) }
            .filter { it.start != it.end || replacement.isNotEmpty() }
            .sortedWith(compareBy<TextEditRange> { it.start }.thenBy { it.end })
            .fold(mutableListOf<TextEditRange>()) { acc, range ->
                if (acc.isEmpty() || range.start >= acc.last().end) acc += range
                else if (range.end > acc.last().end) acc[acc.lastIndex] = TextEditRange(acc.last().start, range.end)
                acc
            }
        if (edits.isEmpty()) return false

        val nextValue = buildString {
            var cursor = 0
            for (edit in edits) {
                append(text, cursor, edit.start)
                append(replacement)
                cursor = edit.end
            }
            append(text, cursor, text.length)
        }
        if (!filter.accepts(nextValue)) return false
        recordHistorySnapshot()

        var offset = 0
        val nextCarets = edits.map { edit ->
            val position = edit.start + offset + replacement.length
            offset += replacement.length - (edit.end - edit.start)
            UiTextCaret(position)
        }
        text = nextValue
        setCaretsAfterEdit(nextCarets)
        return true
    }

    private fun replaceRangesIndividually(
        ranges: List<TextEditRange>,
        replacements: List<String>,
        selectInserted: Boolean,
    ): Boolean {
        if (readOnly || ranges.size != replacements.size) return false
        val edits = ranges.zip(replacements)
            .map { (range, replacement) ->
                TextEdit(
                    range = TextEditRange(range.start.coerceIn(0, text.length), range.end.coerceIn(0, text.length)),
                    replacement = replacement,
                )
            }
            .filter { it.range.start != it.range.end || it.replacement.isNotEmpty() }
            .sortedWith(compareBy<TextEdit> { it.range.start }.thenBy { it.range.end })
        if (edits.isEmpty()) return false
        if (edits.zipWithNext().any { (left, right) -> right.range.start < left.range.end }) return false

        val nextValue = buildString {
            var cursor = 0
            for (edit in edits) {
                append(text, cursor, edit.range.start)
                append(edit.replacement)
                cursor = edit.range.end
            }
            append(text, cursor, text.length)
        }
        if (!filter.accepts(nextValue)) return false
        recordHistorySnapshot()

        var offset = 0
        val nextCarets = edits.map { edit ->
            val insertStart = edit.range.start + offset
            val position = insertStart + edit.replacement.length
            offset += edit.replacement.length - (edit.range.end - edit.range.start)
            if (selectInserted && edit.replacement.isNotEmpty()) UiTextCaret(position, insertStart) else UiTextCaret(position)
        }
        text = nextValue
        setCaretsAfterEdit(nextCarets)
        return true
    }

    private fun setCaretsAfterEdit(ranges: List<UiTextCaret>) {
        val previousRevision = caretVisibilityRevision
        setCarets(ranges)
        if (caretVisibilityRevision == previousRevision) caretVisibilityRevision++
    }

    private fun UiTextCaret.conflictsWith(other: UiTextCaret): Boolean {
        if (!other.hasSelection) return !hasSelection && position == other.position
        if (!hasSelection) return position in other.selectionStart..other.selectionEnd
        return selectionStart < other.selectionEnd && other.selectionStart < selectionEnd
    }

    private fun applyCaretRanges(ranges: List<UiTextCaret>) {
        caretRanges = ranges
            .ifEmpty { listOf(UiTextCaret(0)) }
            .map { it.coerceIn(text.length) }
            .let { if (multiCaret) it else listOf(it.last()) }
            .distinctBy { it.position to it.selectionAnchor }
    }

    private fun activeCaretRanges(): List<UiTextCaret> = if (multiCaret) caretRanges else caretRanges.take(1)

    private fun trySkipClosingPair(char: Char): Boolean {
        if (char !in AutoPairClosings.values) return false
        if (activeCaretRanges().any { it.hasSelection || text.getOrNull(it.position) != char }) return false
        moveCarets({ range -> range.position + 1 })
        return true
    }

    private fun deleteWhitespaceOnlyLineBeforeCaret(): Boolean {
        val position = caret.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (position - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it }
        if (lineEnd <= lineStart) return false
        if (text.substring(lineStart, lineEnd).any { it != ' ' }) return false

        val deleteStart = if (lineStart > 0) lineStart - 1 else 0
        val deleteEnd = if (lineStart == 0 && lineEnd < text.length) lineEnd + 1 else lineEnd
        return replaceRanges(listOf(TextEditRange(deleteStart, deleteEnd)), "")
    }

    private fun selectedLineStarts(): List<Int> {
        val starts = linkedSetOf<Int>()
        for (range in activeCaretRanges()) {
            val from = range.selectionStart.coerceIn(0, text.length)
            val rawTo = range.selectionEnd.coerceIn(0, text.length)
            val to = if (rawTo > from && text.getOrNull(rawTo - 1) == '\n') rawTo - 1 else rawTo
            var lineStart = text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            while (lineStart <= to) {
                starts += lineStart
                val next = text.indexOf('\n', lineStart)
                if (next < 0 || next >= to) break
                lineStart = next + 1
            }
        }
        return starts.toList()
    }

    private fun newlineIndentContext(lineStart: Int, line: String, column: Int, indentSize: Int): NewlineIndentContext {
        if (line.isNotBlank()) return NewlineIndentContext(line.takeWhile { it == ' ' }, previousOpensBlock = false)

        var scanEnd = (lineStart - 1).coerceAtLeast(0)
        while (scanEnd > 0) {
            val previousStart = text.lastIndexOf('\n', scanEnd - 1).let { if (it < 0) 0 else it + 1 }
            val previousLine = text.substring(previousStart, scanEnd)
            if (previousLine.isNotBlank()) {
                val indent = previousLine.takeWhile { it == ' ' }
                val opens = previousLine.trimEnd().let { it.endsWith("{") || it.endsWith("(") || it.endsWith("[") }
                return NewlineIndentContext(indent, opens)
            }
            scanEnd = (previousStart - 1).coerceAtLeast(0)
            if (previousStart == 0) break
        }
        return NewlineIndentContext(" ".repeat((column / indentSize) * indentSize), previousOpensBlock = false)
    }

    private fun recordHistorySnapshot() {
        if (restoringHistory) return
        history.record(SnapshotStep(this, TypingLabel, historyEntry(), ::historyEntry, ::restoreHistory), TypingMergeKey)
    }

    private fun breakHistoryGroup() = history.breakMerge()

    private fun historyEntry(): TextFieldHistoryEntry = TextFieldHistoryEntry(text, caretRanges.toList())

    private fun restoreHistory(entry: TextFieldHistoryEntry) {
        restoringHistory = true
        try {
            text = entry.text
            applyCaretRanges(entry.caretRanges.map { it.coerceIn(text.length) })
            caretVisibilityRevision++
        } finally {
            restoringHistory = false
        }
    }

    private data class TextEditRange(val start: Int, val end: Int)
    private data class TextEdit(val range: TextEditRange, val replacement: String)
    private data class NewlineIndentContext(val baseIndent: String, val previousOpensBlock: Boolean)
    private data class TextFieldHistoryEntry(val text: String, val caretRanges: List<UiTextCaret>)
}

private val DefaultTextFieldCaretColor = UiColor(0.85f, 0.88f, 0.95f, 1f)
private val DefaultTextFieldSelectionColor = UiColor(0.28f, 0.54f, 0.95f, 0.35f)

private const val TextFieldStateHistoryLimit = 128
private const val TextFieldStateHistoryMergeWindowNanos = 600_000_000L
private const val TypingMergeKey = "typing"
private val TypingLabel = UndoLabel("${UndoLabel.LANG}.typing")
private val AutoPairClosings = mapOf(
    '(' to ')',
    '[' to ']',
    '{' to '}',
    '"' to '"',
    '\'' to '\'',
)
