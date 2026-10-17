package ru.hollowhorizon.hollowengine.client.ui.scroll

import ru.hollowhorizon.hollowengine.client.ui.PopupNode
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.UiNode
import ru.hollowhorizon.hollowengine.client.ui.UiScrollSpec
import ru.hollowhorizon.hollowengine.client.ui.layout.*
import ru.hollowhorizon.hollowengine.client.ui.scrollSpec
import ru.hollowhorizon.hollowengine.client.ui.style.ResolvedUiScrollbarStyle
import ru.hollowhorizon.hollowengine.client.ui.style.UiComputedStyle
import ru.hollowhorizon.hollowengine.client.ui.style.scrollPinned
import ru.hollowhorizon.hollowengine.client.ui.style.scrollbar
import java.util.*

private const val ScrollOverflowEpsilon = 0.01f

/** Computes scroll ranges/offsets for scrollable containers, updating [layouts] in place. */
internal fun applyScrollRanges(
    layouts: MutableMap<UiNode, UiLayoutNode>,
    scrollState: UiScrollState,
    layoutChildren: (UiNode) -> List<UiNode> = ::layoutChildren,
) {
    for (entry in layouts.entries) {
        val node = entry.key
        val layout = entry.value
        val spec = node.scrollSpec() ?: continue
        val viewport = layout.content
        val bounds = scrollableContentBounds(node, layout, layouts, layoutChildren)
        val range = UiScrollOffset(
            x = if (spec.horizontal) overflowOf(bounds.x + bounds.width, viewport.x + viewport.width) else 0f,
            y = if (spec.vertical) overflowOf(bounds.y + bounds.height, viewport.y + viewport.height) else 0f,
        )
        val placed = scrollState.clamp(spec.state, range)
        scrollState.settleRequest(spec.state, range)
        entry.setValue(layout.copy(scrollOffset = placed, scrollRange = range))
    }
}

internal class ScrollbarCache {
    private val byContainer = WeakHashMap<UiNode, MutableMap<ScrollbarOrientation, ScrollbarNode>>()

    fun hoverProgress(container: UiNode, orientation: ScrollbarOrientation, nowNanos: Long): Float =
        byContainer[container]?.get(orientation)?.hoverProgress(nowNanos) ?: 0f

    fun scrollbar(container: UiNode, orientation: ScrollbarOrientation): ScrollbarNode {
        val map = byContainer.getOrPut(container) { mutableMapOf() }
        return map.getOrPut(orientation) {
            ScrollbarNode(orientation).also { it.layoutState.attachTo(container) }
        }
    }
}

/** Synthesizes scrollbar node layouts, appending them to [layouts] in place. */
internal fun placeScrollbarNodes(
    layouts: MutableMap<UiNode, UiLayoutNode>,
    cache: ScrollbarCache,
): Map<UiNode, List<ScrollbarNode>> {
    var scrollbars: HashMap<UiNode, List<ScrollbarNode>>? = null
    var additions: ArrayList<UiLayoutNode>? = null
    var easing: ArrayList<ScrollbarNode>? = null
    val now = System.nanoTime()
    for ((container, containerLayout) in layouts) {
        val spec = container.scrollSpec() ?: continue
        val style = container.resolvedSnapshot
        val geometry = scrollbarGeometry(spec, style, containerLayout) { cache.hoverProgress(container, it, now) }
        if (geometry.isEmpty()) continue
        val bars = ArrayList<ScrollbarNode>(geometry.size)
        val pending = additions ?: ArrayList<UiLayoutNode>().also { additions = it }
        for (geom in geometry) {
            val bar = cache.scrollbar(container, geom.orientation)
            bar.applyPartStyles(style.scrollbar, bar.hoverProgress(now))
            pending += scrollbarPartLayout(bar, geom.track, containerLayout)
            pending += scrollbarPartLayout(bar.thumb, geom.thumb, containerLayout)
            bars += bar
            if (bar.isEasing(now)) (easing ?: ArrayList<ScrollbarNode>().also { easing = it }) += bar
        }
        (scrollbars ?: HashMap<UiNode, List<ScrollbarNode>>().also { scrollbars = it })[container] = bars
    }
    additions?.forEach { layouts[it.node] = it }
    easing?.forEach { it.layoutState.invalidateLayout() }
    return scrollbars ?: emptyMap()
}

private fun scrollbarPartLayout(node: UiNode, relativeRect: UiRect, container: UiLayoutNode): UiLayoutNode {
    val offset = UiMatrix4.translation(relativeRect.x, relativeRect.y, 0f)
    val absolute = UiRect(
        container.rect.x + relativeRect.x,
        container.rect.y + relativeRect.y,
        relativeRect.width,
        relativeRect.height,
    )
    return UiLayoutNode(
        node = node,
        rect = absolute,
        content = absolute,
        clip = container.outerClip?.intersect(container.rect) ?: container.rect,
        outerClip = container.outerClip,
        worldTransform = container.worldTransform * offset,
        inputTransform = container.inputTransform * offset,
        needsFramebuffer = false,
    )
}

