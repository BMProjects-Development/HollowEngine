package ru.hollowhorizon.hollowengine.client.ui.widgets

internal fun TextFieldState.indentLines(): Boolean {
    val size = indentSize?.coerceAtLeast(0) ?: return false
    if (readOnly || !multiline || size == 0) return false
    if (caretRanges.all { !it.hasSelection }) return insert(" ".repeat(size))
    return applyCodeEdits(selectedLineStarts().map { TextFieldCodeEdit(it, it, " ".repeat(size)) })
}

internal fun TextFieldState.unindentLines(): Boolean {
    val size = indentSize?.coerceAtLeast(0) ?: return false
    if (readOnly || !multiline || size == 0) return false
    val edits = selectedLineStarts().mapNotNull { start ->
        val end = if (text.getOrNull(start) == '\t') start + 1
        else start + text.substring(start, (start + size).coerceAtMost(text.length)).takeWhile { it == ' ' }.length
        if (end > start) TextFieldCodeEdit(start, end, "") else null
    }
    return edits.isNotEmpty() && applyCodeEdits(edits)
}

internal fun TextFieldState.newlineWithIndent(): Boolean {
    if (readOnly || !multiline) return false
    val size = indentSize ?: return insert("\n")
    if (caretRanges.any { it.hasSelection }) return insert("\n")
    return applyCodeEdits(caretRanges.map {
        val edit = newlineEdit(text, it.position, size, syntax)
        TextFieldCodeEdit(edit.start, edit.end, edit.replacement, edit.caretOffset)
    })
}

private fun TextFieldState.selectedLineStarts(): List<Int> {
    val starts = linkedSetOf<Int>()
    for (range in caretRanges) {
        val from = range.selectionStart
        val rawTo = range.selectionEnd
        val to = if (rawTo > from && text.getOrNull(rawTo - 1) == '\n') rawTo - 1 else rawTo
        var start = lineStart(text, from)
        while (start <= to) {
            starts += start
            val next = text.indexOf('\n', start)
            if (next < 0 || next >= to) break
            start = next + 1
        }
    }
    return starts.toList()
}
