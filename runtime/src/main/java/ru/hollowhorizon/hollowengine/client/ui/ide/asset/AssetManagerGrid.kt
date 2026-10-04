package ru.hollowhorizon.hollowengine.client.ui.ide.asset

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDrag
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang

@Composable
internal fun AssetGrid(
    scope: AssetResourceScope,
    entries: List<AssetGridEntry>,
    state: LazyListState,
    selectedEntryKey: String?,
    clicks: AssetClickTracker,
    onSelect: (AssetGridEntry) -> Unit,
    onOpen: (AssetGridEntry) -> Unit,
    onContext: (AssetGridEntry, Float, Float) -> Unit,
) {
    if (entries.isEmpty()) {
        AssetMessage(AssetManagerLang.EMPTY_FOLDER.lang)
        return
    }
    val columns = assetGridColumnCount(state.scroll.viewport.width)
    val rows = assetGridRowCount(entries.size, columns)
    LazyColumn(
        tags = listOf("asset-grid-scroll"),
        modifier = Modifier.size(100.percent, 0.px).grow(1f),
        state = state,
        gap = GridGap,
        overscan = GridOverscanRows,
    ) {
        items(rows, key = { row -> entries[row * columns].entryKey }) { row ->
            val start = row * columns
            val end = minOf(start + columns, entries.size)
            val tags = buildList {
                add("asset-grid")
                if (end - start == columns) add("justified")
            }
            Row(
                tags = tags,
                modifier = Modifier.size(100.percent, TileHeight.px).gap(GridGap.px),
            ) {
                for (index in start until end) {
                    val entry = entries[index]
                    key(entry.entryKey) {
                        AssetTile(
                            scope = scope,
                            entry = entry,
                            selected = entry.entryKey == selectedEntryKey,
                            onClick = {
                                onSelect(entry)
                                if (clicks.isDoubleClick(entry.entryKey)) onOpen(entry)
                            },
                            onContext = { x, y -> onContext(entry, x, y) },
                        )
                    }
                }
            }
        }
    }
}

internal fun assetGridColumnCount(viewportWidth: Float): Int {
    if (viewportWidth <= 0f) return 1
    return ((viewportWidth + GridGap) / (TileWidth + GridGap)).toInt().coerceAtLeast(1)
}

internal fun assetGridRowCount(entryCount: Int, columns: Int): Int {
    if (entryCount <= 0) return 0
    val safeColumns = columns.coerceAtLeast(1)
    return (entryCount - 1) / safeColumns + 1
}

@Composable
private fun AssetTile(
    scope: AssetResourceScope,
    entry: AssetGridEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onContext: (Float, Float) -> Unit,
) {
    val dragged = remember { booleanArrayOf(false) }
    InlineWidget(
        id = "asset-tile-${entry.entryKey}",
        modifier = Modifier.size(TileWidth.px, TileHeight.px).cursor(UiCursorShape.HAND)
            .onPress { event ->
                dragged[0] = false
                if (event.isRightClick()) {
                    onContext(event.x, event.y)
                    event.consume()
                }
            }
            .dragSource(LocalDragAndDrop.current) {
                val file = (entry as? AssetGridEntry.File)?.file ?: return@dragSource null
                dragged[0] = true
                UiDragItem(
                    payload = HollowIdeFileDrag(file.projectPath(scope)),
                    icon = IconHelper.forPath(file.location.path).toString(),
                    label = file.name,
                )
            }
            .onRelease { event ->
                if (!dragged[0] && event.isLeftClick()) onClick()
                event.consume()
            },
    ) {
        Column(tags = if (selected) listOf("asset-tile", "selected") else listOf("asset-tile")) {
            Box(tags = listOf("asset-preview")) {
                when (entry) {
                    is AssetGridEntry.Directory -> Image(FolderIcon, tags = listOf("asset-folder-icon"))
                    is AssetGridEntry.File -> AssetFilePreview(scope, entry.file)
                }
            }
            Text(
                entry.name,
                tags = listOf("asset-tile-name"),
                modifier = Modifier.size(100.percent, 11.px).textWrap(false).textOverflow(UiTextOverflow.DOTS),
            )
            if (entry is AssetGridEntry.File) {
                Text(
                    entry.file.sourcePackId,
                    tags = listOf("asset-pack-name"),
                    modifier = Modifier.size(100.percent, 9.px).textWrap(false).textOverflow(UiTextOverflow.DOTS),
                )
            }
        }
    }
}

