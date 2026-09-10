package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Checkbox
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.LocalUiViewport
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Popup
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.UiAlign
import ru.hollowhorizon.hollowengine.client.ui.UiBoxMode
import ru.hollowhorizon.hollowengine.client.ui.UiCursorShape
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.UiPopupAlignment
import ru.hollowhorizon.hollowengine.client.ui.align
import ru.hollowhorizon.hollowengine.client.ui.alignItems
import ru.hollowhorizon.hollowengine.client.ui.cursor
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.maxSize
import ru.hollowhorizon.hollowengine.client.ui.onClick
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.textWrap
import ru.hollowhorizon.hollowengine.client.utils.lang

private val CenteredOnViewport = UiPopupAlignment(
    anchorHorizontal = UiAlign.CENTER,
    anchorVertical = UiAlign.CENTER,
    popupHorizontal = UiAlign.CENTER,
    popupVertical = UiAlign.CENTER,
)

@Composable
internal fun HollowIdeHideToolbarDialog(
    visible: Boolean,
    onConfirm: (doNotShowAgain: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return

    var doNotShowAgain by remember { mutableStateOf(false) }
    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = CenteredOnViewport,
        id = "ide-hide-toolbar-dialog-popup",
        tags = listOf("dropdown-popup", "ide-hide-toolbar-dialog"),
        layer = 100,
        modal = true,
        onDismiss = onCancel,
    ) {
        Column(
            tags = listOf("ide-hide-toolbar-dialog-content"),
            modifier = Modifier.size(100.percent, UiLength.Fit)
                .maxSize(width = 300.px),
        ) {
            Text(
                "hollowengine.gui.ide.popups.hide_toolbar_title".lang,
                tags = listOf("ide-hide-toolbar-dialog-title"),
            )
            Text(
                "hollowengine.gui.ide.popups.hide_toolbar_message".lang,
                tags = listOf("ide-hide-toolbar-dialog-message"),
                modifier = Modifier.size(100.percent, UiLength.Fit).textWrap(),
            )
            Row(
                tags = listOf("ide-hide-toolbar-dialog-checkbox-row"),
                modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
            ) {
                Checkbox(
                    checked = doNotShowAgain,
                    onCheckedChange = { doNotShowAgain = it },
                    id = "ide-hide-toolbar-dialog-checkbox",
                )
                Text("hollowengine.gui.ide.popups.do_not_show_again".lang)
            }
            Row(
                tags = listOf("ide-hide-toolbar-dialog-actions"),
                modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(horizontal = UiAlign.END),
            ) {
                DialogButton(
                    id = "ide-hide-toolbar-dialog-cancel",
                    label = "hollowengine.gui.ide.popups.cancel".lang,
                    onClick = onCancel,
                )
                DialogButton(
                    id = "ide-hide-toolbar-dialog-confirm",
                    label = "hollowengine.gui.ide.popups.hide".lang,
                    primary = true,
                    onClick = { onConfirm(doNotShowAgain) },
                )
            }
        }
    }
}

@Composable
private fun DialogButton(id: String, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = listOf("ide-hide-toolbar-dialog-button", if (primary) "primary" else "secondary"),
        modifier = Modifier.size(88.px, 24.px)
            .input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .onClick { event ->
                onClick()
                event.consume()
            },
    ) {
        Text(label, modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
    }
}
