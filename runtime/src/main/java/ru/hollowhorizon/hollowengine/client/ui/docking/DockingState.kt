package ru.hollowhorizon.hollowengine.client.ui.docking

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.UiEvent

private const val MinSplitFraction = 0.1f
private const val MaxSplitFraction = 0.9f

class DockingState {
    private val ids = DockIdGenerator()

    var onTabContextMenu: ((DockItem, UiEvent) -> Unit)? = null

    var root: DockNode? by mutableStateOf(null)
        private set

    val floatingWindows = mutableStateListOf<FloatingDockWindow>()

    var focusedItemId: String? by mutableStateOf(null)
        internal set

    var draggedWindowId: String? by mutableStateOf(null)
        private set

    var previewTarget: DockTarget? by mutableStateOf(null)
        private set

    var tabDrag: DockTabDragState? by mutableStateOf(null)
        private set

    /** Tool windows parked on the side stripes, in the order their buttons appear. */
    val pinnedItems = mutableStateListOf<DockPinnedItem>()

    /** The item each stripe half currently has open, by where it is parked. */
    internal val expandedByAnchor = mutableStateMapOf<DockAnchor, String>()

    /** Whether the stripes are drawn at all. Hidden, the editor runs edge to edge. */
    var stripesVisible: Boolean by mutableStateOf(true)

    /** Height of the area under the editor and the side panels that the bottom halves open into. */
    var bottomHeight: Float by mutableStateOf(DefaultBottomHeight)
        internal set

    /** How much of the bottom area the left stripe's window takes when both bottom halves are open. */
    var bottomFraction: Float by mutableStateOf(0.5f)
        internal set

    /** How much of a side panel its top window takes when a split window is open under it. */
    internal val sideSplitFractions = mutableStateMapOf<DockSide, Float>()

    /** The stripe button being dragged, if any. */
    var stripeDrag: DockStripeDrag? by mutableStateOf(null)
        internal set

    /** Stripe buttons a reorder displaced, by how far they still sit from their new place; see [DockTabSwap]. */
    internal val stripeSwapOffsets = mutableStateMapOf<String, DockTabSwap>()
    private var stripeSwapRevision = 0L

    internal fun recordStripeSwaps(offsets: Map<String, Float>) {
        if (offsets.isEmpty()) return
        val revision = ++stripeSwapRevision
        offsets.forEach { (itemId, offset) -> stripeSwapOffsets[itemId] = DockTabSwap(offset, revision) }
    }

    internal fun clearStripeSwap(itemId: String, revision: Long) {
        if (stripeSwapOffsets[itemId]?.revision == revision) stripeSwapOffsets.remove(itemId)
    }

    private var tabGrab: DockTabGrabState? by mutableStateOf(null)
    private val tabSwapOffsets = mutableStateMapOf<String, DockTabSwap>()
    private var tabSwapRevision = 0L
    private val tabLayouts = mutableMapOf<String, List<DockTabLayout>>()

    fun open(item: DockItem, target: DockTarget = DockTarget.Root) {
        if (contains(item.id)) {
            focus(item.id)
            return
        }
        val node = DockNode.Stack(ids.nextStackId(), listOf(item), item.id)
        root = root?.insert(node, defaultStackTarget(target), ids) ?: node
        focus(item.id)
    }

    fun openFloating(
        item: DockItem,
        x: Float = 32f,
        y: Float = 32f,
        width: Float = 320f,
        height: Float = 220f,
    ) {
        if (contains(item.id)) {
            focus(item.id)
            return
        }
        val stack = DockNode.Stack(ids.nextStackId(), listOf(item), item.id)
        floatingWindows += FloatingDockWindow(ids.nextWindowId(), stack, x, y, width, height)
        focus(item.id)
    }

    fun newStack(items: List<DockItem>, selectedItemId: String? = null): DockNode.Stack? {
        if (items.isEmpty()) return null
        val selected = selectedItemId?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
        return DockNode.Stack(ids.nextStackId(), items, selected)
    }

