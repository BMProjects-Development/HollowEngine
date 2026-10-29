package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.lwjgl.glfw.GLFW
import kotlin.math.abs

internal fun handleEditableFieldPress(
    state: TextFieldState,
    offset: Int,
    clickCount: Int,
    modifiers: Int,
    pointerState: EditableFieldPointerState = EditableFieldPointerState().also {
        it.beginPress(state.text, offset, clickCount, modifiers and GLFW.GLFW_MOD_ALT != 0)
    },
    inlayAffinity: UiInlayCaretAffinity = UiInlayCaretAffinity.AFTER,
    allowSelectionMove: Boolean = true,
) {
    val altPressed = modifiers and GLFW.GLFW_MOD_ALT != 0
    if (allowSelectionMove && clickCount == 1 && !altPressed && modifiers and GLFW.GLFW_MOD_SHIFT == 0 && pointerState.beginSelectionMove(
            state,
            offset
        )
    ) return
    when {
        clickCount >= 3 -> toggleOrAddPointerSelection(state, pointerState, offset, altPressed)
        clickCount == 2 -> toggleOrAddPointerSelection(state, pointerState, offset, altPressed)
        altPressed && state.removeCaretRangeAt(offset) -> pointerState.cancelDrag()
        altPressed -> state.addCaret(offset, inlayAffinity)
        else -> state.moveCaret(offset, inlayAffinity = inlayAffinity)
    }
}

internal fun handleEditableFieldDrag(state: TextFieldState, offset: Int, pointerState: EditableFieldPointerState) {
    if (pointerState.movingSelection) {
        pointerState.moveDragged = true
        state.dropPreview.show(offset.coerceIn(0, state.text.length))
        return
    }
    val selection = pointerState.dragSelection(state.text, offset) ?: return
    applyPointerSelection(state, selection, pointerState.altPressed)
}

internal fun handleEditableFieldRelease(
    state: TextFieldState, offset: Int, modifiers: Int, pointerState: EditableFieldPointerState, inside: Boolean = true,
): Boolean {
    if (!pointerState.movingSelection) {
        pointerState.cancelDrag()
        return false
    }
    if (!pointerState.moveDragged) state.moveCaret(offset)
    else if (inside && pointerState.matchesSelection(state)) {
        val copy = modifiers and (GLFW.GLFW_MOD_CONTROL or GLFW.GLFW_MOD_SUPER) != 0
        state.moveSelections(offset, copy)
    }
    state.clearDropPreview()
    pointerState.cancelDrag()
    return true
}

private fun toggleOrAddPointerSelection(
    state: TextFieldState,
    pointerState: EditableFieldPointerState,
    offset: Int,
    altPressed: Boolean,
) {
    val selection = pointerState.dragSelection(state.text, offset) ?: return
    if (altPressed && state.removeCaretRange(selection)) {
        pointerState.cancelDrag()
        return
    }
    addPointerSelection(state, selection, altPressed)
}

private fun addPointerSelection(state: TextFieldState, selection: UiTextCaret?, altPressed: Boolean) {
    selection ?: return
    val anchor = selection.selectionAnchor ?: selection.position
    if (altPressed) state.addCaretRange(UiTextCaret(selection.position, anchor))
    else state.setSelection(anchor, selection.position)
}

private fun applyPointerSelection(state: TextFieldState, selection: UiTextCaret?, altPressed: Boolean) {
    selection ?: return
    val anchor = selection.selectionAnchor ?: selection.position
    if (altPressed) state.updateLastCaretRange(anchor, selection.position)
    else state.setSelection(anchor, selection.position)
}

internal class EditableFieldPointerState {
    var movingSelection = false
        private set
    var moveDragged = false
    private var sourceText = ""
    private var sourceCarets = emptyList<UiTextCaret>()
    var altPressed = false
        private set

    private var anchor = 0
    private var mode = EditableFieldSelectionMode.CHARACTER
    private var anchorRange = EditableTextRange(0, 0)
    private var dragEnabled = false
    private var lastClickAtMillis = 0L
    private var lastClickOffset = -1
    private var lastClickCount = 0

