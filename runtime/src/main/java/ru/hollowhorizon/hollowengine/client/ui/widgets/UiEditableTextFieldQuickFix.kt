package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter

/**
 * The Alt+Enter menu: diagnostic fixes and asynchronous intention actions. Kept apart from the
 * completion popup because actions are requested explicitly, and because
 * accepting one rewrites arbitrary parts of the document instead of the word being typed.
 */
internal class EditableFieldQuickFixState {
    private var requestVersion = 0L
    private var requestedContext: UiCompletionContext? = null
    var fixes: List<UiTextQuickFix> by mutableStateOf(emptyList())
        private set
    var anchor: Int by mutableStateOf(0)
        private set
    var selectedIndex: Int by mutableStateOf(0)
        private set

    val active: Boolean get() = fixes.isNotEmpty()
    val pending: Boolean get() = requestedContext != null && fixes.isEmpty()

    fun open(state: TextFieldState, fixes: List<UiTextQuickFix>) {
        close()
        requestedContext = UiCompletionContext(state.text, state.caret)
        this.anchor = state.caret
        this.fixes = fixes
        selectedIndex = 0
    }

    fun close() {
        requestVersion++
        requestedContext = null
        if (fixes.isEmpty()) return
        fixes = emptyList()
        selectedIndex = 0
    }

    fun moveSelection(delta: Int) {
        if (fixes.isEmpty()) return
        val size = fixes.size
        selectedIndex = ((selectedIndex + delta) % size + size) % size
    }

    fun selected(): UiTextQuickFix? = fixes.getOrNull(selectedIndex)

    fun request(state: TextFieldState, provider: UiIntentionActionsProvider) {
        val context = UiCompletionContext(state.text, state.caret)
        requestedContext = context
        val version = ++requestVersion
        provider.request(context) { actions ->
            if (version != requestVersion || state.text != context.text || state.caret != context.caret) return@request
            val selected = selected()
            fixes = (fixes + actions).distinct()
            anchor = context.caret
            selectedIndex = fixes.indexOf(selected).coerceAtLeast(0)
            if (fixes.isEmpty()) requestedContext = null
        }
    }

    fun onFrame(state: TextFieldState) {
        val context = requestedContext ?: return
        if (state.text != context.text || state.caret != context.caret || state.readOnly) close()
    }
}

/** The fixes offered at [caret], in diagnostic order and without duplicate titles. */
internal fun quickFixesAt(diagnostics: List<UiTextDiagnostic>, caret: Int): List<UiTextQuickFix> {
    if (diagnostics.isEmpty()) return emptyList()
    return diagnostics
        .filter { it.fixes.isNotEmpty() && caret >= it.start && caret <= it.end }
        .flatMap { it.fixes }
        .distinctBy { it.title }
}

/** Alt+Enter requests intentions; Ctrl+Space remains the explicit completion shortcut. */
internal fun handleEditableFieldQuickFixKey(
    state: TextFieldState,
    input: UiKeyInput,
    quickFix: EditableFieldQuickFixState,
    diagnostics: List<UiTextDiagnostic>,
    intentions: UiIntentionActionsProvider? = null,
): Boolean {
    quickFix.onFrame(state)
    if (input.key == GLFW.GLFW_KEY_ESCAPE) {
        val hadRequest = quickFix.active || quickFix.pending
        quickFix.close()
        if (hadRequest) return true
    }
    if (quickFix.active) {
        when (input.key) {
            GLFW.GLFW_KEY_UP -> {
                quickFix.moveSelection(-1)
                return true
            }

            GLFW.GLFW_KEY_DOWN -> {
                quickFix.moveSelection(1)
                return true
            }

            GLFW.GLFW_KEY_ESCAPE -> {
                quickFix.close()
                return true
            }

            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> {
                val fix = quickFix.selected()
                quickFix.close()
                if (fix != null) applyEditableFieldQuickFix(state, fix)
                return true
            }

            else -> {
                quickFix.close()
                return false
            }
        }
    }
    if (state.readOnly) return false
    if (!input.alt || input.key != GLFW.GLFW_KEY_ENTER && input.key != GLFW.GLFW_KEY_KP_ENTER) return false
    val fixes = quickFixesAt(diagnostics, state.caret)
    if (fixes.isEmpty() && intentions == null) return true
    quickFix.open(state, fixes)
    if (intentions != null) quickFix.request(state, intentions)
    return true
}

