package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang

/** What a key inserted by hand covers: one part of the placement, or everything the picked thing can animate. */
enum class KeyParts(private val key: String) {
    LOCATION("location"), ROTATION("rotation"), SCALE("scale"), ALL("all");

    val label: String get() = "hollowengine.gui.ide.keying.$key".lang
}

/**
 * The menu the insert-key action opens at the pointer.
 */
@Composable
fun KeyInsertMenu(anchor: UiRect?, onPick: (KeyParts) -> Unit, onClose: () -> Unit) {
    if (anchor == null) return
    ContextMenu(
        id = "key-insert-menu",
        anchorBounds = anchor,
        items = KeyParts.entries.map { parts ->
            UiDropdownItem(parts.label, separatorBefore = parts == KeyParts.ALL) {
                onPick(parts)
                onClose()
            }
        },
        onExpandedChange = { open -> if (!open) onClose() },
    )
}
