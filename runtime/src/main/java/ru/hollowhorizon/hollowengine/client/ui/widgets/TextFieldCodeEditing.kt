package ru.hollowhorizon.hollowengine.client.ui.widgets



internal data class TextFieldCodeEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val caretOffset: Int = replacement.length,
    val anchorOffset: Int? = null,
)

internal fun TextFieldState.applyCodeEdits(edits: List<TextFieldCodeEdit>): Boolean {
    if (edits.isEmpty() || readOnly) return false
    val result = codeEditResult(text, edits) ?: return false
    if (!filter.accepts(result.text)) return false
    val moved = result.carets != caretRanges
    return applyEdit(result.text, result.carets) || moved
}

private data class CodeEditResult(val text: String, val carets: List<UiTextCaret>)

private fun codeEditResult(text: String, edits: List<TextFieldCodeEdit>): CodeEditResult? {
    val ordered = edits.distinct().sortedBy { it.start }
    if (ordered.zipWithNext().any { (left, right) -> right.start < left.end }) return null
    val carets = ArrayList<UiTextCaret>()
    val next = buildString {
        var cursor = 0
        for (edit in ordered) {
            append(text, cursor, edit.start)
            carets += UiTextCaret(length + edit.caretOffset, edit.anchorOffset?.let { length + it })
            append(edit.replacement)
            cursor = edit.end
        }
        append(text, cursor, text.length)
    }
    return CodeEditResult(next, carets)
}

internal fun TextFieldState.typeCodeCharacter(char: Char): Boolean {
    if (readOnly) return false
    val ranges = if (multiCaret) caretRanges else listOf(primaryCaret)
    val size = indentSize
    val code = syntax
    if (char == '.' && code != null && multiline && size != null && size > 0) {
        val continuations = ranges.map { if (it.hasSelection) null else continuationDotEdit(text, it.position, size, code) }
        if (continuations.any { it != null }) {
            return applyCodeEdits(ranges.zip(continuations) { range, edit ->
                edit ?: TextFieldCodeEdit(range.selectionStart, range.selectionEnd, ".")
            })
        }
    }
    if (!autoPairs || char !in EditorPairClosings && char !in EditorPairClosings.values) return insert(char.toString())
    val edits = ranges.map { range ->
        val closing = EditorPairClosings[char]
        when {
            range.hasSelection && closing != null -> surroundEdit(range, char, closing)
            range.hasSelection -> TextFieldCodeEdit(range.selectionStart, range.selectionEnd, char.toString())
            else -> characterEdit(range.position, char)
        }
    }
    val typed = codeEditResult(text, edits) ?: return false
    val result = if (code != null && multiline && size != null && char in "})]") typed.reindentClosings(char, size, code) else typed
    if (!filter.accepts(result.text)) return false
    val moved = result.carets != caretRanges
    return applyEdit(result.text, result.carets) || moved
}

private fun CodeEditResult.reindentClosings(char: Char, size: Int, syntax: UiCodeSyntax): CodeEditResult {
    val structure = TextFieldCodeStructure(text, text.length, syntax)
    val changes = carets.sortedByDescending { it.position }.flatMap { range ->
        val position = range.position - 1
        if (position >= 0 && syntax.isCodeAt(text, position)) structure.closingIndents(position, char, size)
        else emptyList()
    }.distinctBy { it.start }.sortedBy { it.start }
    if (changes.isEmpty()) return this
    val next = buildString {
        var cursor = 0
        changes.forEach { change ->
            append(text, cursor, change.start)
            append(change.replacement)
            cursor = change.end
        }
        append(text, cursor, text.length)
    }
    fun mapped(position: Int): Int {
        var shift = 0
        for (change in changes) {
            if (position < change.start) break
            if (position < change.end) return change.start + shift + (position - change.start).coerceAtMost(change.replacement.length)
            shift += change.replacement.length - (change.end - change.start)
        }
        return position + shift
    }
    return CodeEditResult(next, carets.map { it.copy(position = mapped(it.position), selectionAnchor = it.selectionAnchor?.let(::mapped)) })
}

private fun TextFieldState.characterEdit(position: Int, char: Char): TextFieldCodeEdit {
    if (char in EditorPairClosings.values && text.getOrNull(position) == char) {
        return TextFieldCodeEdit(position, position + 1, char.toString())
    }
    val closing = EditorPairClosings[char]
    return if (closing != null && pairs(position, char)) {
        TextFieldCodeEdit(position, position, "$char$closing", caretOffset = 1)
    } else TextFieldCodeEdit(position, position, char.toString())
}

/**
 * Whether typing [char] at [position] adds its closing pair: with a [syntax], only in code, not in its strings and
 * comments; in plain text, unless a quote follows a letter, as the apostrophe in "don't" does.
 */
private fun TextFieldState.pairs(position: Int, char: Char): Boolean {
    syntax?.let { return it.isCodeAt(text, position) }
    return char != '"' && char != '\'' || text.getOrNull(position - 1)?.isLetterOrDigit() != true
}

private fun TextFieldState.surroundEdit(range: UiTextCaret, opening: Char, closing: Char): TextFieldCodeEdit {
    val selected = text.substring(range.selectionStart, range.selectionEnd)
    val size = indentSize
    val code = syntax
    val replacement = if (code != null && multiline && size != null && opening in "{([" && '\n' in selected && code.isCodeAt(text, range.selectionStart)) {
        val base = text.indentOfLine(range.selectionStart)
        val leading = text.substring(lineStart(text, range.selectionStart), range.selectionStart)
            .takeIf { prefix -> prefix.all { it == ' ' || it == '\t' } }.orEmpty()
        val lines = (leading + selected).split('\n')
        val structure = TextFieldCodeStructure(text, text.length, code)
        var offset = range.selectionStart - leading.length
        val content = lines.map { line ->
            val preserve = structure.preservesLineIndent(offset)
            offset += line.length + 1
            line to preserve
        }
        val margin = content.filter { (line, preserve) -> !preserve && line.isNotBlank() }
            .minOfOrNull { (line, _) -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        val indent = base + " ".repeat(size.coerceAtLeast(0))
        val body = content.joinToString("\n") { (line, preserve) ->
            when {
                preserve -> line
                line.isBlank() -> ""
                else -> indent + line.drop(margin)
            }
        }
        "$opening\n$body\n$base$closing"
    } else "$opening$selected$closing"
    val backwards = range.position < (range.selectionAnchor ?: range.position)
    return TextFieldCodeEdit(range.selectionStart, range.selectionEnd, replacement,
        caretOffset = if (backwards) 0 else replacement.length,
        anchorOffset = if (backwards) replacement.length else 0)
}

