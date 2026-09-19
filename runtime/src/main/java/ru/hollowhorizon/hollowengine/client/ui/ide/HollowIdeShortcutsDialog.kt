package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.LocalUiViewport
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Popup
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.maxSize
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.scrollable
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val LANG = "hollowengine.gui.ide.shortcuts"

private class ShortcutGroup(val titleKey: String, val shortcuts: List<Pair<String, String>>)

/** The bindings handled by the overlay and the project tree; keep in step with their key handlers. */
private val ShortcutGroups = listOf(
    ShortcutGroup(
        "$LANG.group.general",
        listOf(
            "Ctrl+N" to "$LANG.search",
            "Ctrl+W" to "$LANG.close_tab",
            "Ctrl+S" to "$LANG.save",
            "F3+T" to "$LANG.reload_client",
        ),
    ),
    ShortcutGroup(
        "$LANG.group.editor",
        listOf(
            "Ctrl+F" to "$LANG.find",
            "Ctrl+R" to "$LANG.replace",
            "F3 / Shift+F3" to "$LANG.next_match",
            "F4" to "$LANG.definition",
            "Ctrl+Alt+L" to "$LANG.format",
            "Ctrl+Wheel" to "$LANG.zoom",
        ),
    ),
    ShortcutGroup(
        "$LANG.group.project",
        listOf(
            "Ctrl+F" to "$LANG.filter",
            "Ctrl+C / X / V" to "$LANG.clipboard",
            "F2" to "$LANG.rename",
            "Delete" to "$LANG.delete",
            "Alt+Insert" to "$LANG.new_file",
            "Alt+Shift+Insert" to "$LANG.new_folder",
        ),
    ),
)

@Composable
internal fun HollowIdeShortcutsDialog(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = CenteredOnViewport,
        id = "ide-shortcuts-dialog-popup",
        tags = listOf("dropdown-popup", "ide-shortcuts-dialog"),
        layer = 100,
        modal = true,
        onDismiss = onDismiss,
    ) {
        Column(
            tags = listOf("ide-shortcuts-content"),
            modifier = Modifier.size(360.px, UiLength.Fit)
                .maxSize(height = (LocalUiViewport.current.height * 0.8f).px)
                .scrollable(horizontal = false),
        ) {
            Text("$LANG.title".lang, tags = listOf("ide-shortcuts-title"))
            ShortcutGroups.forEach { group ->
                Text(group.titleKey.lang, tags = listOf("ide-shortcuts-group"))
                group.shortcuts.forEach { (keys, descriptionKey) ->
                    Row(
                        tags = listOf("ide-shortcuts-row"),
                        modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(vertical = UiAlign.CENTER),
                    ) {
                        Text(descriptionKey.lang, tags = listOf("ide-shortcuts-description"), modifier = Modifier.grow(1f))
                        Text(keys, tags = listOf("ide-shortcuts-keys"))
                    }
                }
            }
        }
    }
}