    fun newSplit(
        orientation: DockOrientation,
        first: DockNode,
        second: DockNode,
        fraction: Float = 0.5f,
    ): DockNode.Split = DockNode.Split(
        ids.nextSplitId(), orientation, first, second, fraction.coerceIn(MinSplitFraction, MaxSplitFraction),
    )

    fun newWindow(stack: DockNode.Stack, x: Float, y: Float, width: Float, height: Float): FloatingDockWindow =
        FloatingDockWindow(ids.nextWindowId(), stack, x, y, width, height)

    /**
     * Replaces everything on screen at once. This is how a layout arrives whole, the default one,
     * or the one the last session left behind.
     */
    fun applyLayout(
        root: DockNode?,
        floating: List<FloatingDockWindow> = emptyList(),
        focused: String? = null,
    ) {
        this.root = root
        floatingWindows.clear()
        floatingWindows += floating
        pinnedItems.clear()
        expandedByAnchor.clear()
        focusedItemId = focused?.takeIf(::contains) ?: firstItemId()
        focusedItemId?.let { this.root = this.root?.select(it) }
    }

    fun close(itemId: String): Boolean {
        val pinnedIndex = pinnedItems.indexOfFirst { it.item.id == itemId }
        if (pinnedIndex >= 0) {
            val pinned = pinnedItems.removeAt(pinnedIndex)
            if (expandedByAnchor[pinned.anchor] == itemId) expandedByAnchor.remove(pinned.anchor)
            if (focusedItemId == itemId) focusedItemId = firstItemId()
            return true
        }

        val dockedRemoval = root?.removeItem(itemId)
        if (dockedRemoval?.item != null) {
            root = dockedRemoval.root
            if (focusedItemId == itemId) focusedItemId = firstItemId()
            return true
        }

        val index = floatingWindows.indexOfFirst { it.stack.items.any { item -> item.id == itemId } }
        if (index < 0) return false
        val window = floatingWindows[index]
        val remaining = window.stack.items.filterNot { it.id == itemId }
        if (remaining.isEmpty()) {
            floatingWindows.removeAt(index)
        } else {
            val selected =
                if (window.stack.selectedItemId == itemId) remaining.first().id else window.stack.selectedItemId
            floatingWindows[index] =
                window.copy(stack = window.stack.copy(items = remaining, selectedItemId = selected))
        }
        if (focusedItemId == itemId) focusedItemId = firstItemId()
        return true
    }

    fun closeFocused(): Boolean {
        val item = focusedItem() ?: return false
        if (!item.closable) return false
        return close(item.id)
    }

    fun focus(itemId: String): Boolean {
        if (isPinned(itemId)) return expand(itemId)
        if (!contains(itemId)) return false
        focusedItemId = itemId
        root = root?.select(itemId)
        val index = floatingWindows.indexOfFirst { it.stack.items.any { item -> item.id == itemId } }
        if (index >= 0) {
            val window = floatingWindows.removeAt(index)
            floatingWindows += window.copy(stack = window.stack.copy(selectedItemId = itemId))
        }
        return true
    }

    fun focusContent(contentNodeId: String): Boolean {
        if (contentNodeId.startsWith(PinnedContentPrefix)) {
            return expand(contentNodeId.removePrefix(PinnedContentPrefix).removeSuffix("-content"))
        }
        val containerId = contentNodeId.removeSuffix("-content")
        val stack = findStack(containerId)
            ?: floatingWindows.firstOrNull { it.id == containerId }?.stack
            ?: return false
        return stack.selectedItem?.let { focus(it.id) } ?: false
    }

    fun select(itemId: String): Boolean = focus(itemId)

    /** Whether the window holding [itemId] floats over the dock instead of being docked in it. */
    fun isFloating(itemId: String): Boolean =
        floatingWindows.any { window -> window.stack.items.any { it.id == itemId } }

    /** Leaves every window unfocused, for when the focus has gone somewhere outside the dock. */
    fun unfocus() {
        focusedItemId = null
    }

    fun dock(itemId: String, target: DockTarget): Boolean {
        val removal = removeItemForDock(itemId) ?: return false
        val node = DockNode.Stack(ids.nextStackId(), listOf(removal), removal.id)
        root = root?.insert(node, defaultStackTarget(target), ids) ?: node
        focus(itemId)
        return true
    }

