package ru.hollowhorizon.hollowengine.client.ui.docking

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds

/** Edge of a stripe button. */
private const val StripeButtonSize = 22f

/** A button and the gap after it, as `.dock-stripe` lays them out. */
private const val StripeButtonPitch = 24f

/** The line between the top and the split buttons: 1px, its margins in `.dock-stripe-divider`, one gap. */
private const val StripeDividerWidth = 12f
private const val StripeDividerPitch = 9f

/** How long a panel takes to push the editor aside, and to give the room back. */
private const val PanelSlideMillis = 120L

/** Width of the handles that drag a panel's inner edge. */
internal const val PanelSplitterWidth = 3f

/** How far a stripe button has to move before a press becomes a drag rather than a click. */
private const val DragThreshold = 4f

/** How far past its stripe a button has to be dragged to come off it as a floating window. */
private const val UndockDistance = 28f

/** Where on a window just pulled off a stripe the pointer holds it: its header, near the left. */
private const val HeaderGrabX = 48f
private const val HeaderGrabY = 12f

/** How far a panel's header has to be dragged before the panel comes off: further than a click wobbles. */
private const val HeaderUndockDistance = 8f

internal fun DockingState.edgeWidth(side: DockSide): Float {
    val stripe = if (stripesVisible && pinnedOn(side).isNotEmpty()) StripeButtonSize else 0f
    val panel = sideWidth(side)?.let { it + PanelSplitterWidth } ?: 0f
    return stripe + panel
}

internal fun stripeId(side: DockSide): String = "dock-stripe-${side.tag}"

internal fun stripeButtonId(itemId: String): String = "dock-stripe-button-$itemId"

/** The top buttons, a divider and the split ones under it, a gap, then the bottom buttons. */
@Composable
internal fun DockStripe(side: DockSide, state: DockingState) {
    val open = state.stripesVisible && state.pinnedOn(side).isNotEmpty()
    var height by remember { mutableStateOf(0f) }
    Column(
        id = stripeId(side),
        tags = listOf(DockTags.Stripe, side.tag),
        modifier = Modifier.size(if (open) StripeButtonSize.px else 0.px, 100.percent)
            .alignItems(horizontal = UiAlign.CENTER).clip().onPlaced { height = it.height },
    ) {
        state.pinnedIn(DockAnchor(side, DockStripeGroup.TOP)).forEach { pinned ->
            key(pinned.item.id) { DockStripeButton(pinned, state) { height } }
        }
        val split = state.pinnedIn(DockAnchor(side, DockStripeGroup.SPLIT))
        if (split.isNotEmpty()) {
            Box(
                tags = listOf(DockTags.StripeDivider),
                modifier = Modifier.size(StripeDividerWidth.px, 1.px),
            )
            split.forEach { pinned ->
                key(pinned.item.id) { DockStripeButton(pinned, state) { height } }
            }
        }
        Box(modifier = Modifier.size(1.px, 0.px).grow(1f))
        state.pinnedIn(DockAnchor(side, DockStripeGroup.BOTTOM)).forEach { pinned ->
            key(pinned.item.id) { DockStripeButton(pinned, state) { height } }
        }
    }
}

internal fun pinnedHeaderId(itemId: String): String = "dock-pinned-header-$itemId"

/**
 * Lets a parked window's header pull it off into a floating window, the way its stripe button can.
 */
internal fun Modifier.pinnedHeaderDrag(itemId: String, state: DockingState): Modifier =
    input(hoverable = true, clickable = true, draggable = true)
        .onDrag { event ->
            if (hypot(event.dragTotalX, event.dragTotalY) < HeaderUndockDistance) return@onDrag
            event.consume()
            val spaceX = event.localXInAncestor(DockTags.Space) ?: event.rootLocalX
            val spaceY = event.localYInAncestor(DockTags.Space) ?: event.rootLocalY
            state.undockPinned(itemId, spaceX - event.localX, spaceY - event.localY, pinnedHeaderId(itemId))
        }

