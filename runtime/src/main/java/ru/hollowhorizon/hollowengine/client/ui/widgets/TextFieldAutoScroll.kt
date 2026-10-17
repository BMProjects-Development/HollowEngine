package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle

/** Follows caret requests across editor mounts without losing the user's scroll position. */
internal class TextFieldAutoScroll {
    private var caretRevision = 0
    private var caretMovePending = false
    private var followedRevision = Int.MIN_VALUE
    private var settling = false
    private var followedViewport = UiRect.Zero
    private var followedContentWidth = Float.NaN
    private var followedContentHeight = Float.NaN

    fun follow(state: TextFieldState, layout: EditableFieldLayout, scrollState: UiScrollHandle, gutterWidth: Float) {
        val revision = state.caretVisibilityRevision
        if (caretRevision != revision) caretMovePending = true
        caretRevision = revision

        val viewport = scrollState.viewport
        if (!caretMovePending) {
            if (!settling || followedRevision != revision) return
            val unchanged = followedViewport == viewport &&
                followedContentWidth == layout.naturalWidth && followedContentHeight == layout.height
            if (unchanged) {
                settling = false
                return
            }
        }
        if (viewport.height <= 0f) return
        caretMovePending = false
        followedRevision = revision
        settling = true
        followedViewport = viewport
        followedContentWidth = layout.naturalWidth
        followedContentHeight = layout.height

        val caret = layout.caretAt(state.primaryCaret.position, state.primaryCaret.inlayAffinity)
        val margin = layout.fontSize
        val caretEndX = caret.x + TextFieldCaretWidth
        val viewportWidth = (viewport.width - gutterWidth).coerceAtLeast(0f)

        val targetY = when {
            caret.y < scrollState.offsetY + margin -> (caret.y - margin).coerceAtLeast(0f)
            caret.y + layout.fontSize > scrollState.offsetY + viewport.height - margin ->
                caret.y + layout.fontSize + margin - viewport.height
            else -> null
        }
        val targetX = when {
            state.wrap -> null
            caret.x < scrollState.offsetX + margin -> (caret.x - margin).coerceAtLeast(0f)
            caretEndX > scrollState.offsetX + viewportWidth - margin -> caretEndX + margin - viewportWidth
            else -> null
        }
        if (targetX != null || targetY != null) scrollState.scrollTo(targetX, targetY)
    }
}
