package ru.hollowhorizon.hollowengine.client.ui.ide.asset

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeFilterState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeView
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang

internal const val FolderIcon = "hollowengine:textures/gui/icons/folder.svg"
private const val OpenFolderIcon = "hollowengine:textures/gui/icons/folder_open.svg"
private const val ReloadIcon = "hollowengine:textures/gui/icons/reload.svg"
private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val CloseIcon = "hollowengine:textures/gui/icons/cross.svg"

@Composable
internal fun AssetManagerPanel(
    state: AssetManagerState,
    onOpenFile: (AssetResourceScope, AssetFile, ByteArray?, Boolean) -> Unit,
    onOverrideFile: (AssetResourceScope, AssetFile) -> Unit,
    onHideFile: (AssetResourceScope, AssetFile) -> Unit,
    onRestoreFile: (AssetResourceScope, AssetFile) -> Unit,
    onFocusFilter: (String) -> Unit,
) {
    val scope = state.scope
    val treeFilter = state.treeFilter
    val gridFilter = state.gridFilter

    LaunchedEffect(gridFilter.expanded) {
        if (gridFilter.expanded) onFocusFilter(gridFilter.inputId)
    }

    val minecraft = Minecraft.getInstance()
    val lifecycleRevision = when (scope) {
        AssetResourceScope.CLIENT -> AssetManagerLifecycle.clientRevision
        AssetResourceScope.SERVER -> AssetManagerLifecycle.serverRevision
    }
    val resourceManager = assetResourceManager(scope)
    val connection = minecraft.connection
    val remoteServer = scope == AssetResourceScope.SERVER && resourceManager == null && connection != null

    LaunchedEffect(scope, resourceManager, connection, state.refreshRevision, lifecycleRevision) {
        val requestedScope = scope
        val requestedManager = resourceManager
        if (remoteServer) {
            state.localLoadTokens.remove(requestedScope)
            state.localLoadKeys.remove(requestedScope)
            state.localLoading.remove(requestedScope)
            state.indexes.remove(requestedScope)
            if (
                state.remoteConnection !== connection ||
                state.remoteRefreshRevision != state.refreshRevision ||
                state.remoteLifecycleRevision != lifecycleRevision
            ) {
                RemoteServerAssetState.reset()
                state.remoteConnection = connection
                state.remoteRefreshRevision = state.refreshRevision
                state.remoteLifecycleRevision = lifecycleRevision
            }
            state.localErrors.remove(requestedScope)
            RemoteServerAssetState.requestRoot()
            return@LaunchedEffect
        }
        if (requestedManager == null) {
            state.localLoadTokens.remove(requestedScope)
            state.localLoadKeys.remove(requestedScope)
            state.indexes.remove(requestedScope)
            state.localErrors[requestedScope] = AssetManagerLang.CONNECT_TO_SERVER.lang
            state.localLoading.remove(requestedScope)
            return@LaunchedEffect
        }
        val loadKey = AssetLoadKey(requestedManager, state.refreshRevision, lifecycleRevision)
        if (state.localLoadKeys[requestedScope] == loadKey &&
            (requestedScope in state.indexes || requestedScope in state.localErrors)
        ) return@LaunchedEffect
        state.localLoadKeys[requestedScope] = loadKey
        val loadToken = Any()
        state.localLoadTokens[requestedScope] = loadToken
        state.localLoading[requestedScope] = true
        state.localErrors.remove(requestedScope)
        try {
            val loaded = withContext(Dispatchers.Default) {
                AssetIndex.load(requestedManager, requestedScope)
            }
            if (state.localLoadTokens[requestedScope] === loadToken) state.indexes[requestedScope] = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (state.localLoadTokens[requestedScope] === loadToken) {
                state.indexes.remove(requestedScope)
                val detail = failure.message ?: failure::class.simpleName.orEmpty()
                state.localErrors[requestedScope] = AssetManagerLang.INDEX_FAILED.lang(detail)
            }
        } finally {
            if (state.localLoadTokens[requestedScope] === loadToken) {
                state.localLoadTokens.remove(requestedScope)
                state.localLoading.remove(requestedScope)
            }
        }
    }

    val remoteRevision = RemoteServerAssetState.revision
    val index = if (remoteServer) {
        remember(remoteRevision) { RemoteServerAssetState.snapshot() }
    } else {
        state.indexes[scope] ?: AssetIndex.Empty
    }
    LaunchedEffect(scope, index.directoryKeys) {
        val selected = index.directory(state.selectedDirectories[scope]) ?: index.rootDirectories.firstOrNull()
        if (selected != null) {
            state.selectedDirectories[scope] = selected.key
            state.expandedDirectories[scope.expandedKey(selected.key)] = true
            if (remoteServer) RemoteServerAssetState.requestDirectory(selected)
        }
    }

    val selectedDirectory = index.directory(state.selectedDirectories[scope])
    val expandedKeys = index.visibleExpandedKeys(scope, state.expandedDirectories)
    val loading = if (remoteServer) RemoteServerAssetState.loading else state.localLoading[scope] == true
    val error = if (remoteServer) RemoteServerAssetState.error ?: state.localErrors[scope] else state.localErrors[scope]

    fun selectDirectory(directory: AssetDirectory) {
        state.selectedDirectories[scope] = directory.key
        state.selectedEntryKey = null
        if (remoteServer) RemoteServerAssetState.requestDirectory(directory)
    }

    fun openDirectory(directory: AssetDirectory) {
        directory.ancestorKeys().forEach { key ->
            state.expandedDirectories[scope.expandedKey(key)] = true
        }
        selectDirectory(directory)
    }

    fun openFile(file: AssetFile, forceText: Boolean = false) {
        val requestedScope = scope
        state.localErrors.remove(requestedScope)
        if (!remoteServer) {
            onOpenFile(requestedScope, file, null, forceText)
            return
        }
        RemoteServerAssetState.requestFile(file) { bytes, requestError ->
            if (requestError != null) {
                state.localErrors[requestedScope] = requestError
            } else {
                onOpenFile(requestedScope, file, bytes, forceText)
            }
        }
    }

    Row(
        tags = listOf("ide-panel", "asset-manager-root"),
        modifier = Modifier.style("hollowengine:ui/styles/asset-manager.hss").size(100.percent, 100.percent),
    ) {
        Column(tags = listOf("asset-sidebar"), modifier = Modifier.size(state.sidebarWidth.px, 100.percent)) {
            AssetScopeTabs(scope) { next ->
                state.scope = next
                state.selectedEntryKey = null
                state.contextMenu = null
                state.recipeFilterExpanded = false
                state.localErrors.remove(next)
            }
            UiTreeView(
                items = index.visibleDirectories(expandedKeys, treeFilter.query).map { directory ->
                    val expanded = directory.key in expandedKeys
                    UiTreeItem(
                        id = "asset-${scope.name.lowercase()}-${directory.key}",
                        label = directory.name,
                        depth = directory.depth,
                        payload = directory,
                        icon = if (expanded) OpenFolderIcon else FolderIcon,
                        hasChildren = index.hasChildDirectories(directory),
                        expanded = expanded,
                        selected = selectedDirectory == directory,
                    )
                },
                onToggle = { item ->
                    val key = scope.expandedKey(item.payload.key)
                    val expanding = state.expandedDirectories[key] != true
                    state.expandedDirectories[key] = expanding
                    if (expanding && remoteServer) RemoteServerAssetState.requestDirectory(item.payload)
                },
                onSelect = { item, event ->
                    if (!event.isLeftClick()) return@UiTreeView
                    selectDirectory(item.payload)
                    if (state.treeClicks.isDoubleClick(item.id)) {
                        val key = scope.expandedKey(item.payload.key)
                        state.expandedDirectories[key] = state.expandedDirectories[key] != true
                    }
                    event.consume()
                },
                filterState = treeFilter,
                onFilterOpened = onFocusFilter,
                scrollState = state.treeScroll(scope),
            )
        }

        AssetSidebarSplitter(state.sidebarWidth) { state.sidebarWidth = it }

        Column(
            tags = listOf("asset-content"),
            modifier = Modifier.size(0.px, 100.percent).grow(1f).focus()
                .onKeyInput(FilterShortcutPriority) { input ->
                    if (input.repeat || !HollowKeys.FIND.matches(input)) return@onKeyInput
                    gridFilter.open()
                    input.consume()
                },
        ) {
            AssetToolbar(
                scope = scope,
                directory = selectedDirectory,
                fileCount = index.files.size,
                namespaceCount = index.namespaceCount,
                loading = loading,
                remote = remoteServer,
                filter = gridFilter,
                recipeFilter = state.recipeFilter.takeIf {
                    !remoteServer && scope == AssetResourceScope.SERVER && selectedDirectory?.isRecipeDirectory() == true
                },
                recipeFilterExpanded = state.recipeFilterExpanded,
                onRecipeFilterExpandedChange = { state.recipeFilterExpanded = it },
                onRecipeFilterChange = { state.recipeFilter = it },
                onRefresh = state::refresh,
            )
            when {
                error != null -> AssetMessage(error.lang)
                loading && index.directoryKeys.isEmpty() -> AssetMessage(AssetManagerLang.LOADING.lang)
                selectedDirectory == null -> AssetMessage(AssetManagerLang.NO_RESOURCES.lang)
                else -> AssetGrid(
                    scope = scope,
                    entries = index.children(selectedDirectory).filter { entry ->
                        val query = gridFilter.query.trim()
                        val matchesText = query.isEmpty() || entry.name.contains(query, ignoreCase = true)
                        val matchesRecipeState = selectedDirectory.isRecipeDirectory().not() ||
                                remoteServer || state.recipeFilter.accepts(entry)
                        matchesText && matchesRecipeState
                    },
                    state = state.gridState(scope, selectedDirectory),
                    selectedEntryKey = state.selectedEntryKey,
                    clicks = state.gridClicks,
                    onSelect = { entry -> state.selectedEntryKey = entry.entryKey },
                    onOpen = { entry ->
                        when (entry) {
                            is AssetGridEntry.Directory -> openDirectory(entry.directory)
                            is AssetGridEntry.File -> openFile(entry.file)
                        }
                    },
                    onContext = { entry, x, y ->
                        state.selectedEntryKey = entry.entryKey
                        state.contextMenu = AssetContextMenu(entry, x, y)
                    },
                )
            }
        }

        AssetEntryContextMenu(
            menu = state.contextMenu,
            canModify = !remoteServer,
            onOpen = { entry ->
                state.contextMenu = null
                when (entry) {
                    is AssetGridEntry.Directory -> openDirectory(entry.directory)
                    is AssetGridEntry.File -> openFile(entry.file)
                }
            },
            onOpenAsText = { file ->
                state.contextMenu = null
                openFile(file, forceText = true)
            },
            onOverride = { file ->
                state.contextMenu = null
                onOverrideFile(scope, file)
                state.refresh()
            },
            onHide = { file ->
                state.contextMenu = null
                onHideFile(scope, file)
                state.refresh()
            },
            onRestore = { file ->
                state.contextMenu = null
                onRestoreFile(scope, file)
                state.refresh()
            },
            onDismiss = { state.contextMenu = null },
        )
    }
}

