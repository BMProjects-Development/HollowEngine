package ru.hollowhorizon.hollowengine.client.ui.docking

import androidx.compose.runtime.compositionLocalOf
import ru.hollowhorizon.hollowengine.client.ui.Modifier

data class DockItem(
    val id: String,
    val title: String,
    val icon: String? = null,
    val closable: Boolean = true,
    val minWidth: Float = 48f,
    val minHeight: Float = 32f,
    val dirty: Boolean = false,
    val pinnable: Boolean = false,
    val titleInToolbar: Boolean = false,
)

/** Which edge of the dock space a pinned item's stripe button sits on. */
enum class DockSide {
    LEFT,
    RIGHT;

    val placement: DockPlacement get() = if (this == LEFT) DockPlacement.LEFT else DockPlacement.RIGHT

    val opposite: DockSide get() = if (this == LEFT) RIGHT else LEFT
}

/**
 * Which part of a stripe a tool window sits in.
 *
 * [TOP] opens beside the editor, above the bottom area. [SPLIT] opens in the same side panel, under
 * the [TOP] window, so the two show at once with a splitter between them. [BOTTOM] opens into the
 * area under the editor and the side panels, the full width between the stripes.
 */
enum class DockStripeGroup {
    TOP,
    SPLIT,
    BOTTOM,
}

/** Where on the stripes a tool window is parked; one window per anchor is open at a time. */
data class DockAnchor(val side: DockSide, val group: DockStripeGroup = DockStripeGroup.TOP) {
    companion object {
        val all: List<DockAnchor> = DockSide.entries.flatMap { side ->
            DockStripeGroup.entries.map { group -> DockAnchor(side, group) }
        }
    }
}

data class DockPinnedItem(
    val item: DockItem,
    val side: DockSide,
    val width: Float = DefaultPinnedWidth,
    val group: DockStripeGroup = DockStripeGroup.TOP,
) {
    val anchor: DockAnchor get() = DockAnchor(side, group)
}

/** Width a tool window's panel takes the first time it is pinned. */
const val DefaultPinnedWidth = 260f
const val DefaultBottomHeight = 220f
internal const val DefaultFloatingOffset = 32f
internal const val DefaultFloatingWidth = 320f
internal const val DefaultFloatingHeight = 220f

sealed interface DockNode {
    val id: String

    data class Stack(
        override val id: String,
        val items: List<DockItem>,
        val selectedItemId: String = items.firstOrNull()?.id.orEmpty(),
    ) : DockNode {
        val selectedItem: DockItem?
            get() = items.firstOrNull { it.id == selectedItemId } ?: items.firstOrNull()
    }

    data class Split(
        override val id: String,
        val orientation: DockOrientation,
        val first: DockNode,
        val second: DockNode,
        val fraction: Float = 0.5f,
    ) : DockNode
}

data class FloatingDockWindow(
    val id: String,
    val stack: DockNode.Stack,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val dragKey: String? = null,
    val detached: Boolean = false,
)

data class DockTabDragState(
    val stackId: String,
    val itemId: String,
    val pointerX: Float,
    val grabX: Float,
    val layouts: List<DockTabLayout> = emptyList(),
)

/** A stripe button in the hand: the pointer's place along its stripe, and how far down the button it took hold. */
data class DockStripeDrag(
    val itemId: String,
    val along: Float,
    val grab: Float,
)

data class DockTabGrabState(
    val stackId: String,
    val itemId: String,
    val x: Float,
    val y: Float,
)

/**
 * Where a tab used to sit before the reorder that displaced it, and which reorder that was.
 * The tab starts its slide from [offset]; [revision] tells a later reorder from the one the
 * tab is already animating away from.
 */
data class DockTabSwap(
    val offset: Float,
    val revision: Long,
)

data class DockTabLayout(
    val itemId: String,
    val left: Float,
    val width: Float,
    val outerLeft: Float,
    val outerWidth: Float,
) {
    val midpoint: Float get() = left + width * 0.5f
}

