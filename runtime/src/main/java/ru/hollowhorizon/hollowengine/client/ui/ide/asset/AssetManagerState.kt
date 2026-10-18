package ru.hollowhorizon.hollowengine.client.ui.ide.asset

import androidx.compose.runtime.*
import net.minecraft.server.packs.resources.ResourceManager
import ru.hollowhorizon.hollowengine.client.ui.LazyListState
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeFilterState

@Stable
internal class AssetManagerState {
    var scope by mutableStateOf(AssetResourceScope.CLIENT)
    var sidebarWidth by mutableStateOf(DefaultAssetSidebarWidth)
    var refreshRevision by mutableIntStateOf(0)
    var remoteConnection by mutableStateOf<Any?>(null)
    var remoteRefreshRevision by mutableIntStateOf(-1)
    var remoteLifecycleRevision by mutableIntStateOf(-1)
    val indexes = mutableStateMapOf<AssetResourceScope, AssetIndex>()
    val localLoading = mutableStateMapOf<AssetResourceScope, Boolean>()
    val localErrors = mutableStateMapOf<AssetResourceScope, String>()
    val localLoadTokens = mutableMapOf<AssetResourceScope, Any>()
    val localLoadKeys = mutableMapOf<AssetResourceScope, AssetLoadKey>()
    val selectedDirectories = mutableStateMapOf<AssetResourceScope, String>()
    val expandedDirectories = mutableStateMapOf<String, Boolean>()
    var selectedEntryKey by mutableStateOf<String?>(null)
    var contextMenu by mutableStateOf<AssetContextMenu?>(null)
    var recipeFilter by mutableStateOf(AssetRecipeFilter.ALL)
    var recipeFilterExpanded by mutableStateOf(false)
    val treeClicks = AssetClickTracker()
    val gridClicks = AssetClickTracker()
    val treeFilter = UiTreeFilterState("asset-tree-filter")
    val gridFilter = UiTreeFilterState("asset-grid-filter")

    private val treeScrolls = mutableMapOf<AssetResourceScope, UiScrollHandle>()
    private val gridStates = mutableMapOf<String, LazyListState>()

    fun treeScroll(scope: AssetResourceScope): UiScrollHandle =
        treeScrolls.getOrPut(scope, ::UiScrollHandle)

    fun gridState(scope: AssetResourceScope, directory: AssetDirectory): LazyListState =
        gridStates.getOrPut("${scope.name}:${directory.key}", ::LazyListState)

    fun refresh() {
        refreshRevision++
    }
}

internal data class AssetLoadKey(
    val manager: ResourceManager,
    val refreshRevision: Int,
    val lifecycleRevision: Int,
)

internal enum class AssetRecipeFilter(val labelKey: String) {
    ALL(AssetManagerLang.FILTER_ALL),
    OVERRIDDEN(AssetManagerLang.FILTER_OVERRIDDEN),
    UNTOUCHED(AssetManagerLang.FILTER_UNTOUCHED),
    HIDDEN(AssetManagerLang.FILTER_HIDDEN),
    ;

    fun accepts(entry: AssetGridEntry): Boolean {
        val file = (entry as? AssetGridEntry.File)?.file ?: return true
        return when (this) {
            ALL -> true
            OVERRIDDEN -> file.state == AssetResourceState.OVERRIDDEN
            UNTOUCHED -> file.state == AssetResourceState.UNTOUCHED
            HIDDEN -> file.state == AssetResourceState.HIDDEN
        }
    }
}

internal fun AssetDirectory.isRecipeDirectory(): Boolean =
    path == "recipe" || path.startsWith("recipe/") || path == "recipes" || path.startsWith("recipes/")

internal const val DefaultAssetSidebarWidth = 230f