/** Where the slot of [itemId] in [anchor] starts, down its stripe, as `.dock-stripe` lays it out. */
private fun DockingState.stripeSlotTop(anchor: DockAnchor, itemId: String, height: Float): Float {
    val group = pinnedIn(anchor)
    val index = group.indexOfFirst { it.item.id == itemId }.coerceAtLeast(0)
    return when (anchor.group) {
        DockStripeGroup.TOP -> index * StripeButtonPitch
        DockStripeGroup.SPLIT ->
            pinnedIn(DockAnchor(anchor.side, DockStripeGroup.TOP)).size * StripeButtonPitch + StripeDividerPitch +
                    index * StripeButtonPitch
        DockStripeGroup.BOTTOM -> height - StripeButtonSize - (group.size - 1 - index) * StripeButtonPitch
    }
}

private fun DockingState.stripeSlotAt(side: DockSide, itemId: String, along: Float, height: Float): Pair<DockAnchor, Int> {
    fun othersIn(group: DockStripeGroup) = pinnedIn(DockAnchor(side, group)).count { it.item.id != itemId }
    if (along >= height / 2f) {
        val others = othersIn(DockStripeGroup.BOTTOM)
        return DockAnchor(side, DockStripeGroup.BOTTOM) to others - ((height - along) / StripeButtonPitch).toInt()
    }
    val topCount = othersIn(DockStripeGroup.TOP)
    if (along < topCount * StripeButtonPitch + StripeButtonSize) {
        return DockAnchor(side, DockStripeGroup.TOP) to (along / StripeButtonPitch).toInt().coerceIn(0, topCount)
    }
    val splitStart = topCount * StripeButtonPitch + StripeDividerPitch
    val index = ((along - splitStart) / StripeButtonPitch).toInt().coerceIn(0, othersIn(DockStripeGroup.SPLIT))
    return DockAnchor(side, DockStripeGroup.SPLIT) to index
}

/**
 * A stripe button. A click opens or closes its window.
 */