/** Applies all edits atomically, preserving carets and leaving a single undo step. */
internal fun applyEditableFieldQuickFix(state: TextFieldState, fix: UiTextQuickFix): Boolean {
    val text = state.text
    val edits = fix.edits.sortedWith(compareBy({ it.start }, { it.end }))
    if (edits.isEmpty() || edits.any { it.start < 0 || it.end < it.start || it.end > text.length }) return false
    if (edits.zipWithNext().any { (left, right) -> right.start < left.end }) return false
    val next = buildString {
        var cursor = 0
        for (edit in edits) {
            append(text, cursor, edit.start)
            append(edit.replacement)
            cursor = edit.end
        }
        append(text, cursor, text.length)
    }
    fun mapOffset(offset: Int): Int {
        var shift = 0
        for (edit in edits) {
            if (offset < edit.start) break
            if (offset <= edit.end) return edit.start + shift + edit.replacement.length
            shift += edit.replacement.length - (edit.end - edit.start)
        }
        return offset + shift
    }
    return state.applyEdit(next, state.caretRanges.map { UiTextCaret(mapOffset(it.position)) })
}
@Composable
internal fun EditableFieldQuickFixPopup(
    quickFix: EditableFieldQuickFixState,
    layout: EditableFieldLayout,
    scrollState: UiScrollHandle,
    contentOffsetX: Float = 0f,
    onApply: (UiTextQuickFix) -> Unit,
) {
    val fixes = quickFix.fixes
    if (fixes.isEmpty()) return
    val viewport = scrollState.viewport
    if (viewport.width <= 0f || viewport.height <= 0f) return

    val fontSize = layout.fontSize
    val fontFamily = layout.fontFamily
    val caret = layout.caretAt(quickFix.anchor)
    val width = (fixes.maxOf { UiTextLayouter.measureTextWidth(it.title, fontSize, fontFamily) } + QuickFixRowChrome)
        .coerceIn(
            minOf(QuickFixMinWidth, viewport.width),
            (viewport.width - QuickFixViewportMargin * 2f).coerceAtLeast(1f),
        )
    val x = (contentOffsetX + caret.x - scrollState.offsetX).coerceIn(
        QuickFixViewportMargin,
        (viewport.width - width - QuickFixViewportMargin).coerceAtLeast(QuickFixViewportMargin),
    )
    val y = (caret.y - scrollState.offsetY + fontSize + QuickFixAnchorGap)
        .coerceAtMost((viewport.height - QuickFixViewportMargin).coerceAtLeast(0f))

    val at = scrollState.toScreen(x, y)
    Popup(
        anchorBounds = UiRect(at.x, at.y, 0f, 0f),
        alignment = UiPopupAlignment(anchorVertical = UiAlign.START),
        id = "editable-text-field-quick-fix",
        tags = listOf("editable-text-field-quick-fix-popup", "ide-quick-fix-popup"),
        modifier = Modifier.size(width.px, UiLength.Fit).fontSize(fontSize)
            .let { base -> fontFamily?.let { base.fontFamily(it) } ?: base }
            .input(clickable = true, hoverable = true),
        dismissOnOutside = true,
        onDismiss = quickFix::close,
    ) {
        fixes.forEachIndexed { index, fix ->
            key(index) {
                Row(
                    id = "editable-text-field-quick-fix-$index",
                    tags = listOf("ide-quick-fix-row", if (index == quickFix.selectedIndex) "selected" else "idle"),
                    modifier = Modifier.input(clickable = true, hoverable = true)
                        .cursor(UiCursorShape.HAND)
                        .alignItems(vertical = UiAlign.CENTER)
                        .onClick { event ->
                            onApply(fix)
                            event.consume()
                        },
                ) {
                    Text(fix.title, tags = listOf("ide-quick-fix-label"))
                }
            }
        }
    }
}

private const val QuickFixMinWidth = 170f
private const val QuickFixRowChrome = 26f
private const val QuickFixAnchorGap = 4f
private const val QuickFixViewportMargin = 4f