@Composable
internal fun AssetEntryContextMenu(
    menu: AssetContextMenu?,
    canModify: Boolean,
    onOpen: (AssetGridEntry) -> Unit,
    onOpenAsText: (AssetFile) -> Unit,
    onOverride: (AssetFile) -> Unit,
    onHide: (AssetFile) -> Unit,
    onRestore: (AssetFile) -> Unit,
    onDismiss: () -> Unit,
) {
    if (menu == null) return
    ContextMenu(
        id = "asset-entry-context",
        anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
        items = buildList {
            add(UiDropdownItem(AssetManagerLang.OPEN.lang) { onOpen(menu.entry) })
            val file = (menu.entry as? AssetGridEntry.File)?.file
            if (file?.location?.path?.endsWith(".json", ignoreCase = true) == true) {
                add(UiDropdownItem(AssetManagerLang.OPEN_AS_TEXT.lang) { onOpenAsText(file) })
            }
            if (file != null && canModify) {
                if (file.state != AssetResourceState.OVERRIDDEN) {
                    add(UiDropdownItem(AssetManagerLang.OVERRIDE_RESOURCE.lang) { onOverride(file) })
                } else {
                    add(UiDropdownItem(AssetManagerLang.EDIT_OVERRIDE.lang) { onOverride(file) })
                }
                if (file.state != AssetResourceState.HIDDEN) {
                    add(UiDropdownItem(AssetManagerLang.HIDE_RESOURCE.lang) { onHide(file) })
                }
                if (file.state != AssetResourceState.UNTOUCHED) {
                    add(UiDropdownItem(AssetManagerLang.RESTORE_RESOURCE.lang) { onRestore(file) })
                }
            }
        },
        onExpandedChange = { if (!it) onDismiss() },
    )
}

@Composable
private fun AssetFilePreview(scope: AssetResourceScope, file: AssetFile) {
    if (scope == AssetResourceScope.CLIENT && file.location.path.endsWith(".png", ignoreCase = true)) {
        val atlas = atlasTexturePreview(file.location)
        Image(
            atlas?.atlas?.toString() ?: file.location.toString(),
            tags = listOf("asset-texture-preview"),
            modifier = Modifier.size(100.percent, 100.percent)
                .imageFit(UiImageFit.CONTAIN)
                .let { modifier -> atlas?.let { modifier.imageUv(it.uv) } ?: modifier },
        )
    } else {
        Image(IconHelper.forPath(file.location.path).toString(), tags = listOf("asset-file-icon"))
    }
}

@Composable
internal fun AssetMessage(message: String) {
    Box(tags = listOf("asset-message"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
        Text(message, tags = listOf("asset-message-text"))
    }
}

internal val AssetGridEntry.entryKey: String
    get() = when (this) {
        is AssetGridEntry.Directory -> "directory:${directory.key}"
        is AssetGridEntry.File -> "file:${file.location}"
    }

internal data class AssetContextMenu(val entry: AssetGridEntry, val x: Float, val y: Float)

internal class AssetClickTracker {
    private var lastKey = ""
    private var lastClickAt = 0L

    fun isDoubleClick(key: String): Boolean {
        val now = System.currentTimeMillis()
        val doubleClick = key == lastKey && now - lastClickAt <= DoubleClickMillis
        lastKey = if (doubleClick) "" else key
        lastClickAt = now
        return doubleClick
    }
}

private const val TileWidth = 70f
private const val TileHeight = 86f
private const val GridGap = 5f
private const val GridOverscanRows = 2
private const val DoubleClickMillis = 350L
