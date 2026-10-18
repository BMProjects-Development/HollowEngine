package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.lwjgl.glfw.GLFW

/** Alt+Enter lists the actions at the caret in the completion popup, which then owns the keys. */
internal fun handleEditableFieldIntentionKey(
    state: TextFieldState,
    input: UiKeyInput,
    completion: TextFieldCompletionState,
    diagnostics: List<UiTextDiagnostic>,
): Boolean {
    if (!input.alt || input.key != GLFW.GLFW_KEY_ENTER && input.key != GLFW.GLFW_KEY_KP_ENTER) return false
    return completion.openIntentions(quickFixesAt(diagnostics, state.caret))
}

/** The fixes offered at [caret], in diagnostic order and without duplicate titles. */
internal fun quickFixesAt(diagnostics: List<UiTextDiagnostic>, caret: Int): List<UiTextQuickFix> =
    diagnostics.asSequence().filter { it.fixes.isNotEmpty() && caret >= it.start && caret <= it.end }
        .flatMap { it.fixes }.distinctBy { it.title }.toList()

/** An Alt+Enter action shown in the completion popup; accepting it applies [UiTextQuickFix.edits]. */
internal fun UiTextQuickFix.toCompletion() = UiTextCompletion(title, insertText = "", edits = edits)

/** Applies all edits atomically, preserving carets and leaving a single undo step. */
internal fun TextFieldState.applyTextEdits(edits: List<UiTextEdit>): Boolean {
    val text = text
    val sorted = edits.sortedWith(compareBy({ it.start }, { it.end }))
    if (sorted.isEmpty() || sorted.any { it.start < 0 || it.end < it.start || it.end > text.length }) return false
    if (sorted.zipWithNext().any { (left, right) -> right.start < left.end }) return false
    val next = buildString {
        var cursor = 0
        for (edit in sorted) {
            append(text, cursor, edit.start)
            append(edit.replacement)
            cursor = edit.end
        }
        append(text, cursor, text.length)
    }

    fun mapOffset(offset: Int): Int {
        var shift = 0
        for (edit in sorted) {
            if (offset < edit.start) break
            if (offset <= edit.end) return edit.start + shift + edit.replacement.length
            shift += edit.replacement.length - (edit.end - edit.start)
        }
        return offset + shift
    }
    return applyEdit(next, caretRanges.map { UiTextCaret(mapOffset(it.position)) })
}
