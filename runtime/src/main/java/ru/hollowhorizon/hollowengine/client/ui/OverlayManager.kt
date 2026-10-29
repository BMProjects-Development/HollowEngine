package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.*

/**
 * One open overlay (popup) tracked by the [OverlayManager]. A [Popup] owns an entry while composed; the
 * [OverlayHost] reads [content] (and [layer] for ordering) to compose the popup out-of-line, on top of
 * everything. [key] is a stable identity so the host can `key()` each popup across list changes.
 */
class PopupEntry(val key: Any, val id: String? = null) {
    var layer by mutableStateOf(0)
    var dismissOnOutside by mutableStateOf(true)

    var modal by mutableStateOf(false)
    var animated by mutableStateOf(true)
    var visible by mutableStateOf(true)

    /**
     * The owning [Popup] has left the composition and the entry is only still here so its content can
     * animate out. It no longer takes input, and the host drops it once the animation is done.
     */
    var exiting by mutableStateOf(false)
        internal set

    var onDismiss: (() -> Unit)? = null
    var content by mutableStateOf<@Composable () -> Unit>({})
}

/**
 * Tracks the open overlays (popups) for the surface's [OverlayHost], which composes them out-of-line
 * above the content. Overlays register while composed; the host shows a full-screen dismiss catcher
 * while any dismiss-on-outside overlay is open and closes them on an outside click or Escape.
 */
class OverlayManager {
    val popups = mutableStateListOf<PopupEntry>()

    val hasDismissable: Boolean
        get() = popups.any { it.visible && !it.exiting && it.dismissOnOutside && it.onDismiss != null }

    val hasModal: Boolean
        get() = popups.any { it.visible && !it.exiting && it.modal }

    /**
     * Registers an overlay; returns its dispose handle. Animated entries stay until the host
     * finishes the exit transition; immediate entries are removed on disposal.
     */
    fun register(entry: PopupEntry): () -> Unit {
        entry.id?.let { id -> popups.removeAll { it.exiting && it.id == id } }
        entry.exiting = false
        popups += entry
        return {
            entry.exiting = true
            entry.onDismiss = null
            entry.dismissOnOutside = false
            if (!entry.animated || !entry.visible) remove(entry)
        }
    }

    /** Drops an entry whose closing animation has finished. */
    fun remove(entry: PopupEntry) {
        popups -= entry
    }

    fun dismissAll() {
        popups.toList().forEach { if (it.visible && !it.exiting && it.dismissOnOutside) it.onDismiss?.invoke() }
    }
}

val LocalOverlayManager = staticCompositionLocalOf<OverlayManager?> { null }
