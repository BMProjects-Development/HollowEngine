package ru.hollowhorizon.hollowengine.client.ui.docking

import ru.hollowhorizon.hollowengine.client.ui.*

internal sealed interface DockTransferSource {
    data class Tab(val itemId: String) : DockTransferSource
    data class Window(val windowId: String) : DockTransferSource
}

internal sealed interface DockDropTarget {
    data class Pane(val target: DockTarget) : DockDropTarget
    data class Stripe(val anchor: DockAnchor) : DockDropTarget
}

internal data class DockIncomingDrop(val target: DockDropTarget?, val canPin: Boolean)

internal data class DockDropZoneMarker(val target: DockDropTarget) : Modifier
internal data class DockTabBarMarker(val stackId: String) : Modifier

internal fun HollowUiFrame.dockDropTargetAt(
    x: Float,
    y: Float,
    skipSubtree: ((UiNode) -> Boolean)? = null,
): DockDropTarget? {
    var node = UiHitTester().hitTest(root, layout, x, y, skipSubtree)?.node
    while (node != null) {
        val current = node
        for (modifier in current.modifiers.flattenModifiers()) {
            when (modifier) {
                is DockDropZoneMarker -> return modifier.target
                is DockTabBarMarker -> return tabDropTarget(current, modifier.stackId, x)
            }
        }
        node = current.layoutState.parentNode
    }
    return null
}

private fun HollowUiFrame.tabDropTarget(bar: UiNode, stackId: String, x: Float): DockDropTarget {
    val index = layout.childrenOf(bar).count { tab ->
        if (DockTags.Tab !in tab.tags) return@count false
        val bounds = layout[tab]
        bounds.inputTransform.transformX(bounds.rect.width / 2f) <= x
    }
    return DockDropTarget.Pane(DockTarget(stackId, tabIndex = index))
}

internal fun DockingState.transferItems(source: DockTransferSource): List<DockItem> = when (source) {
    is DockTransferSource.Tab -> listOfNotNull(item(source.itemId))
    is DockTransferSource.Window -> floatingWindows.firstOrNull { it.id == source.windowId }?.stack?.items.orEmpty()
}

internal fun DockingState.canTransfer(source: DockTransferSource, destination: DockDropTarget): Boolean =
    canTransfer(source, destination, transferItems(source))

private fun DockingState.canTransfer(
    source: DockTransferSource,
    destination: DockDropTarget,
    items: List<DockItem>,
): Boolean {
    if (items.isEmpty()) return false
    return when (destination) {
        is DockDropTarget.Stripe -> items.all { it.pinnable }
        is DockDropTarget.Pane -> {
            val target = destination.target
            if (source is DockTransferSource.Tab && isPinned(source.itemId)) return false
            val floating = floatingWindows.firstOrNull { it.stack.id == target.anchorId }
            if (floating != null) {
                target.placement == DockPlacement.CENTER && items.none { floating.stack.containsItem(it.id) } && (!floating.detached || items.all(
                    canDetachItem
                ))
            } else {
                val anchor = target.anchorId ?: return true
                root?.findNode(anchor) != null && (source !is DockTransferSource.Tab || stackIdOf(source.itemId) != anchor)
            }
        }
    }
}

internal fun DockingState.transfer(source: DockTransferSource, destination: DockDropTarget): Boolean {
    val items = transferItems(source)
    if (!canTransfer(source, destination, items)) return false
    return when (destination) {
        is DockDropTarget.Stripe -> {
            val (side, group) = destination.anchor
            items.forEach { pin(it.id, side, group) }
            true
        }

        is DockDropTarget.Pane -> transferToPane(source, items, destination.target)
    }
}

private fun DockingState.transferToPane(
    source: DockTransferSource,
    items: List<DockItem>,
    target: DockTarget,
): Boolean {
    val floating = floatingWindows.firstOrNull { it.stack.id == target.anchorId }
    if (floating == null) {
        return when (source) {
            is DockTransferSource.Tab -> dock(source.itemId, target)
            is DockTransferSource.Window -> dockWindow(source.windowId, target)
        }
    }
    val selected = when (source) {
        is DockTransferSource.Tab -> source.itemId
        is DockTransferSource.Window -> floatingWindows.first { it.id == source.windowId }.stack.selectedItem?.id
    }
    items.forEach { removeItemForDock(it.id) }
    val index = floatingWindows.indexOfFirst { it.id == floating.id }
    floatingWindows[index] = floating.copy(stack = floating.stack.merge(items, target.tabIndex))
    focus(selected ?: items.first().id)
    return true
}