    fun dockWindow(windowId: String, target: DockTarget): Boolean {
        val index = floatingWindows.indexOfFirst { it.id == windowId }
        if (index < 0) return false
        val window = floatingWindows.removeAt(index)
        root = root?.insert(window.stack, defaultStackTarget(target), ids) ?: window.stack
        focus(window.stack.selectedItem?.id ?: window.stack.items.first().id)
        return true
    }

    fun beginDraggingTab(
        itemId: String,
        x: Float,
        y: Float,
        width: Float = 320f,
        height: Float = 220f,
    ): DockWindowDragStart? {
        val existingWindow = floatingWindows.firstOrNull { window -> window.stack.items.any { it.id == itemId } }
        if (existingWindow != null) {
            draggedWindowId = existingWindow.id
            return DockWindowDragStart(existingWindow.id, created = false)
        }
        val item = removeItemForDock(itemId) ?: return null
        val window = FloatingDockWindow(
            id = ids.nextWindowId(),
            stack = DockNode.Stack(ids.nextStackId(), listOf(item), item.id),
            x = x,
            y = y,
            width = width.coerceAtLeast(item.minWidth),
            height = height.coerceAtLeast(item.minHeight),
            dragKey = tabNodeId(item.id),
        )
        floatingWindows += window
        draggedWindowId = window.id
        focus(itemId)
        return DockWindowDragStart(window.id, created = true)
    }

    fun moveFloating(windowId: String, deltaX: Float, deltaY: Float): Boolean {
        val index = floatingWindows.indexOfFirst { it.id == windowId }
        if (index < 0) return false
        val window = floatingWindows[index]
        floatingWindows[index] = window.copy(x = window.x + deltaX, y = window.y + deltaY)
        return true
    }

    fun startDraggingWindow(windowId: String) {
        draggedWindowId = windowId
    }

    fun previewDock(target: DockTarget?) {
        previewTarget = target
    }

    fun finishDraggingWindow() {
        val windowId = draggedWindowId
        draggedWindowId = null
        previewTarget = null
        finishTabDrag()
        if (windowId != null) {
            val index = floatingWindows.indexOfFirst { it.id == windowId }
            if (index >= 0) floatingWindows[index] = floatingWindows[index].copy(dragKey = null).keptInSpace()
        }
    }

    /** The dock space's size from its last layout; floating windows are kept inside it. */
    private var spaceWidth = 0f
    private var spaceHeight = 0f

    /**
     * Records the dock space's size and pulls back any window the change left outside it; a window
     * in the hand is left alone and settled when it is dropped.
     */
    internal fun updateSpaceSize(width: Float, height: Float) {
        if (width == spaceWidth && height == spaceHeight) return
        spaceWidth = width
        spaceHeight = height
        for (index in floatingWindows.indices) {
            val window = floatingWindows[index]
            if (window.id == draggedWindowId) continue
            val kept = window.keptInSpace()
            if (kept != window) floatingWindows[index] = kept
        }
    }

    /** Moved, never resized, to lie inside the space; a window wider than it keeps its left edge in. */
    internal fun FloatingDockWindow.keptInSpace(): FloatingDockWindow {
        if (spaceWidth <= 0f || spaceHeight <= 0f) return this
        return copy(
            x = x.coerceIn(0f, (spaceWidth - width).coerceAtLeast(0f)),
            y = y.coerceIn(0f, (spaceHeight - height).coerceAtLeast(0f)),
        )
    }

    fun dockDraggedWindow(target: DockTarget): Boolean {
        val windowId = draggedWindowId ?: return false
        val docked = dockWindow(windowId, target)
        finishDraggingWindow()
        return docked
    }