@Composable
private fun AssetScopeTabs(selected: AssetResourceScope, onSelect: (AssetResourceScope) -> Unit) {
    Row(tags = listOf("asset-tabs")) {
        AssetResourceScope.entries.forEach { scope ->
            Box(
                tags = if (scope == selected) listOf("asset-tab", "selected") else listOf("asset-tab"),
                modifier = Modifier.size(0.px, 17.px).grow(1f).cursor(UiCursorShape.HAND).onClick { event ->
                    if (event.isLeftClick()) onSelect(scope)
                    event.consume()
                },
            ) { Text(scope.labelKey.lang, tags = listOf("asset-tab-label")) }
        }
    }
}

@Composable
private fun AssetToolbar(
    scope: AssetResourceScope,
    directory: AssetDirectory?,
    fileCount: Int,
    namespaceCount: Int,
    loading: Boolean,
    remote: Boolean,
    filter: UiTreeFilterState,
    recipeFilter: AssetRecipeFilter?,
    recipeFilterExpanded: Boolean,
    onRecipeFilterExpandedChange: (Boolean) -> Unit,
    onRecipeFilterChange: (AssetRecipeFilter) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(tags = listOf("asset-toolbar")) {
        if (filter.expanded) {
            Image(SearchIcon, tags = listOf("asset-filter-icon"))
            TextField(
                value = filter.query,
                placeholder = AssetManagerLang.FILTER_CURRENT_FOLDER.lang,
                onChange = { filter.query = it },
                id = filter.inputId,
                tags = listOf("asset-filter-input"),
                modifier = Modifier.grow(1f).onKeyInput(FilterShortcutPriority) { input ->
                    if (input.key != GLFW.GLFW_KEY_ESCAPE) return@onKeyInput
                    filter.close()
                    input.consume()
                },
            )
            IconButton(
                CloseIcon,
                AssetManagerLang.CLOSE_FILTER.lang,
                tags = listOf("inline", "asset-filter-close"),
                onClick = filter::close,
            )
        } else {
            Text(
                directory?.let { "${scope.directory}/${it.namespace}/${it.path}".trimEnd('/') } ?: scope.directory,
                tags = listOf("asset-breadcrumb"),
                modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).align(vertical = UiAlign.CENTER).textWrap(false),
            )
            Text(
                if (remote) {
                    AssetManagerLang.REMOTE_COUNTS.lang(namespaceCount, fileCount)
                } else {
                    AssetManagerLang.LOCAL_COUNTS.lang(namespaceCount, fileCount)
                },
                tags = listOf("asset-count"),
            )
            if (recipeFilter != null) {
                UiDropdown(
                    id = "asset-recipe-filter",
                    label = recipeFilter.labelKey.lang,
                    expanded = recipeFilterExpanded,
                    onExpandedChange = onRecipeFilterExpandedChange,
                    items = AssetRecipeFilter.entries.map { option ->
                        UiDropdownItem(option.labelKey.lang) {
                            onRecipeFilterChange(option)
                            onRecipeFilterExpandedChange(false)
                        }
                    },
                    tags = listOf("asset-recipe-filter"),
                )
            }
        }
        IconButton(
            ReloadIcon,
            AssetManagerLang.REFRESH.lang,
            enabled = !loading,
            tags = if (loading) listOf("asset-refresh", "loading") else listOf("asset-refresh"),
            onClick = onRefresh,
        )
    }
}