    fun beginPress(text: String, offset: Int, clickCount: Int, altPressed: Boolean) {
        movingSelection = false
        moveDragged = false
        sourceText = ""
        sourceCarets = emptyList()
        this.altPressed = altPressed
        anchor = offset.coerceIn(0, text.length)
        mode = when {
            clickCount >= 3 -> EditableFieldSelectionMode.LINE
            clickCount == 2 -> EditableFieldSelectionMode.WORD
            else -> EditableFieldSelectionMode.CHARACTER
        }
        anchorRange = selectionUnitAt(text, anchor, mode)
        dragEnabled = true
    }

    fun cancelDrag() {
        dragEnabled = false
        movingSelection = false
        moveDragged = false
        sourceText = ""
        sourceCarets = emptyList()
    }

    fun beginSelectionMove(state: TextFieldState, offset: Int): Boolean {
        if (state.readOnly || state.caretRanges.none { it.hasSelection && offset >= it.selectionStart && offset < it.selectionEnd }) return false
        movingSelection = true
        sourceText = state.text
        sourceCarets = state.caretRanges
        return true
    }

    fun matchesSelection(state: TextFieldState): Boolean = sourceText == state.text && sourceCarets == state.caretRanges

    fun dragSelection(text: String, offset: Int): UiTextCaret? {
        if (!dragEnabled) return null
        val active = offset.coerceIn(0, text.length)
        if (mode == EditableFieldSelectionMode.CHARACTER) return UiTextCaret(active, anchor)

        val activeRange = selectionUnitAt(text, active, mode)
        return if (active < anchorRange.start) {
            UiTextCaret(activeRange.start, anchorRange.end)
        } else {
            UiTextCaret(activeRange.end, anchorRange.start)
        }
    }

    fun clickCount(offset: Int): Int {
        val now = System.currentTimeMillis()
        val continues = now - lastClickAtMillis <= EditableFieldDoubleClickMillis && abs(lastClickOffset - offset) <= 1
        val count = if (continues) (lastClickCount + 1).coerceAtMost(3) else 1
        lastClickAtMillis = now
        lastClickOffset = offset
        lastClickCount = count
        return count
    }
}

private enum class EditableFieldSelectionMode {
    CHARACTER, WORD, LINE,
}

internal data class EditableTextRange(val start: Int, val end: Int)

private fun selectionUnitAt(text: String, offset: Int, mode: EditableFieldSelectionMode): EditableTextRange {
    return when (mode) {
        EditableFieldSelectionMode.CHARACTER -> {
            val index = offset.coerceIn(0, text.length)
            EditableTextRange(index, index)
        }

        EditableFieldSelectionMode.WORD -> wordRangeAt(text, offset)
        EditableFieldSelectionMode.LINE -> realLineRangeAt(text, offset)
    }
}

private fun wordRangeAt(text: String, offset: Int): EditableTextRange {
    if (text.isEmpty()) return EditableTextRange(0, 0)
    val index = offset.coerceIn(0, text.length)
    val characterIndex = if (index < text.length) index else text.lastIndex
    val character = text[characterIndex]
    val predicate: (Char) -> Boolean = when {
        character.isEditableFieldWordChar() -> Char::isEditableFieldWordChar
        character.isWhitespace() -> Char::isWhitespace
        else -> { candidate -> candidate == character }
    }
    var start = characterIndex
    var end = characterIndex + 1
    while (start > 0 && predicate(text[start - 1])) start--
    while (end < text.length && predicate(text[end])) end++
    return EditableTextRange(start, end)
}

private fun realLineRangeAt(text: String, offset: Int): EditableTextRange {
    if (text.isEmpty()) return EditableTextRange(0, 0)
    val index = offset.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
    return EditableTextRange(start, end)
}

private fun Char.isEditableFieldWordChar(): Boolean = this == '_' || isLetterOrDigit()

private const val EditableFieldDoubleClickMillis = 350L