    fun resizeFloatingFrom(
        windowId: String,
        edge: DockResizeEdge,
        start: FloatingDockWindow,
        deltaX: Float,
        deltaY: Float,
        minWidth: Float = 80f,
        minHeight: Float = 60f,
    ): Boolean {
        val index = floatingWindows.indexOfFirst { it.id == windowId }
        if (index < 0) return false
        var nextX = start.x
        var nextY = start.y
        var nextWidth = start.width
        var nextHeight = start.height

        if (edge.resizesLeft) {
            val applied = deltaX.coerceAtMost(start.width - minWidth)
            nextX += applied
            nextWidth -= applied
        }
        if (edge.resizesRight) {
            nextWidth = (nextWidth + deltaX).coerceAtLeast(minWidth)
        }
        if (edge.resizesTop) {
            val applied = deltaY.coerceAtMost(start.height - minHeight)
            nextY += applied
            nextHeight -= applied
        }
        if (edge.resizesBottom) {
            nextHeight = (nextHeight + deltaY).coerceAtLeast(minHeight)
        }

        val current = floatingWindows[index]
        val next = current.copy(x = nextX, y = nextY, width = nextWidth, height = nextHeight)
        if (next != current) floatingWindows[index] = next
        return true
    }

    fun setSplitFraction(splitId: String, fraction: Float): Boolean {
        val current = root ?: return false
        var changed = false
        root = current.mapSplits { split ->
            if (split.id == splitId) {
                changed = true
                split.withFractionPreservingChildren(fraction.coerceIn(MinSplitFraction, MaxSplitFraction))
            } else {
                split
            }
        }
        return changed
    }

    /** Sets the share of the split branch containing [itemId] without disturbing ancestor splits. */
    fun setSplitFractionForItem(itemId: String, siblingItemId: String, fraction: Float): Boolean {
        val split = root?.findSplitSeparating(itemId, siblingItemId) ?: return false
        val itemFraction = fraction.coerceIn(MinSplitFraction, MaxSplitFraction)
        val splitFraction = if (split.first.containsItem(itemId)) itemFraction else 1f - itemFraction
        return setSplitFraction(split.id, splitFraction)
    }

    fun dragTabInBar(
        stackId: String,
        itemId: String,
        pointerX: Float,
        grabX: Float,
        tabWidth: Float? = null,
    ): Boolean {
        val stack = findStack(stackId) ?: return false
        val currentIndex = stack.items.indexOfFirst { it.id == itemId }
        if (currentIndex < 0) return false
        val previousOrder = stack.items.map { it.id }
        val currentLayouts = currentTabLayouts(stackId, previousOrder, tabWidth)
        tabDrag = DockTabDragState(stackId, itemId, pointerX, grabX, currentLayouts)
        val targetIndex = tabDragTargetIndex(
            currentIndex = currentIndex,
            order = previousOrder,
            layouts = currentLayouts,
            draggedItemId = itemId,
            draggedLeft = pointerX - grabX,
        )
        if (targetIndex == currentIndex) return false
        val changed = reorderTab(stackId, itemId, targetIndex)
        if (changed) {
            val nextOrder = findStack(stackId)?.items?.map { it.id }.orEmpty()
            val nextLayouts = reorderTabLayouts(currentLayouts, nextOrder)
            tabDrag = DockTabDragState(stackId, itemId, pointerX, grabX, nextLayouts)
            tabLayouts[stackId] = nextLayouts
            recordTabSwapOffsets(stackId, itemId, currentLayouts, nextLayouts)
        }
        return changed
    }

    internal fun updateTabLayouts(stackId: String, layouts: List<DockTabLayout>) {
        if (layouts.isEmpty()) {
            tabLayouts.remove(stackId)
        } else {
            tabLayouts[stackId] = layouts
        }
    }

    fun beginTabGrab(stackId: String, itemId: String, x: Float, y: Float) {
        tabGrab = DockTabGrabState(stackId, itemId, x, y)
    }

    fun tabGrab(stackId: String, itemId: String): DockTabGrabState? {
        return tabGrab?.takeIf { it.stackId == stackId && it.itemId == itemId }
    }

    fun finishTabDrag() {
        tabDrag = null
        tabGrab = null
    }

    fun tabDragOffset(stackId: String, itemId: String, currentIndex: Int): Float? {
        val drag = tabDrag ?: return null
        if (drag.stackId != stackId || drag.itemId != itemId) return null
        val left = drag.layouts.firstOrNull { it.itemId == itemId }?.left
            ?: (currentIndex * (drag.layouts.firstOrNull { it.itemId == itemId }?.width ?: 0f))
        return drag.pointerX - drag.grabX - left
    }

