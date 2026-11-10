package ru.hollowhorizon.hollowengine.client.ui.docking

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

private const val DropOverlayId = "dock-drop-overlay"

@Composable
internal fun DockStackDropPreview(
    id: String,
    modifier: Modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER),
    size: Float = MaxSplitZoneSize,
) {
    Box(
        id = "$id-drop-preview",
        mode = UiBoxMode.STACK,
        tags = listOf(DockTags.DropZone, DockTags.Active),
        modifier = modifier.size(size.px, size.px).layer(10_000).inputTransparent(),
    ) {
        DropZoneGlyph(DockPlacement.CENTER)
    }
}

@Composable
internal fun FloatingResizeHandle(window: FloatingDockWindow, state: DockingState) {
    DockResizeEdge.entries.forEach { edge ->
        val dragStart = remember(window.id, edge) { arrayOf(window) }
        Box(
            id = "${window.id}-resize-${edge.name.lowercase()}",
            tags = listOf(DockTags.ResizeHandle),
            modifier = Modifier.resizeHandleModifier(edge).input(hoverable = true, draggable = true)
                .cursor(edge.cursorShape).onPress { dragStart[0] = window }.onDrag { event ->
                    state.resizeFloatingFrom(
                        window.id,
                        edge,
                        dragStart[0],
                        event.dragTotalX,
                        event.dragTotalY,
                    )
                    event.consume()
                })
    }
}

@Composable
internal fun DockDropOverlay(
    state: DockingState,
    leftInset: Float = 0f,
    rightInset: Float = 0f,
    bottomInset: Float = 0f,
    incoming: DockIncomingDrop? = null,
) {
    var bounds by remember { mutableStateOf(UiRect.Zero) }
    var hoveredStackId by remember { mutableStateOf<String?>(null) }
    val target = if (incoming == null) state.previewTarget else (incoming.target as? DockDropTarget.Pane)?.target
    Box(
        id = DropOverlayId,
        tags = listOf(DockTags.DropOverlay),
        modifier = Modifier.size(100.percent, 100.percent).padding(leftInset.px, 0.px, rightInset.px, bottomInset.px)
            .layer(10_000).onPlaced { bounds = it }
            .then(if (incoming == null) localDropReceiver(state) { hoveredStackId = it } else Modifier),
    ) {
        val root = state.root
        val layouts = DockLayoutCalculator.layout(root, DockBounds)
        if (target?.placement == DockPlacement.CENTER) {
            if (incoming != null) {
                dockPreviewRect(target, layouts, DockBounds)?.let { pane ->
                    val size = pane.splitZoneSize()
                    DockStackDropPreview(
                        "dock-center",
                        Modifier.position(pane.centerDropX(size), pane.centerDropY(size)),
                        size,
                    )
                }
            }
        } else {
            DockDropPreview(
                target,
                layouts,
                width = bounds.width - leftInset - rightInset,
                height = bounds.height - bottomInset,
            )
        }
        val anchor = target?.anchorId ?: hoveredStackId
        val floating = state.floatingWindows.firstOrNull { incoming == null && !it.detached && it.stack.id == anchor }
        if (floating != null) {
            val size = minOf(MaxSplitZoneSize, floating.width, floating.height)
            val x = (floating.x - leftInset + (floating.width - size) / 2f).px
            val y = (floating.y + (floating.height - size) / 2f).px
            DockDropZone(state, DockTarget(floating.stack.id), x, y, size.px, size.px, 1, null)
        } else {
            val stackId = (root as? DockNode.Stack)?.id ?: anchor
            layouts.firstOrNull { it.stack && it.nodeId == stackId }?.let { layout ->
                DockPaneDropZones(state, layout, incoming)
            }
            if (root == null && incoming == null) DockCenterDropZone(state, DockTarget.Root, DockBounds)
        }
        if (root is DockNode.Split) DockRootDropZones(state, incoming)
    }
}

private fun localDropReceiver(state: DockingState, onHoverStack: (String?) -> Unit): Modifier =
    Modifier.input(hoverable = true, clickable = true).onHover { event -> onHoverStack(event.stackUnderDrag(state)) }
        .onRelease { event ->
            if (event.isLeftButton()) state.finishDraggingWindow()
        }

private fun UiEvent.stackUnderDrag(state: DockingState): String? {
    val sourceId = state.draggedWindowId ?: return null
    val destination = frame?.dockDropTargetAt(x, y) { node -> node.id == DropOverlayId || node.id == sourceId }
    return (destination as? DockDropTarget.Pane)?.target?.anchorId
}

/**
 * Outlines where the dragged window would land.
 */
