package ru.hollowhorizon.hollowengine.client.ui.widgets

/** Moves the selected fragments together as one undoable edit, in document order. */
internal fun TextFieldState.transferSelections(target: Int, copy: Boolean): Boolean {
    if (readOnly) return false
    val ranges = caretRanges.filter { it.hasSelection }.sortedBy { it.selectionStart }
        .fold(mutableListOf<UiTextCaret>()) { result, range ->
            val previous = result.lastOrNull()
            if (previous != null && range.selectionStart < previous.selectionEnd) {
                result[result.lastIndex] =
                    UiTextCaret(maxOf(previous.selectionEnd, range.selectionEnd), previous.selectionStart)
            } else result += range
            result
        }
    if (ranges.isEmpty()) return false
    val at = target.coerceIn(0, text.length)
    if (ranges.any { if (copy) at in it.selectionStart + 1 until it.selectionEnd else at in it.selectionStart..it.selectionEnd }) return false
    val selected =
        ranges.joinToString(if (multiline) "\n" else " ") { text.substring(it.selectionStart, it.selectionEnd) }
    val without = if (copy) text else buildString {
        var from = 0
        for (range in ranges) {
            append(text, from, range.selectionStart)
            from = range.selectionEnd
        }
        append(text, from, text.length)
    }
    val removedBefore =
        if (copy) 0 else ranges.filter { it.selectionEnd <= at }.sumOf { it.selectionEnd - it.selectionStart }
    val insertAt = at - removedBefore
    val next = without.substring(0, insertAt) + selected + without.substring(insertAt)
    return applyEdit(next, listOf(UiTextCaret(insertAt + selected.length, insertAt)))
}