    /**
     * How far a tab still sits from where a reorder put it. The tab draws itself at its old
     * place for one frame and then drops the offset, which is what turns the reorder into a
     * slide instead of a jump.
     */
    fun tabSwapOffset(stackId: String, itemId: String): DockTabSwap? {
        return tabSwapOffsets[tabSwapOffsetKey(stackId, itemId)]
    }

    /** Drops the offset recorded by [revision]; a newer reorder keeps its own. */
    fun clearTabSwapOffset(stackId: String, itemId: String, revision: Long) {
        val key = tabSwapOffsetKey(stackId, itemId)
        if (tabSwapOffsets[key]?.revision == revision) tabSwapOffsets.remove(key)
    }

    fun reorderTab(stackId: String, itemId: String, targetIndex: Int): Boolean {
        var changed = false
        root = root?.let { node ->
            val next = node.reorderTab(stackId, itemId, targetIndex)
            changed = next != node
            next
        }
        for (index in floatingWindows.indices) {
            val window = floatingWindows[index]
            if (window.stack.id != stackId) continue
            val nextStack = window.stack.reorderTab(stackId, itemId, targetIndex) as DockNode.Stack
            changed = changed || nextStack != window.stack
            floatingWindows[index] = window.copy(stack = nextStack)
        }
        return changed
    }

    fun contains(itemId: String): Boolean {
        return root?.containsItem(itemId) == true || isPinned(itemId) || floatingWindows.any { window ->
            window.stack.items.any { it.id == itemId }
        }
    }

    fun focusedItem(): DockItem? {
        val focused = focusedItemId ?: return null
        return item(focused)
    }

    fun item(itemId: String): DockItem? {
        return root?.findItem(itemId)
            ?: pinnedItem(itemId)?.item
            ?: floatingWindows.firstNotNullOfOrNull { window -> window.stack.items.firstOrNull { it.id == itemId } }
    }

    fun stackIdOf(itemId: String): String? {
        return root?.findStackWithItem(itemId)?.id
            ?: floatingWindows.firstOrNull { window -> window.stack.items.any { it.id == itemId } }?.stack?.id
    }

    fun updateItem(item: DockItem): Boolean {
        var changed = false
        val pinnedIndex = pinnedItems.indexOfFirst { it.item.id == item.id }
        if (pinnedIndex >= 0 && pinnedItems[pinnedIndex].item != item) {
            pinnedItems[pinnedIndex] = pinnedItems[pinnedIndex].copy(item = item)
            changed = true
        }
        root = root?.mapStacks { stack ->
            val index = stack.items.indexOfFirst { it.id == item.id }
            if (index < 0) return@mapStacks stack
            val nextItems = stack.items.toMutableList()
            if (nextItems[index] == item) return@mapStacks stack
            nextItems[index] = item
            changed = true
            stack.copy(items = nextItems)
        }
        for (index in floatingWindows.indices) {
            val window = floatingWindows[index]
            val itemIndex = window.stack.items.indexOfFirst { it.id == item.id }
            if (itemIndex < 0) continue
            val nextItems = window.stack.items.toMutableList()
            if (nextItems[itemIndex] == item) continue
            nextItems[itemIndex] = item
            floatingWindows[index] = window.copy(stack = window.stack.copy(items = nextItems))
            changed = true
        }
        return changed
    }

    internal fun removeItemForDock(itemId: String): DockItem? {
        val dockedRemoval = root?.removeItem(itemId)
        if (dockedRemoval?.item != null) {
            root = dockedRemoval.root
            return dockedRemoval.item
        }

        val windowIndex = floatingWindows.indexOfFirst { it.stack.items.any { item -> item.id == itemId } }
        if (windowIndex < 0) return null
        val window = floatingWindows[windowIndex]
        val item = window.stack.items.first { it.id == itemId }
        val remaining = window.stack.items.filterNot { it.id == itemId }
        if (remaining.isEmpty()) {
            floatingWindows.removeAt(windowIndex)
        } else {
            floatingWindows[windowIndex] = window.copy(
                stack = window.stack.copy(
                    items = remaining,
                    selectedItemId = remaining.firstOrNull { it.id == window.stack.selectedItemId }?.id
                        ?: remaining.first().id,
                )
            )
        }
        return item
    }