@Composable
private fun AssetSidebarSplitter(width: Float, onWidthChange: (Float) -> Unit) {
    val dragStart = remember { floatArrayOf(width) }
    Box(
        tags = listOf("asset-splitter"),
        modifier = Modifier.size(4.px, 100.percent).input(hoverable = true, draggable = true)
            .cursor(UiCursorShape.RESIZE_HORIZONTAL).onPress { dragStart[0] = width }.onDrag { event ->
                onWidthChange((dragStart[0] + event.dragTotalX).coerceIn(MinSidebarWidth, MaxSidebarWidth))
                event.consume()
            },
    )
}

internal fun assetResourceManager(scope: AssetResourceScope): ResourceManager? {
    val minecraft = Minecraft.getInstance()
    return when (scope) {
        AssetResourceScope.CLIENT -> minecraft.resourceManager
        AssetResourceScope.SERVER -> minecraft.singleplayerServer?.resourceManager
    }
}

private fun AssetResourceScope.expandedKey(directoryKey: String): String = "$name:$directoryKey"

private fun AssetIndex.visibleExpandedKeys(
    scope: AssetResourceScope,
    expanded: Map<String, Boolean>,
): Set<String> = directoryKeys.filterTo(mutableSetOf()) { expanded[scope.expandedKey(it)] == true }

private const val MinSidebarWidth = 170f
private const val MaxSidebarWidth = 460f
private const val FilterShortcutPriority = 100