private fun scrollbarGeometry(
    spec: UiScrollSpec,
    style: UiComputedStyle,
    layoutNode: UiLayoutNode,
    hover: (ScrollbarOrientation) -> Float,
): List<UiScrollbarGeometry> {
    val rect = layoutNode.rect
    val scrollArea = layoutNode.scrollArea
    val vertical = style.scrollbar.resolved(scrollArea.width, hover(ScrollbarOrientation.VERTICAL))
    val horizontal = style.scrollbar.resolved(scrollArea.height, hover(ScrollbarOrientation.HORIZONTAL))
    val hasVertical = spec.verticalScrollbar && vertical.isVisible &&
            layoutNode.scrollRange.y > ScrollOverflowEpsilon && scrollArea.height > vertical.thickness
    val hasHorizontal = spec.horizontalScrollbar && horizontal.isVisible &&
            layoutNode.scrollRange.x > ScrollOverflowEpsilon && scrollArea.width > horizontal.thickness
    if (!hasVertical && !hasHorizontal) return emptyList()

    val result = ArrayList<UiScrollbarGeometry>(2)
    if (hasVertical) {
        val trackLength = scrollArea.height - vertical.margin * 2f - if (hasHorizontal) horizontal.gutter else 0f
        if (trackLength > 0f) {
            val track = UiRect(
                x = if (vertical.atStart) scrollArea.x - rect.x + vertical.margin
                else rect.width - vertical.thickness - vertical.margin,
                y = scrollArea.y - rect.y + vertical.margin,
                width = vertical.thickness,
                height = trackLength,
            )
            val thumbLength = thumbLength(trackLength, layoutNode.content.height, layoutNode.scrollRange.y, vertical)
            val thumbOffset = thumbOffset(trackLength, thumbLength, layoutNode.scrollOffset.y, layoutNode.scrollRange.y)
            result += UiScrollbarGeometry(
                track = track,
                thumb = track.copy(y = track.y + thumbOffset, height = thumbLength),
                orientation = ScrollbarOrientation.VERTICAL,
            )
        }
    }
    if (hasHorizontal) {
        val trackLength = scrollArea.width - horizontal.margin * 2f - if (hasVertical) vertical.gutter else 0f
        if (trackLength > 0f) {
            val track = UiRect(
                x = scrollArea.x - rect.x + horizontal.margin,
                y = if (horizontal.atStart) scrollArea.y - rect.y + horizontal.margin
                else rect.height - horizontal.thickness - horizontal.margin,
                width = trackLength,
                height = horizontal.thickness,
            )
            val thumbLength = thumbLength(trackLength, layoutNode.content.width, layoutNode.scrollRange.x, horizontal)
            val thumbOffset = thumbOffset(trackLength, thumbLength, layoutNode.scrollOffset.x, layoutNode.scrollRange.x)
            result += UiScrollbarGeometry(
                track = track,
                thumb = track.copy(x = track.x + thumbOffset, width = thumbLength),
                orientation = ScrollbarOrientation.HORIZONTAL,
            )
        }
    }
    return result
}

private fun thumbLength(
    trackLength: Float,
    viewportLength: Float,
    range: Float,
    style: ResolvedUiScrollbarStyle,
): Float = maxOf(style.minThumbSize, trackLength * viewportLength / (viewportLength + range))
    .coerceAtMost(trackLength)

private fun thumbOffset(trackLength: Float, thumbLength: Float, offset: Float, range: Float): Float =
    if (range > 0f) (trackLength - thumbLength) * (offset / range).coerceIn(0f, 1f) else 0f

internal fun detectScrollbarReserves(
    layouts: Map<UiNode, UiLayoutNode>,
    layoutChildren: (UiNode) -> List<UiNode> = ::layoutChildren,
): Map<UiNode, UiScrollbarReserve> {
    val reserves = linkedMapOf<UiNode, UiScrollbarReserve>()
    for ((node, layout) in layouts) {
        val spec = node.scrollSpec() ?: continue
        val style = node.resolvedSnapshot
        val verticalGutter =
            if (spec.verticalScrollbar) style.scrollbar.resolved(layout.rect.width).gutter else 0f
        val horizontalGutter =
            if (spec.horizontalScrollbar) style.scrollbar.resolved(layout.rect.height).gutter else 0f
        if (verticalGutter <= 0f && horizontalGutter <= 0f) continue
        val bounds = scrollableContentBounds(node, layout, layouts, layoutChildren)
        val area = layout.scrollArea
        var reserve = UiScrollbarReserve.None
        repeat(2) {
            val right = area.x + area.width - if (reserve.vertical) verticalGutter else 0f
            val bottom = area.y + area.height - if (reserve.horizontal) horizontalGutter else 0f
            reserve = UiScrollbarReserve(
                vertical = spec.vertical && verticalGutter > 0f && (bounds.y + bounds.height).exceeds(bottom),
                horizontal = spec.horizontal && horizontalGutter > 0f && (bounds.x + bounds.width).exceeds(right),
            )
        }
        if (reserve.active) reserves[node] = reserve
    }
    return reserves
}

private fun scrollableContentBounds(
    node: UiNode,
    layout: UiLayoutNode,
    layouts: Map<UiNode, UiLayoutNode>,
    layoutChildren: (UiNode) -> List<UiNode>,
): UiRect {
    var bounds: UiRect? = null
    for (child in layoutChildren(node)) {
        if (child.resolvedSnapshot.scrollPinned != null) continue
        val childRect = layouts[child]?.rect ?: continue
        bounds = bounds?.union(childRect) ?: childRect
    }
    val union = bounds ?: return layout.content
    val offset = layout.scrollOffset
    return union.copy(x = union.x + offset.x, y = union.y + offset.y)
}

private fun layoutChildren(node: UiNode): List<UiNode> = node.children.filterNot { it is PopupNode }

private fun overflowOf(contentEdge: Float, viewportEdge: Float): Float =
    if (contentEdge.exceeds(viewportEdge)) contentEdge - viewportEdge else 0f

private fun Float.exceeds(limit: Float): Boolean = this - limit > ScrollOverflowEpsilon

private fun UiRect.union(other: UiRect): UiRect {
    val left = minOf(x, other.x)
    val top = minOf(y, other.y)
    val right = maxOf(x + width, other.x + other.width)
    val bottom = maxOf(y + height, other.y + other.height)
    return UiRect(left, top, right - left, bottom - top)
}