    internal fun firstItemId(): String? {
        return root?.firstItemId() ?: floatingWindows.firstOrNull()?.stack?.items?.firstOrNull()?.id
    }

    private fun defaultStackTarget(target: DockTarget): DockTarget {
        if (target.anchorId != null || target.placement != DockPlacement.CENTER) return target
        val anchor = focusedItemId
            ?.let { root?.findStackWithItem(it)?.id }
            ?: root?.firstStackId()
        return anchor?.let { target.copy(anchorId = it) } ?: target
    }

    private fun findStack(stackId: String): DockNode.Stack? {
        return root?.findNode(stackId) as? DockNode.Stack
            ?: floatingWindows.firstOrNull { it.stack.id == stackId }?.stack
    }

    private fun recordTabSwapOffsets(
        stackId: String,
        draggedItemId: String,
        previousLayouts: List<DockTabLayout>,
        nextLayouts: List<DockTabLayout>,
    ) {
        val previousById = previousLayouts.associateBy { it.itemId }
        nextLayouts.forEach { nextLayout ->
            val itemId = nextLayout.itemId
            if (itemId == draggedItemId) return@forEach
            val previousLayout = previousById[itemId] ?: return@forEach
            val offset = previousLayout.left - nextLayout.left
            if (offset == 0f) return@forEach
            tabSwapOffsets[tabSwapOffsetKey(stackId, itemId)] = DockTabSwap(offset, ++tabSwapRevision)
        }
    }

    private fun currentTabLayouts(stackId: String, order: List<String>, tabWidth: Float?): List<DockTabLayout> {
        val layouts = tabLayouts[stackId]
            ?.filter { it.itemId in order }
            ?.takeIf { it.size == order.size }
            ?.let { byCurrentOrder(it, order) }
        if (layouts != null) return layouts

        val width = tabWidth ?: return emptyList()
        return order.mapIndexed { index, itemId ->
            DockTabLayout(itemId, index * width, width, index * width, width)
        }
    }

    private fun reorderTabLayouts(layouts: List<DockTabLayout>, order: List<String>): List<DockTabLayout> {
        if (layouts.isEmpty()) return layouts
        val byId = layouts.associateBy { it.itemId }
        var outerLeft = layouts.minOf { it.outerLeft }
        return order.mapNotNull { itemId ->
            val layout = byId[itemId] ?: return@mapNotNull null
            val childInset = layout.left - layout.outerLeft
            val next = layout.copy(
                left = outerLeft + childInset,
                outerLeft = outerLeft,
            )
            outerLeft += layout.outerWidth
            next
        }
    }
}

private fun tabSwapOffsetKey(stackId: String, itemId: String): String = "$stackId/$itemId"

private fun tabDragTargetIndex(
    currentIndex: Int,
    order: List<String>,
    layouts: List<DockTabLayout>,
    draggedItemId: String,
    draggedLeft: Float,
): Int {
    if (layouts.isEmpty()) return currentIndex
    val byId = layouts.associateBy { it.itemId }
    val current = byId[draggedItemId] ?: return currentIndex
    val draggedRight = draggedLeft + current.width
    if (draggedRight > current.left + current.width) {
        var targetIndex = currentIndex
        while (targetIndex < order.lastIndex) {
            val nextLayout = byId[order[targetIndex + 1]] ?: break
            if (draggedRight < nextLayout.midpoint) break
            targetIndex++
        }
        return targetIndex
    }
    if (draggedLeft < current.left) {
        var targetIndex = currentIndex
        while (targetIndex > 0) {
            val previousLayout = byId[order[targetIndex - 1]] ?: break
            if (draggedLeft > previousLayout.midpoint) break
            targetIndex--
        }
        return targetIndex
    }
    return currentIndex
}

