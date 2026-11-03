package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

interface UiCompletionPreview {
    @Composable
    fun Content()
}

private val PreviewAlignment = UiPopupAlignment(
    anchorHorizontal = UiAlign.START,
    anchorVertical = UiAlign.START,
    popupHorizontal = UiAlign.END,
    offsetX = -PREVIEW_GAP,
    flipsHorizontally = true,
)

private const val PREVIEW_GAP = 4f

@Composable
internal fun CompletionPreviewPopup(preview: UiCompletionPreview, anchor: UiRect) {
    Popup(
        anchorBounds = anchor,
        alignment = PreviewAlignment,
        id = "editable-text-field-completion-preview",
        tags = listOf("ide-completion-popup", "ide-completion-preview"),
        modifier = Modifier.inputTransparent(),
        dismissOnOutside = false,
        animated = false,
    ) {
        key(preview) { preview.Content() }
    }
}