@Composable
private fun DockDropPreview(preview: DockTarget?, layouts: List<DockNodeLayout>, width: Float, height: Float) {
    val target = preview?.let { dockPreviewRect(it, layouts, DockBounds) }
    val last = remember { arrayOfNulls<DockRect>(1) }
    if (target != null) last[0] = target
    val rect = target ?: last[0] ?: return
    if (width <= 0f || height <= 0f) return
    val scaleX = width / DockBounds.width
    val scaleY = height / DockBounds.height
    Box(
        id = "dock-drop-preview",
        tags = listOfNotNull(DockTags.DropPreview, "visible".takeIf { target != null }),
        modifier = Modifier.position(0.px, 0.px).size(
            (rect.width * scaleX - DropPreviewInset * 2f).coerceAtLeast(0f).px,
            (rect.height * scaleY - DropPreviewInset * 2f).coerceAtLeast(0f).px
        ).translate(rect.x * scaleX + DropPreviewInset, rect.y * scaleY + DropPreviewInset).inputTransparent(),
    )
}

@Composable
private fun DockRootDropZones(state: DockingState, incoming: DockIncomingDrop?) {
    for (placement in listOf(DockPlacement.LEFT, DockPlacement.RIGHT, DockPlacement.TOP, DockPlacement.BOTTOM)) {
        val vertical = placement == DockPlacement.LEFT || placement == DockPlacement.RIGHT
        DockDropZone(
            state, DockTarget(placement = placement),
            width = if (vertical) 25.px else 125.px, height = if (vertical) 125.px else 25.px,
            layer = 2, incoming = incoming,
        )
    }
}

@Composable
private fun DockPaneDropZones(state: DockingState, layout: DockNodeLayout, incoming: DockIncomingDrop?) {
    val rect = layout.rect
    val cell = rect.splitZoneSize()
    val gap = cell * 0.18f
    val centerX = rect.centerDropX(cell)
    val centerY = rect.centerDropY(cell)

    if (incoming == null) DockCenterDropZone(state, DockTarget(layout.nodeId), rect)
    val distance = (cell + gap).px
    DockDropZone(
        state,
        DockTarget(layout.nodeId, DockPlacement.LEFT),
        centerX - distance,
        centerY,
        cell.px,
        cell.px,
        1,
        incoming
    )
    DockDropZone(
        state,
        DockTarget(layout.nodeId, DockPlacement.RIGHT),
        centerX + distance,
        centerY,
        cell.px,
        cell.px,
        1,
        incoming
    )
    DockDropZone(
        state,
        DockTarget(layout.nodeId, DockPlacement.TOP),
        centerX,
        centerY - distance,
        cell.px,
        cell.px,
        1,
        incoming
    )
    DockDropZone(
        state,
        DockTarget(layout.nodeId, DockPlacement.BOTTOM),
        centerX,
        centerY + distance,
        cell.px,
        cell.px,
        1,
        incoming
    )
}

@Composable
private fun DockCenterDropZone(state: DockingState, target: DockTarget, rect: DockRect) {
    val cell = rect.splitZoneSize()
    DockDropZone(state, target, rect.centerDropX(cell), rect.centerDropY(cell), cell.px, cell.px, 1, null)
}

private fun DockRect.splitZoneSize(): Float = (minOf(width, height) * 0.6f).coerceAtMost(MaxSplitZoneSize)
private fun DockRect.centerDropX(size: Float): UiLength = (x + width * 0.5f).percent - (size * 0.5f).px
private fun DockRect.centerDropY(size: Float): UiLength = (y + height * 0.5f).percent - (size * 0.5f).px

@Composable
private fun DockDropZone(
    state: DockingState,
    target: DockTarget,
    x: UiLength? = null,
    y: UiLength? = null,
    width: UiLength,
    height: UiLength,
    layer: Int,
    incoming: DockIncomingDrop?,
) {
    val destination = DockDropTarget.Pane(target)
    val active = if (incoming != null) incoming.target == destination else state.previewTarget == target
    Box(
        id = "dock-drop-${target.anchorId ?: "root"}-${target.placement.name.lowercase()}",
        mode = UiBoxMode.STACK,
        tags = listOfNotNull(DockTags.DropZone, DockTags.Active.takeIf { active }),
        modifier = Modifier.size(width, height).layer(layer).input(hoverable = true, clickable = true)
            .then(DockDropZoneMarker(destination)).cursor(UiCursorShape.HAND).onHover {
                if (incoming == null) state.previewDock(target)
            }.onExit {
                if (incoming == null && state.previewTarget == target) state.previewDock(null)
            }.onRelease { event ->
                if (incoming != null || !event.isLeftButton()) return@onRelease
                val id = state.draggedWindowId ?: return@onRelease
                if (state.transfer(DockTransferSource.Window(id), destination)) event.consume()
                state.finishDraggingWindow()
            }.then(
                if (x == null || y == null) {
                    if (target.placement == DockPlacement.LEFT || target.placement == DockPlacement.RIGHT) {
                        Modifier.align(
                            horizontal = if (target.placement == DockPlacement.RIGHT) UiAlign.END else UiAlign.START,
                            vertical = UiAlign.CENTER,
                        )
                    } else {
                        Modifier.align(
                            horizontal = UiAlign.CENTER,
                            vertical = if (target.placement == DockPlacement.BOTTOM) UiAlign.END else UiAlign.START,
                        )
                    }
                } else Modifier.position(x, y)
            )
    ) {
        DropZoneGlyph(target.placement)
    }
}

