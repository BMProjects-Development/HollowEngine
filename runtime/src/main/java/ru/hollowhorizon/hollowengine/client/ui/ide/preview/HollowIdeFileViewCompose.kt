package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val SplitterWidth = 2f
private const val MinSplit = 0.15f
private const val MaxSplit = 0.85f

/** The file's tab body in its current [HollowIdeViewMode]. */
@Composable
internal fun HollowIdeFileViewBody(
    view: HollowIdeFileView,
    id: String,
    text: @Composable () -> Unit,
    preview: @Composable () -> Unit,
) {
    key(view.mode) {
        when (view.mode) {
            HollowIdeViewMode.TEXT -> text()
            HollowIdeViewMode.PREVIEW -> preview()
            HollowIdeViewMode.SPLIT -> Row(id = "$id-split", modifier = Modifier.size(100.percent, 100.percent)) {
                Box(modifier = Modifier.size(0.px, 100.percent).grow(view.split)) { text() }
                SplitDivider(view, id)
                Box(modifier = Modifier.size(0.px, 100.percent).grow(1f - view.split)) { preview() }
            }
        }
    }
}

@Composable
private fun SplitDivider(view: HollowIdeFileView, id: String) {
    val dragStart = remember(view) { floatArrayOf(view.split) }
    Box(
        id = "$id-split-divider",
        tags = listOf("ide-preview-divider"),
        modifier = Modifier.size(SplitterWidth.px, 100.percent).input(hoverable = true, draggable = true)
            .cursor(UiCursorShape.RESIZE_HORIZONTAL).onPress { dragStart[0] = view.split }.onDrag { event ->
                val width = event.parentWidth - SplitterWidth
                if (width > 0f) view.split = (dragStart[0] + event.dragTotalX / width).coerceIn(MinSplit, MaxSplit)
                event.consume()
            },
    )
}

/**
 * The mode buttons at the right end of the tab bar, as in IDEA: the text, the split, then a button per preview. While
 * split, picking another preview puts it beside the text, and picking the one there opens it alone.
 */
@Composable
internal fun HollowIdeViewModeSwitch(view: HollowIdeFileView, id: String) {
    Row(tags = listOf("ide-view-modes"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
        listOf(HollowIdeViewMode.TEXT, HollowIdeViewMode.SPLIT).forEach { mode ->
            IconButton(
                mode.icon,
                mode.tooltip.lang,
                id = "$id-view-${mode.name.lowercase()}",
                active = view.mode == mode,
                tags = listOf("ide-view-mode"),
            ) { view.mode = mode }
        }
        view.previews.forEach { preview ->
            IconButton(
                preview.icon,
                preview.title.lang,
                id = "$id-view-${preview.id}",
                active = view.preview == preview && (view.mode == HollowIdeViewMode.PREVIEW ||
                    view.mode == HollowIdeViewMode.SPLIT && view.previews.size > 1),
                tags = listOf("ide-view-mode"),
            ) {
                val swap = view.mode == HollowIdeViewMode.SPLIT && view.preview != preview
                view.preview = preview
                if (!swap) view.mode = HollowIdeViewMode.PREVIEW
            }
        }
    }
}

/** What a preview shows while the text does not read, instead of editing over it. */
@Composable
fun HollowIdePreviewError(error: Throwable?) {
    Column(tags = listOf("ide-preview-error")) {
        Text("hollowengine.gui.ide.preview.unreadable".lang, tags = listOf("ide-preview-error-title"))
        error?.let { Text(it.message ?: it.javaClass.simpleName, tags = listOf("ide-preview-error-message")) }
    }
}