private fun byCurrentOrder(layouts: List<DockTabLayout>, order: List<String>): List<DockTabLayout> {
    val byId = layouts.associateBy { it.itemId }
    return order.mapNotNull(byId::get)
}

private fun DockNode.Split.withFractionPreservingChildren(nextFraction: Float): DockNode.Split {
    val oldFraction = fraction
    if (oldFraction == nextFraction) return this
    return copy(
        fraction = nextFraction,
        first = first.preserveSplitPosition(
            orientation,
            oldSpan = oldFraction,
            nextSpan = nextFraction,
            startShift = 0f
        ),
        second = second.preserveSplitPosition(
            orientation = orientation,
            oldSpan = 1f - oldFraction,
            nextSpan = 1f - nextFraction,
            startShift = oldFraction - nextFraction,
        ),
    )
}

private fun DockNode.preserveSplitPosition(
    orientation: DockOrientation,
    oldSpan: Float,
    nextSpan: Float,
    startShift: Float,
): DockNode {
    if (oldSpan <= 0f || nextSpan <= 0f) return this
    if (this !is DockNode.Split) return this
    if (this.orientation != orientation) {
        return copy(
            first = first.preserveSplitPosition(orientation, oldSpan, nextSpan, startShift),
            second = second.preserveSplitPosition(orientation, oldSpan, nextSpan, startShift),
        )
    }
    val oldFraction = fraction
    val oldBoundary = startShift + oldSpan * oldFraction
    val nextFraction = (oldBoundary / nextSpan).coerceIn(MinSplitFraction, MaxSplitFraction)
    return copy(
        fraction = nextFraction,
        first = first.preserveSplitPosition(
            orientation = orientation,
            oldSpan = oldSpan * oldFraction,
            nextSpan = nextSpan * nextFraction,
            startShift = startShift,
        ),
        second = second.preserveSplitPosition(
            orientation = orientation,
            oldSpan = oldSpan * (1f - oldFraction),
            nextSpan = nextSpan * (1f - nextFraction),
            startShift = startShift + oldSpan * oldFraction - nextSpan * nextFraction,
        ),
    )
}

private val DockResizeEdge.resizesLeft: Boolean
    get() = this == DockResizeEdge.LEFT || this == DockResizeEdge.TOP_LEFT || this == DockResizeEdge.BOTTOM_LEFT

private val DockResizeEdge.resizesRight: Boolean
    get() = this == DockResizeEdge.RIGHT || this == DockResizeEdge.TOP_RIGHT || this == DockResizeEdge.BOTTOM_RIGHT

private val DockResizeEdge.resizesTop: Boolean
    get() = this == DockResizeEdge.TOP || this == DockResizeEdge.TOP_LEFT || this == DockResizeEdge.TOP_RIGHT

private val DockResizeEdge.resizesBottom: Boolean
    get() = this == DockResizeEdge.BOTTOM || this == DockResizeEdge.BOTTOM_LEFT || this == DockResizeEdge.BOTTOM_RIGHT

private fun DockNode.firstItemId(): String? {
    return when (this) {
        is DockNode.Stack -> items.firstOrNull()?.id
        is DockNode.Split -> first.firstItemId() ?: second.firstItemId()
    }
}

private fun DockNode.firstStackId(): String? {
    return when (this) {
        is DockNode.Stack -> id
        is DockNode.Split -> first.firstStackId() ?: second.firstStackId()
    }
}

private fun DockNode.mapSplits(transform: (DockNode.Split) -> DockNode.Split): DockNode {
    return when (this) {
        is DockNode.Stack -> this
        is DockNode.Split -> transform(copy(first = first.mapSplits(transform), second = second.mapSplits(transform)))
    }
}

private fun DockNode.mapStacks(transform: (DockNode.Stack) -> DockNode.Stack): DockNode {
    return when (this) {
        is DockNode.Stack -> transform(this)
        is DockNode.Split -> copy(first = first.mapStacks(transform), second = second.mapStacks(transform))
    }
}

internal fun tabNodeId(itemId: String): String = "dock-tab-$itemId"

/** The start of the id of an open parked window's content, which the id of the window follows. */
internal const val PinnedContentPrefix = "dock-pinned-"