data class DockWindowDragStart(
    val windowId: String,
    val created: Boolean,
)

enum class DockResizeEdge {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

internal data class DockRemoval(
    val root: DockNode?,
    val item: DockItem?,
)

internal fun DockNode.findNode(nodeId: String): DockNode? {
    if (id == nodeId) return this
    return when (this) {
        is DockNode.Stack -> null
        is DockNode.Split -> first.findNode(nodeId) ?: second.findNode(nodeId)
    }
}

internal fun DockNode.findStackWithItem(itemId: String): DockNode.Stack? {
    return when (this) {
        is DockNode.Stack -> takeIf { stack -> stack.items.any { it.id == itemId } }
        is DockNode.Split -> first.findStackWithItem(itemId) ?: second.findStackWithItem(itemId)
    }
}

internal fun DockNode.findSplitSeparating(firstItemId: String, secondItemId: String): DockNode.Split? {
    if (this is DockNode.Stack) return null
    this as DockNode.Split

    val firstHasFirst = first.containsItem(firstItemId)
    val firstHasSecond = first.containsItem(secondItemId)
    if (firstHasFirst && firstHasSecond) return first.findSplitSeparating(firstItemId, secondItemId)

    val secondHasFirst = second.containsItem(firstItemId)
    val secondHasSecond = second.containsItem(secondItemId)
    if (secondHasFirst && secondHasSecond) return second.findSplitSeparating(firstItemId, secondItemId)

    return takeIf { (firstHasFirst && secondHasSecond) || (firstHasSecond && secondHasFirst) }
}

internal fun DockNode.containsItem(itemId: String): Boolean {
    return findStackWithItem(itemId) != null
}

internal fun DockNode.findItem(itemId: String): DockItem? {
    return findStackWithItem(itemId)?.items?.firstOrNull { it.id == itemId }
}

internal fun DockNode.removeItem(itemId: String): DockRemoval {
    return when (this) {
        is DockNode.Stack -> {
            val removed = items.firstOrNull { it.id == itemId } ?: return DockRemoval(this, null)
            val remaining = items.filterNot { it.id == itemId }
            val selected = when {
                remaining.isEmpty() -> null
                selectedItemId != itemId -> selectedItemId
                else -> remaining.first().id
            }
            DockRemoval(remaining.takeIf { it.isNotEmpty() }
                ?.let { copy(items = it, selectedItemId = selected.orEmpty()) }, removed)
        }

        is DockNode.Split -> {
            val firstRemoval = first.removeItem(itemId)
            if (firstRemoval.item != null) {
                return DockRemoval(copyWithChildren(firstRemoval.root, second), firstRemoval.item)
            }
            val secondRemoval = second.removeItem(itemId)
            if (secondRemoval.item != null) {
                return DockRemoval(copyWithChildren(first, secondRemoval.root), secondRemoval.item)
            }
            DockRemoval(this, null)
        }
    }
}

internal fun DockNode.select(itemId: String): DockNode {
    return when (this) {
        is DockNode.Stack -> {
            if (items.none { it.id == itemId }) this else copy(selectedItemId = itemId)
        }

        is DockNode.Split -> copy(first = first.select(itemId), second = second.select(itemId))
    }
}

internal fun DockNode.reorderTab(stackId: String, itemId: String, targetIndex: Int): DockNode {
    return when (this) {
        is DockNode.Stack -> {
            if (id != stackId) return this
            val currentIndex = items.indexOfFirst { it.id == itemId }
            if (currentIndex < 0) return this
            val reordered = items.toMutableList()
            val item = reordered.removeAt(currentIndex)
            reordered.add(targetIndex.coerceIn(0, reordered.size), item)
            copy(items = reordered)
        }

        is DockNode.Split -> copy(
            first = first.reorderTab(stackId, itemId, targetIndex),
            second = second.reorderTab(stackId, itemId, targetIndex)
        )
    }
}

internal fun DockNode.insert(node: DockNode, target: DockTarget, ids: DockIdGenerator): DockNode {
    if (target.anchorId == null) return insertAtRoot(node, target, ids)
    val inserted = replace(target.anchorId) { anchor -> anchor.insertAtAnchor(node, target, ids) }
    return inserted ?: insertAtRoot(node, target, ids)
}

private fun DockNode.insertAtRoot(node: DockNode, target: DockTarget, ids: DockIdGenerator): DockNode {
    if (target.placement == DockPlacement.CENTER && this is DockNode.Stack && node is DockNode.Stack) {
        return merge(node.items, target.tabIndex)
    }
    return splitWith(node, target.placement, ids)
}

private fun DockNode.insertAtAnchor(node: DockNode, target: DockTarget, ids: DockIdGenerator): DockNode {
    if (target.placement == DockPlacement.CENTER && this is DockNode.Stack && node is DockNode.Stack) {
        return merge(node.items, target.tabIndex)
    }
    return splitWith(node, target.placement, ids)
}

private fun DockNode.replace(nodeId: String, transform: (DockNode) -> DockNode): DockNode? {
    if (id == nodeId) return transform(this)
    return when (this) {
        is DockNode.Stack -> null
        is DockNode.Split -> {
            val nextFirst = first.replace(nodeId, transform)
            if (nextFirst != null) return copy(first = nextFirst)
            val nextSecond = second.replace(nodeId, transform)
            nextSecond?.let { copy(second = it) }
        }
    }
}

internal fun DockNode.Stack.merge(newItems: List<DockItem>, tabIndex: Int?): DockNode.Stack {
    val uniqueItems = newItems.filter { item -> items.none { it.id == item.id } }
    if (uniqueItems.isEmpty()) return this
    val insertionIndex = tabIndex?.coerceIn(0, items.size) ?: items.size
    val merged = items.toMutableList()
    merged.addAll(insertionIndex, uniqueItems)
    return copy(items = merged, selectedItemId = uniqueItems.last().id)
}

private fun DockNode.splitWith(node: DockNode, placement: DockPlacement, ids: DockIdGenerator): DockNode {
    if (placement == DockPlacement.CENTER) {
        return when {
            this is DockNode.Stack && node is DockNode.Stack -> merge(node.items, null)
            else -> DockNode.Split(ids.nextSplitId(), DockOrientation.HORIZONTAL, this, node)
        }
    }
    val orientation = when (placement) {
        DockPlacement.LEFT,
        DockPlacement.RIGHT,
            -> DockOrientation.HORIZONTAL

        DockPlacement.TOP,
        DockPlacement.BOTTOM,
            -> DockOrientation.VERTICAL
    }
    return when (placement) {
        DockPlacement.LEFT,
        DockPlacement.TOP,
            -> DockNode.Split(ids.nextSplitId(), orientation, node, this)

        DockPlacement.RIGHT,
        DockPlacement.BOTTOM,
            -> DockNode.Split(ids.nextSplitId(), orientation, this, node)
    }
}

private fun DockNode.Split.copyWithChildren(first: DockNode?, second: DockNode?): DockNode? {
    return when {
        first == null -> second
        second == null -> first
        else -> copy(first = first, second = second)
    }
}

internal class DockIdGenerator {
    private var nextStack = 1
    private var nextSplit = 1
    private var nextWindow = 1

    fun nextStackId(): String = "dock-stack-${nextStack++}"

    fun nextSplitId(): String = "dock-split-${nextSplit++}"

    fun nextWindowId(): String = "dock-window-${nextWindow++}"
}

/**
 * The title a parked window is expected to show itself, when it asked for [DockItem.titleInToolbar].
 */
class DockPanelTitle(
    val title: String,
    val icon: String?,
    val headerId: String,
    val dragHandle: Modifier,
)

/** Set for the content of a parked window that draws its own title; null everywhere else. */
val LocalDockPanelTitle = compositionLocalOf<DockPanelTitle?> { null }