/** A small picture inside a zone of the part of the pane the window would take. */
@Composable
private fun DropZoneGlyph(placement: DockPlacement) {
    val (width, height) = when (placement) {
        DockPlacement.LEFT, DockPlacement.RIGHT -> 50.percent to 100.percent
        DockPlacement.TOP, DockPlacement.BOTTOM -> 100.percent to 50.percent
        DockPlacement.CENTER -> 100.percent to 100.percent
    }
    val horizontal = if (placement == DockPlacement.RIGHT) UiAlign.END else UiAlign.START
    val vertical = if (placement == DockPlacement.BOTTOM) UiAlign.END else UiAlign.START
    Box(
        tags = listOf(DockTags.DropZoneGlyph),
        modifier = Modifier.size(width, height).align(horizontal, vertical).inputTransparent(),
    )
}

internal fun Modifier.resizeHandleModifier(edge: DockResizeEdge): Modifier {
    val thickness = 2.px
    val corner = 6.px
    val layer = if (edge.isCorner) layer(1) else layer(0)
    return layer.then(
        when (edge) {
            DockResizeEdge.LEFT -> Modifier.position(0.px, 0.px).size(thickness, 100.percent)
            DockResizeEdge.RIGHT -> Modifier.align(UiAlign.END, UiAlign.START).size(thickness, 100.percent)
            DockResizeEdge.TOP -> Modifier.position(0.px, 0.px).size(100.percent, thickness)
            DockResizeEdge.BOTTOM -> Modifier.align(UiAlign.START, UiAlign.END).size(100.percent, thickness)
            DockResizeEdge.TOP_LEFT -> Modifier.position(0.px, 0.px).size(corner, corner)
            DockResizeEdge.TOP_RIGHT -> Modifier.align(UiAlign.END, UiAlign.START).size(corner, corner)
            DockResizeEdge.BOTTOM_LEFT -> Modifier.align(UiAlign.START, UiAlign.END).size(corner, corner)
            DockResizeEdge.BOTTOM_RIGHT -> Modifier.align(UiAlign.END, UiAlign.END).size(corner, corner)
        }
    )
}

internal val DockResizeEdge.isCorner: Boolean
    get() = this == DockResizeEdge.TOP_LEFT || this == DockResizeEdge.TOP_RIGHT || this == DockResizeEdge.BOTTOM_LEFT || this == DockResizeEdge.BOTTOM_RIGHT

internal val DockResizeEdge.cursorShape: UiCursorShape
    get() = when (this) {
        DockResizeEdge.LEFT,
        DockResizeEdge.RIGHT,
            -> UiCursorShape.RESIZE_HORIZONTAL

        DockResizeEdge.TOP,
        DockResizeEdge.BOTTOM,
            -> UiCursorShape.RESIZE_VERTICAL

        DockResizeEdge.TOP_LEFT,
        DockResizeEdge.BOTTOM_RIGHT,
            -> UiCursorShape.RESIZE_NWSE

        DockResizeEdge.TOP_RIGHT,
        DockResizeEdge.BOTTOM_LEFT,
            -> UiCursorShape.RESIZE_NESW
    }

@Composable
internal fun DockStripeDropZones(state: DockingState, incoming: DockIncomingDrop?) {
    var hovered by remember { mutableStateOf<DockAnchor?>(null) }
    Box(
        id = "dock-stripe-drop-zones",
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).layer(10_001).inputTransparent(),
    ) {
        DockAnchor.all.forEach { anchor ->
            val destination = DockDropTarget.Stripe(anchor)
            val active = if (incoming != null) incoming.target == destination else hovered == anchor
            val horizontal = if (anchor.side == DockSide.LEFT) UiAlign.START else UiAlign.END
            val vertical = when (anchor.group) {
                DockStripeGroup.TOP -> UiAlign.START
                DockStripeGroup.SPLIT -> UiAlign.CENTER
                DockStripeGroup.BOTTOM -> UiAlign.END
            }
            Box(
                id = "dock-stripe-drop-${anchor.side.tag}-${anchor.group.name.lowercase()}",
                tags = listOfNotNull(DockTags.DropZone, DockTags.Active.takeIf { active }),
                modifier = Modifier.size(StripeDropWidth.px, StripeDropHeight.percent).align(horizontal, vertical)
                    .input(hoverable = true, clickable = true).tooltipOnHover(DockLang.ParkHere)
                    .then(DockDropZoneMarker(destination)).onHover { if (incoming == null) hovered = anchor }
                    .onExit { if (hovered == anchor) hovered = null }.onRelease { event ->
                        if (incoming == null) {
                            state.pinDraggedWindow(anchor)
                            event.consume()
                        }
                    },
            )
        }
    }
}

private const val StripeDropWidth = 28f

private const val StripeDropHeight = 32f
private val DockBounds = DockRect(0f, 0f, 100f, 100f)
private const val DropPreviewInset = 3f
private const val MaxSplitZoneSize = 36f

internal fun DockingState.bottomAreaHeight(): Float {
    val open = DockSide.entries.any { side -> expandedIn(DockAnchor(side, DockStripeGroup.BOTTOM)) != null }
    return if (open) bottomHeight + PanelSplitterWidth else 0f
}