@Composable
private fun DockStripeButton(pinned: DockPinnedItem, state: DockingState, stripeHeight: () -> Float) {
    val item = pinned.item
    val expanded = state.expandedIn(pinned.anchor)?.item?.id == item.id
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    var menu by remember { mutableStateOf(false) }
    val drag = state.stripeDrag?.takeIf { it.itemId == item.id }
    val swap = if (drag == null) state.stripeSwapOffsets[item.id] else null
    if (swap != null) {
        LaunchedEffect(item.id, swap.revision) {
            withFrameNanos { }
            state.clearStripeSwap(item.id, swap.revision)
        }
    }
    val offset = drag?.let { it.along - it.grab - state.stripeSlotTop(pinned.anchor, item.id, stripeHeight()) }
        ?: swap?.offset

    Box(
        id = stripeButtonId(item.id),
        tags = buildList {
            add(DockTags.StripeButton)
            if (expanded) add(DockTags.Selected)
            if (drag != null) add(DockTags.Dragging)
        },
        modifier = Modifier.size(StripeButtonSize.px, StripeButtonSize.px)
            .let { if (offset != null) it.translate(y = offset).transition() else it }
            .layer(if (drag != null) 10 else 0)
            .input(hoverable = true, clickable = true, draggable = true)
            .cursor(UiCursorShape.HAND).tooltipOnHover(item.title.lang, alignment = pinned.side.asideAlignment)
            .onPlaced { anchor = it }
            .onPress { event ->
                // A drag whose release never arrived (the window lost focus mid-gesture) ends here.
                state.stripeDrag = null
                if (event.isRightClick()) {
                    menu = true
                    event.consume()
                }
            }
            .onDrag { event ->
                if (event.isRightClick()) return@onDrag
                val held = state.stripeDrag?.itemId == item.id
                if (!held && hypot(event.dragTotalX, event.dragTotalY) < DragThreshold) return@onDrag
                event.consume()

                if (abs(event.dragTotalX) > StripeButtonSize + UndockDistance) {
                    state.stripeDrag = null
                    val spaceX = event.localXInAncestor(DockTags.Space) ?: event.rootLocalX
                    val spaceY = event.localYInAncestor(DockTags.Space) ?: event.rootLocalY
                    state.undockPinned(item.id, spaceX - HeaderGrabX, spaceY - HeaderGrabY, stripeButtonId(item.id))
                    return@onDrag
                }

                val height = stripeHeight()
                val along = event.localYInAncestor(stripeId(pinned.side)) ?: return@onDrag
                if (height <= 0f) return@onDrag
                val grab = state.stripeDrag?.takeIf { held }?.grab
                    ?: (along - state.stripeSlotTop(pinned.anchor, item.id, height))
                state.stripeDrag = DockStripeDrag(item.id, along, grab)

                val middle = along - grab + StripeButtonSize / 2f
                val (target, index) = state.stripeSlotAt(pinned.side, item.id, middle, height)
                val others = state.pinnedIn(target).count { it.item.id != item.id }
                val before = state.pinnedIn(target).map { it.item.id }
                if (!state.movePinned(item.id, target, index.coerceIn(0, others))) return@onDrag
                if (target == pinned.anchor) {
                    val after = state.pinnedIn(target).map { it.item.id }
                    state.recordStripeSwaps(
                        after.withIndex()
                            .filter { (newIndex, id) -> id != item.id && before.indexOf(id) != newIndex }
                            .associate { (newIndex, id) -> id to (before.indexOf(id) - newIndex) * StripeButtonPitch },
                    )
                }
            }
            .onRelease { event ->
                event.consume()
                val dragged = state.stripeDrag?.itemId == item.id
                state.stripeDrag = null
                if (!dragged && !event.isRightClick()) state.togglePinned(item.id)
            },
    ) {
        item.icon?.let { icon ->
            Image(icon, tags = listOf(DockTags.StripeIcon), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
        }
    }

    if (menu) {
        ContextMenu(
            id = "dock-stripe-menu-${item.id}",
            anchorBounds = anchor,
            items = stripeMenuItems(pinned, state),
            alignment = pinned.side.asideAlignment,
            onExpandedChange = { menu = it },
        )
    }
}

private fun stripeMenuItems(pinned: DockPinnedItem, state: DockingState): List<UiDropdownItem> {
    val itemId = pinned.item.id
    return buildList {
        DockStripeGroup.entries.filter { it != pinned.group }.forEach { group ->
            add(UiDropdownItem(label = DockLang.moveTo(group)) { state.pin(itemId, pinned.side, group) })
        }
        val panels = state.sidePanels(pinned.side)
        if (panels.size == 2 && panels.any { it.item.id == itemId }) {
            add(UiDropdownItem(label = DockLang.SwapPanels) { state.swapSidePanels(pinned.side) })
        }
        add(UiDropdownItem(label = DockLang.pinTo(pinned.side.opposite)) { state.pin(itemId, pinned.side.opposite, pinned.group) })
        add(UiDropdownItem(label = DockLang.Undock) { state.unpin(itemId) })
        if (pinned.item.closable) {
            add(UiDropdownItem(label = DockLang.Close) { state.close(itemId) })
        }
        add(UiDropdownItem(label = DockLang.HideStripes, separatorBefore = true) { state.stripesVisible = false })
    }
}

/**
 * The side panel of [side]: the window its stripe's top part has open, with the split one under it
 * when there is one, full height of the space above the bottom area, sliding the editor aside as it
 * opens.
 */
@Composable
internal fun DockSidePanel(
    side: DockSide,
    state: DockingState,
    tabBarActions: DockTabBarActions,
    content: DockItemContent,
) {
    val target = state.sidePanels(side)
    var shown by remember { mutableStateOf(target) }
    SideEffect { if (target.isNotEmpty()) shown = target }

    var opened by remember { mutableStateOf(false) }
    var sliding by remember { mutableStateOf(false) }
    LaunchedEffect(target.isEmpty()) {
        if (target.isNotEmpty()) {
            if (opened) return@LaunchedEffect
            sliding = true
            withFrameNanos { }
            opened = true
            delay(PanelSlideMillis.milliseconds)
            sliding = false
            return@LaunchedEffect
        }
        sliding = true
        delay(PanelSlideMillis.milliseconds)
        shown = emptyList()
        opened = false
        sliding = false
    }

    val panels = shown.ifEmpty { return }
    val width = if (target.isEmpty() || !opened) 0f else panels.first().width
    val open = target.isNotEmpty() && opened

    if (side == DockSide.RIGHT) DockPanelSplitter(side, state, open, sliding)
    Column(
        id = "dock-pinned-${side.tag}",
        tags = buildList {
            add(DockTags.PinnedPanel)
            add(side.tag)
            if (sliding) add(DockTags.Sliding)
        },
        modifier = Modifier.size(width.px, 100.percent).clip(),
    ) {
        val fraction = state.sideSplitFraction(side)
        panels.forEachIndexed { index, panel ->
            key(panel.item.id) {
                if (index > 0) DockSideDivider(side, state)
                val grow = when {
                    panels.size == 1 -> 1f
                    index == 0 -> fraction
                    else -> 1f - fraction
                }
                Column(
                    id = "dock-pinned-slot-${panel.item.id}",
                    modifier = Modifier.size(100.percent, 0.px).grow(grow).clip(),
                ) {
                    DockPinnedBody(panel, state, tabBarActions, content)
                }
            }
        }
    }
    if (side == DockSide.LEFT) DockPanelSplitter(side, state, open, sliding)
}

/** The handle between a side panel's top window and the split one under it. */
@Composable
private fun DockSideDivider(side: DockSide, state: DockingState) {
    val fractionAtPress = remember { floatArrayOf(state.sideSplitFraction(side)) }
    Box(
        id = "dock-side-divider-${side.tag}",
        tags = listOf(DockTags.Splitter),
        modifier = Modifier.size(100.percent, PanelSplitterWidth.px)
            .input(hoverable = true, draggable = true).cursor(UiCursorShape.RESIZE_VERTICAL)
            .onPress { fractionAtPress[0] = state.sideSplitFraction(side) }
            .onDrag { event ->
                val height = event.parentHeight - PanelSplitterWidth
                if (height > 0f) state.setSideSplitFraction(side, fractionAtPress[0] + event.dragTotalY / height)
                event.consume()
            },
    )
}

/**
 * The area the bottom halves of both stripes.
 */
@Composable
internal fun DockBottomArea(state: DockingState, tabBarActions: DockTabBarActions, content: DockItemContent) {
    val left = state.expandedIn(DockAnchor(DockSide.LEFT, DockStripeGroup.BOTTOM))
    val right = state.expandedIn(DockAnchor(DockSide.RIGHT, DockStripeGroup.BOTTOM))
    if (left == null && right == null) return

    val heightAtPress = remember { floatArrayOf(state.bottomHeight) }
    Box(
        id = "dock-bottom-splitter",
        tags = listOf(DockTags.Splitter),
        modifier = Modifier.size(100.percent, PanelSplitterWidth.px)
            .input(hoverable = true, draggable = true).cursor(UiCursorShape.RESIZE_VERTICAL)
            .onPress { heightAtPress[0] = state.bottomHeight }
            .onDrag { event ->
                state.setBottomHeight(heightAtPress[0] - event.dragTotalY)
                event.consume()
            },
    )
    Row(
        id = "dock-bottom-area",
        tags = listOf(DockTags.PinnedPanel, "bottom"),
        modifier = Modifier.size(100.percent, state.bottomHeight.px).clip(),
    ) {
        val both = left != null && right != null
        left?.let { panel ->
            Column(
                id = "dock-bottom-left",
                modifier = Modifier.size(0.px, 100.percent).grow(if (both) state.bottomFraction else 1f).clip(),
            ) { DockPinnedBody(panel, state, tabBarActions, content) }
        }
        if (both) DockBottomDivider(state)
        right?.let { panel ->
            Column(
                id = "dock-bottom-right",
                modifier = Modifier.size(0.px, 100.percent).grow(if (both) 1f - state.bottomFraction else 1f).clip(),
            ) { DockPinnedBody(panel, state, tabBarActions, content) }
        }
    }
}

@Composable
private fun DockBottomDivider(state: DockingState) {
    val fractionAtPress = remember { floatArrayOf(state.bottomFraction) }
    Box(
        id = "dock-bottom-divider",
        tags = listOf(DockTags.Splitter),
        modifier = Modifier.size(PanelSplitterWidth.px, 100.percent)
            .input(hoverable = true, draggable = true).cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { fractionAtPress[0] = state.bottomFraction }
            .onDrag { event ->
                val width = event.parentWidth - PanelSplitterWidth
                if (width > 0f) state.setBottomFraction(fractionAtPress[0] + event.dragTotalX / width)
                event.consume()
            },
    )
}

/** A parked window as it shows when open: its header, then its content. */
@Composable
private fun DockPinnedBody(
    panel: DockPinnedItem,
    state: DockingState,
    tabBarActions: DockTabBarActions,
    content: DockItemContent,
) {
    val item = panel.item
    Column(tags = listOf(DockTags.PinnedIsland)) {
        if (!item.titleInToolbar) DockPanelHeader(panel, state, tabBarActions)
        Box(
            id = "$PinnedContentPrefix${item.id}-content",
            tags = listOf(DockTags.Content),
            modifier = Modifier.size(100.percent, 0.px).grow(1f).clip().input(hoverable = true, clickable = true)
                .onPress { state.expand(item.id) },
        ) {
            key(item.id) {
                val title = if (item.titleInToolbar) {
                    DockPanelTitle(
                        title = item.title.lang,
                        icon = item.icon,
                        headerId = pinnedHeaderId(item.id),
                        dragHandle = Modifier.pinnedHeaderDrag(item.id, state),
                    )
                } else null
                CompositionLocalProvider(LocalDockPanelTitle provides title) {
                    DockContentBody { content(item) }
                }
            }
        }
    }
}

@Composable
private fun DockPanelHeader(panel: DockPinnedItem, state: DockingState, tabBarActions: DockTabBarActions) {
    val item = panel.item
    Row(
        id = pinnedHeaderId(item.id),
        tags = listOf(DockTags.PinnedHeader),
        modifier = Modifier.size(100.percent, 24.px).alignItems(vertical = UiAlign.CENTER)
            .pinnedHeaderDrag(item.id, state),
    ) {
        item.icon?.let { icon -> Image(icon, tags = listOf(DockTags.PinnedHeaderIcon)) }
        Box(modifier = Modifier.size(0.px, 100.percent).grow(1f).clip()) {
            Text(
                item.title.lang,
                tags = listOf(DockTags.PinnedHeaderLabel),
                modifier = Modifier.align(UiAlign.START, UiAlign.CENTER).textWrap(false),
            )
        }
        key(item.id) { tabBarActions(item) }
    }
}

@Composable
private fun DockPanelSplitter(
    side: DockSide,
    state: DockingState,
    open: Boolean,
    sliding: Boolean,
) {
    val start = remember(side) { floatArrayOf(state.sideWidth(side) ?: 0f) }
    Box(
        id = "dock-pinned-splitter-${side.tag}",
        tags = buildList {
            add(DockTags.Splitter)
            if (sliding) add(DockTags.Sliding)
        },
        modifier = Modifier.size(if (open) PanelSplitterWidth.px else 0.px, 100.percent)
            .input(hoverable = true, draggable = true).cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { start[0] = state.sideWidth(side) ?: 0f }.onDrag { event ->
                val delta = if (side == DockSide.LEFT) event.dragTotalX else -event.dragTotalX
                state.setSideWidth(side, start[0] + delta)
                event.consume()
            },
    )
}

internal val DockSide.tag: String get() = if (this == DockSide.LEFT) "left" else "right"

private val DockSide.asideAlignment: UiPopupAlignment
    get() = if (this == DockSide.LEFT) {
        UiPopupAlignment(
            anchorHorizontal = UiAlign.END,
            anchorVertical = UiAlign.CENTER,
            popupVertical = UiAlign.CENTER,
            offsetX = 6f,
        )
    } else {
        UiPopupAlignment(
            anchorHorizontal = UiAlign.START,
            anchorVertical = UiAlign.CENTER,
            popupHorizontal = UiAlign.END,
            popupVertical = UiAlign.CENTER,
            offsetX = -6f,
        )
    }

internal object DockLang {
    private const val Root = "hollowengine.gui.docking"

    val PinLeft: String get() = "$Root.pin_left".lang
    val PinRight: String get() = "$Root.pin_right".lang
    val PinLeftBottom: String get() = "$Root.pin_left_bottom".lang
    val PinRightBottom: String get() = "$Root.pin_right_bottom".lang
    val PinLeftSplit: String get() = "$Root.pin_left_split".lang
    val PinRightSplit: String get() = "$Root.pin_right_split".lang
    val Undock: String get() = "$Root.undock".lang
    val Close: String get() = "$Root.close".lang
    val HideStripes: String get() = "$Root.hide_stripes".lang
    val ShowStripes: String get() = "$Root.show_stripes".lang
    val ParkHere: String get() = "$Root.park_here".lang

    fun pinTo(side: DockSide): String = if (side == DockSide.LEFT) PinLeft else PinRight

    val SwapPanels: String get() = "$Root.swap_panels".lang

    fun moveTo(group: DockStripeGroup): String = when (group) {
        DockStripeGroup.TOP -> "$Root.move_to_top".lang
        DockStripeGroup.SPLIT -> "$Root.move_to_split".lang
        DockStripeGroup.BOTTOM -> "$Root.move_to_bottom".lang
    }
}
