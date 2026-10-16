package ru.hollowhorizon.hollowengine.client.ui.ide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import ru.hollowhorizon.hollowengine.client.ui.docking.DefaultBottomHeight
import ru.hollowhorizon.hollowengine.client.ui.docking.DefaultPinnedWidth
import ru.hollowhorizon.hollowengine.client.ui.docking.DockAnchor
import ru.hollowhorizon.hollowengine.client.ui.docking.DockItem
import ru.hollowhorizon.hollowengine.client.ui.docking.DockNode
import ru.hollowhorizon.hollowengine.client.ui.docking.DockOrientation
import ru.hollowhorizon.hollowengine.client.ui.docking.DockPinnedItem
import ru.hollowhorizon.hollowengine.client.ui.docking.DockSide
import ru.hollowhorizon.hollowengine.client.ui.docking.DockStripeGroup
import ru.hollowhorizon.hollowengine.client.ui.docking.DockingState
import ru.hollowhorizon.hollowengine.client.ui.docking.applyPinned
import ru.hollowhorizon.hollowengine.client.ui.docking.expandedPinned
import ru.hollowhorizon.hollowengine.common.config.Config
import java.util.*
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * One dock node as the layout file keeps it.
 */
@Serializable
internal data class StoredDockNode(
    val items: List<String> = emptyList(),
    val selected: String? = null,
    val orientation: DockOrientation? = null,
    val fraction: Float = 0.5f,
    val first: StoredDockNode? = null,
    val second: StoredDockNode? = null,
)

@Serializable
internal data class StoredDockWindow(
    val node: StoredDockNode,
    val x: Float = 32f,
    val y: Float = 32f,
    val width: Float = 320f,
    val height: Float = 220f,
)

@Serializable
internal data class StoredPinnedItem(
    val id: String,
    val side: DockSide = DockSide.LEFT,
    val width: Float = DefaultPinnedWidth,
    val group: DockStripeGroup = DockStripeGroup.TOP,
    val expanded: Boolean = false,
)

/** The editor's windows as they located when it was last used. */
@Serializable
internal data class StoredDockLayout(
    val root: StoredDockNode? = null,
    val floating: List<StoredDockWindow> = emptyList(),
    val focused: String? = null,
    val pinned: List<StoredPinnedItem> = emptyList(),
    val stripesVisible: Boolean = true,
    val bottomHeight: Float = DefaultBottomHeight,
    val bottomFraction: Float = 0.5f,
    val sideSplit: Map<DockSide, Float> = emptyMap(),
)

/**
 * Keeps the arrangement of the editor's windows between sessions.
 */
internal object HollowIdeLayoutStore {
    private val LOGGER = LogManager.getLogger("HollowIde")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file get() = Config.CONFIG_DIR.resolve("hollowengine").resolve("ide-layout.json")

    fun load(): StoredDockLayout? {
        val path = file
        if (!path.exists()) return null
        return try {
            json.decodeFromString<StoredDockLayout>(path.readText())
        } catch (e: Exception) {
            LOGGER.warn("Could not read the editor layout, starting from the default one", e)
            null
        }
    }

    fun save(layout: StoredDockLayout) {
        try {
            val path = file
            path.createParentDirectories()
            path.writeText(json.encodeToString(StoredDockLayout.serializer(), layout))
        } catch (e: Exception) {
            LOGGER.warn("Could not write the editor layout", e)
        }
    }
}

internal fun DockingState.capture(): StoredDockLayout {
    val expanded = expandedPinned()
    return StoredDockLayout(
        root = root?.toStored(),
        floating = floatingWindows.map { window ->
            StoredDockWindow(window.stack.toStored(), window.x, window.y, window.width, window.height)
        },
        focused = focusedItemId,
        pinned = pinnedItems.map { pinned ->
            StoredPinnedItem(
                id = pinned.item.id,
                side = pinned.side,
                width = pinned.width,
                expanded = expanded[pinned.anchor] == pinned.item.id,
                group = pinned.group,
            )
        },
        stripesVisible = stripesVisible,
        bottomHeight = bottomHeight,
        bottomFraction = bottomFraction,
        sideSplit = sideSplitFractions.toMap(),
    )
}

private fun DockNode.toStored(): StoredDockNode = when (this) {
    is DockNode.Stack -> StoredDockNode(items = items.map { it.id }, selected = selectedItemId)
    is DockNode.Split -> StoredDockNode(
        orientation = orientation,
        fraction = fraction,
        first = first.toStored(),
        second = second.toStored(),
    )
}

internal fun DockingState.restore(layout: StoredDockLayout, resolve: (String) -> DockItem?): Boolean {
    val root = layout.root?.let { restoreNode(it, resolve) }
    val floating = layout.floating.mapNotNull { window ->
        val stack = restoreNode(window.node, resolve) as? DockNode.Stack ?: return@mapNotNull null
        newWindow(stack, window.x, window.y, window.width, window.height)
    }
    val pinned = layout.pinned.mapNotNull { stored ->
        val item = resolve(stored.id)?.takeIf { it.pinnable } ?: return@mapNotNull null
        DockPinnedItem(item, stored.side, stored.width, stored.group)
    }
    if (root == null && floating.isEmpty() && pinned.isEmpty()) return false
    applyLayout(root, floating, layout.focused)
    applyPinned(
        items = pinned,
        expanded = layout.pinned.filter { it.expanded }.associate { DockAnchor(it.side, it.group) to it.id },
        visible = layout.stripesVisible,
        bottomHeight = layout.bottomHeight,
        bottomFraction = layout.bottomFraction,
        sideSplit = layout.sideSplit,
    )
    return true
}

private fun DockingState.restoreNode(node: StoredDockNode, resolve: (String) -> DockItem?): DockNode? {
    val orientation = node.orientation
    if (orientation == null || node.first == null || node.second == null) {
        val items = node.items.distinct().mapNotNull(resolve)
        return newStack(items, node.selected)
    }
    val first = restoreNode(node.first, resolve)
    val second = restoreNode(node.second, resolve)
    if (first == null) return second
    if (second == null) return first
    return newSplit(orientation, first, second, node.fraction)
}

internal fun fileDockItemPath(itemId: String): String? {
    if (!itemId.startsWith(FileDockItemPrefix)) return null
    return try {
        String(Base64.getUrlDecoder().decode(itemId.removePrefix(FileDockItemPrefix)))
    } catch (e: IllegalArgumentException) {
        null
    }
}

private const val FileDockItemPrefix = "ide-file-"
