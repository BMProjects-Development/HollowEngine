package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.*
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter
import kotlin.math.abs

private const val ConstraintReflowEpsilon = 0.01f

internal fun UiLayoutPipeline.rootRect(
    resolved: UiNode,
    width: Float,
    height: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
): UiRect {
    val node = resolved.root
    val style = resolved[node]
    val margin = style.margin.resolve(width, height)
    val availableWidth = (width - margin.left - margin.right).coerceAtLeast(0f)
    val availableHeight = (height - margin.top - margin.bottom).coerceAtLeast(0f)
    val measured =
        measureNode(node, resolved, availableWidth, availableHeight, scrollbarReserves)
    val rootWidth = if (style.size.width is UiLength.Auto && UiStyleProperty.WIDTH !in style.explicitProperties) {
        availableWidth
    } else {
        measured.width
    }
    val rootHeight =
        if (style.size.height is UiLength.Auto && UiStyleProperty.HEIGHT !in style.explicitProperties) {
            availableHeight
        } else {
            measured.height
        }
    val alignX = style.alignHorizontal.takeUnless { it == UiAlign.AUTO } ?: UiAlign.START
    val alignY = style.alignVertical.takeUnless { it == UiAlign.AUTO } ?: UiAlign.START
    return UiRect(
        x = alignX.crossOffset(width, rootWidth, margin.left, margin.right),
        y = alignY.crossOffset(height, rootHeight, margin.top, margin.bottom),
        width = rootWidth.coerceIn(style.minSize.width, style.maxSize.width, width),
        height = rootHeight.coerceIn(style.minSize.height, style.maxSize.height, height),
    )
}


internal fun UiLayoutPipeline.measureNode(
    node: UiNode,
    resolved: UiNode,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    widthOverride: Float? = null,
    heightOverride: Float? = null,
    deferFlexibleWidth: Boolean = false,
    deferFlexibleHeight: Boolean = false,
    allowWidthOverflow: Boolean = false,
    allowHeightOverflow: Boolean = false,
): LayoutSize {
    val profile = activeProfile
    if (profile == null) return measureNodeCached(
        node, resolved, availableWidth, availableHeight, scrollbarReserves,
        widthOverride, heightOverride, deferFlexibleWidth, deferFlexibleHeight,
        allowWidthOverflow, allowHeightOverflow,
    )
    val outerMeasurement = measureDepth == 0
    val measureStartedAt = if (outerMeasurement) System.nanoTime() else 0L
    profile.measureCalls++
    profile.recordMeasuredNode(node)
    if (node is SpanNode) profile.textNodeMeasurements++
    measureDepth++
    if (measureDepth > profile.maxMeasureDepth) profile.maxMeasureDepth = measureDepth
    try {
        return measureNodeCached(
            node, resolved, availableWidth, availableHeight, scrollbarReserves,
            widthOverride, heightOverride, deferFlexibleWidth, deferFlexibleHeight,
            allowWidthOverflow, allowHeightOverflow,
            onCacheHit = { profile.measureCacheHits++ },
        )
    } finally {
        measureDepth--
        if (outerMeasurement) profile.measureNanos += System.nanoTime() - measureStartedAt
    }
}

private inline fun UiLayoutPipeline.measureNodeCached(
    node: UiNode,
    resolved: UiNode,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    widthOverride: Float?,
    heightOverride: Float?,
    deferFlexibleWidth: Boolean,
    deferFlexibleHeight: Boolean,
    allowWidthOverflow: Boolean,
    allowHeightOverflow: Boolean,
    onCacheHit: () -> Unit = {},
): LayoutSize {
    val cache = measureCacheFor(node)
    val revision = node.layoutState.subtreeLayoutRevision
    val key = MeasureCacheKey(
        availableWidth = availableWidth,
        availableHeight = availableHeight,
        widthOverride = widthOverride,
        heightOverride = heightOverride,
        flags = measureFlags(deferFlexibleWidth, deferFlexibleHeight, allowWidthOverflow, allowHeightOverflow),
        scrollbarReserves = scrollbarReserves,
    )
    cache.get(revision, key)?.let {
        onCacheHit()
        return it
    }
    return measureNodeContent(
        node,
        resolved,
        availableWidth,
        availableHeight,
        scrollbarReserves,
        widthOverride,
        heightOverride,
        deferFlexibleWidth,
        deferFlexibleHeight,
        allowWidthOverflow,
        allowHeightOverflow,
    ).also { cache.put(revision, key, it) }
}

private fun measureFlags(
    deferFlexibleWidth: Boolean,
    deferFlexibleHeight: Boolean,
    allowWidthOverflow: Boolean,
    allowHeightOverflow: Boolean,
): Int {
    var flags = 0
    if (deferFlexibleWidth) flags = flags or 1
    if (deferFlexibleHeight) flags = flags or 2
    if (allowWidthOverflow) flags = flags or 4
    if (allowHeightOverflow) flags = flags or 8
    return flags
}

internal data class MeasureCacheKey(
    val availableWidth: Float,
    val availableHeight: Float,
    val widthOverride: Float?,
    val heightOverride: Float?,
    val flags: Int,
    val scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
)

internal class NodeMeasureCache {
    private var revision = Long.MIN_VALUE
    private val keys = ArrayList<MeasureCacheKey>(MaxEntries)
    private val sizes = ArrayList<LayoutSize>(MaxEntries)

    fun get(revision: Long, key: MeasureCacheKey): LayoutSize? {
        if (this.revision != revision) return null
        val index = keys.indexOf(key)
        return if (index >= 0) sizes[index] else null
    }

    fun put(revision: Long, key: MeasureCacheKey, size: LayoutSize) {
        if (this.revision != revision) {
            this.revision = revision
            keys.clear()
            sizes.clear()
        }
        if (keys.size >= MaxEntries) {
            keys.removeAt(0)
            sizes.removeAt(0)
        }
        keys.add(key)
        sizes.add(size)
    }

    private companion object {
        const val MaxEntries = 6
    }
}

internal fun UiLayoutPipeline.measureNodeContent(
    node: UiNode,
    resolved: UiNode,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    widthOverride: Float? = null,
    heightOverride: Float? = null,
    deferFlexibleWidth: Boolean = false,
    deferFlexibleHeight: Boolean = false,
    allowWidthOverflow: Boolean = false,
    allowHeightOverflow: Boolean = false,
): LayoutSize {
    val style = resolved[node]
    val referenceWidth = availableWidth.coerceAtLeast(0f)
    val referenceHeight = availableHeight.coerceAtLeast(0f)
    val reserve = scrollbarReserves[node] ?: UiScrollbarReserve.None
    val insets = style.outerInsets(referenceWidth, referenceHeight, reserve)
    var width = widthOverride ?: style.size.width.resolveOrNull(referenceWidth, deferFlexibleWidth)
    var height = heightOverride ?: style.size.height.resolveOrNull(referenceHeight, deferFlexibleHeight)
    style.aspectRatio?.let { ratio ->
        if (width == null && height != null) width = height * ratio
        if (height == null && width != null) height = width / ratio
    }
    if (width != null && height != null) {
        return constrainMeasuredSize(
            width = width,
            height = height,
            style = style,
            referenceWidth = referenceWidth,
            referenceHeight = referenceHeight,
            allowWidthOverflow = allowWidthOverflow,
            allowHeightOverflow = allowHeightOverflow,
        )
    }
    val childAvailableWidth = ((width ?: referenceWidth) - insets.horizontal).coerceAtLeast(0f)
    val childAvailableHeight = ((height ?: referenceHeight) - insets.vertical).coerceAtLeast(0f)
    val intrinsic = intrinsicSize(
        node,
        resolved,
        style,
        childAvailableWidth,
        childAvailableHeight,
        scrollbarReserves,
        knownContentWidth = width?.let { (it - insets.horizontal).coerceAtLeast(0f) },
        knownContentHeight = height?.let { (it - insets.vertical).coerceAtLeast(0f) },
    )
    width = width ?: (intrinsic.width + insets.horizontal)
    height = height ?: (intrinsic.height + insets.vertical)
    style.aspectRatio?.let { ratio ->
        val resolvedWidth = width
        val resolvedHeight = height
        if (style.size.width.isContentSized && !style.size.height.isContentSized) {
            width = resolvedHeight * ratio
        }
        if (style.size.height.isContentSized && !style.size.width.isContentSized) {
            height = resolvedWidth / ratio
        }
    }
    val finalWidth = requireNotNull(width)
    val finalHeight = requireNotNull(height)
    val widthReference =
        if (style.size.width is UiLength.Fit || allowWidthOverflow && style.size.width is UiLength.Auto)
            Float.POSITIVE_INFINITY else referenceWidth
    val heightReference =
        if (style.size.height is UiLength.Fit || allowHeightOverflow && style.size.height is UiLength.Auto)
            Float.POSITIVE_INFINITY else referenceHeight
    var constrainedWidth = finalWidth.coerceIn(style.minSize.width, style.maxSize.width, widthReference)
    var constrainedHeight = finalHeight.coerceIn(style.minSize.height, style.maxSize.height, heightReference)
    val widthConstrained = abs(constrainedWidth - finalWidth) > ConstraintReflowEpsilon
    val contentSizedWidth = widthOverride == null && style.size.width.isContentSized
    val contentSizedHeight = heightOverride == null && style.size.height.isContentSized
    val shouldReflowConstrainedWidth =
        widthConstrained && (contentSizedWidth || contentSizedHeight)

    if (!shouldReflowConstrainedWidth) return LayoutSize(constrainedWidth, constrainedHeight)

    val constrainedContentWidth = (constrainedWidth - insets.horizontal).coerceAtLeast(0f)
    val reflowed = intrinsicSize(
        node,
        resolved,
        style,
        constrainedContentWidth,
        childAvailableHeight,
        scrollbarReserves,
        knownContentWidth = constrainedContentWidth,
        knownContentHeight = null,
    )
    if (contentSizedWidth) {
        constrainedWidth = (reflowed.width + insets.horizontal)
            .coerceIn(style.minSize.width, style.maxSize.width, widthReference)
    }
    if (contentSizedHeight) {
        constrainedHeight = (reflowed.height + insets.vertical)
            .coerceIn(style.minSize.height, style.maxSize.height, heightReference)
    }

    return LayoutSize(
        width = constrainedWidth,
        height = constrainedHeight,
    )
}

private fun constrainMeasuredSize(
    width: Float,
    height: Float,
    style: UiComputedStyle,
    referenceWidth: Float,
    referenceHeight: Float,
    allowWidthOverflow: Boolean,
    allowHeightOverflow: Boolean,
): LayoutSize {
    val widthReference =
        if (style.size.width is UiLength.Fit || allowWidthOverflow && style.size.width is UiLength.Auto)
            Float.POSITIVE_INFINITY else referenceWidth
    val heightReference =
        if (style.size.height is UiLength.Fit || allowHeightOverflow && style.size.height is UiLength.Auto)
            Float.POSITIVE_INFINITY else referenceHeight
    return LayoutSize(
        width = width.coerceIn(style.minSize.width, style.maxSize.width, widthReference),
        height = height.coerceIn(style.minSize.height, style.maxSize.height, heightReference),
    )
}

private fun UiLayoutPipeline.intrinsicSize(
    node: UiNode,
    resolved: UiNode,
    style: UiComputedStyle,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    knownContentWidth: Float? = null,
    knownContentHeight: Float? = null,
): LayoutSize {
    return when (node) {
        is SpanNode -> LayoutSize(
            UiTextLayouter.measureTextWidth(node.text, style.fontSize, style.fontFamily),
            style.fontSize,
        )

        else -> {
            if (layoutChildren(node).isEmpty()) return replacedIntrinsicSize(style)

            if (node.measurePolicy !is UiBuiltInMeasurePolicy) {
                val policyHeight =
                    if (style.size.height is UiLength.Fit) Float.POSITIVE_INFINITY else availableHeight
                measureCustomContainer(
                    node,
                    resolved,
                    node.measurePolicy,
                    availableWidth,
                    policyHeight,
                    scrollbarReserves,
                )
            } else {
                measureStandardContainer(
                    node,
                    resolved,
                    style,
                    availableWidth,
                    availableHeight,
                    scrollbarReserves,
                    knownContentWidth,
                    knownContentHeight
                )
            }
        }
    }
}

private fun UiLayoutPipeline.measureCustomContainer(
    node: UiNode,
    resolved: UiNode,
    measurePolicy: UiMeasurePolicy,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
): LayoutSize {
    val result = measureCustomLayout(node, resolved, measurePolicy, availableWidth, availableHeight, scrollbarReserves)
    return LayoutSize(result.width, result.height)
}

private fun UiLayoutPipeline.measureStandardContainer(
    node: UiNode,
    resolved: UiNode,
    style: UiComputedStyle,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    knownContentWidth: Float?,
    knownContentHeight: Float?,
): LayoutSize {
    val children = measureFlowChildren(
        node,
        resolved,
        availableWidth,
        availableHeight,
        scrollbarReserves,
        deferFlexibleWidth = style.size.width.isContentSized,
        deferFlexibleHeight = style.size.height.isContentSized,
        allowWidthOverflow = style.scrollable,
        allowHeightOverflow = style.scrollable,
    )

    val layoutAxis = node.measurePolicy.flowAxis()
    val isHorizontal = layoutAxis == FlowAxis.Horizontal
    val gap = style.gap.resolve(if (isHorizontal) availableWidth else availableHeight)

    return node.measurePolicy.policy().intrinsic(
        this,
        ChildIntrinsicScope(
            node = node,
            children = children,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            knownContentWidth = knownContentWidth,
            knownContentHeight = knownContentHeight,
            gap = gap,
            resolved = resolved,
            scrollbarReserves = scrollbarReserves,
        )
    )
}

internal fun nodeBoxes(rect: UiRect, style: UiComputedStyle, reserve: UiScrollbarReserve): NodeBoxes {
    val border = style.border.width.resolve(rect.width, rect.height)
    val padding = style.padding.resolve(rect.width, rect.height)
    val scrollArea = UiRect(
        rect.x + border.left + padding.left,
        rect.y + border.top + padding.top,
        (rect.width - border.left - border.right - padding.left - padding.right).coerceAtLeast(0f),
        (rect.height - border.top - border.bottom - padding.top - padding.bottom).coerceAtLeast(0f),
    )
    if (!reserve.active) return NodeBoxes(scrollArea, scrollArea)
    return NodeBoxes(
        scrollArea = scrollArea,
        content = scrollArea.copy(
            width = (scrollArea.width - style.verticalGutter(reserve, rect.width)).coerceAtLeast(0f),
            height = (scrollArea.height - style.horizontalGutter(reserve, rect.height)).coerceAtLeast(0f),
        )
    )
}

/**
 * Where a clipping node cuts its children.
 */
internal fun overflowClipRect(rect: UiRect, style: UiComputedStyle, content: UiRect): UiRect {
    val scroll = style.scroll ?: return content
    if (scroll.horizontal && scroll.vertical) return content
    val border = style.border.width.resolve(rect.width, rect.height)
    val paddingBox = UiRect(
        rect.x + border.left,
        rect.y + border.top,
        (rect.width - border.left - border.right).coerceAtLeast(0f),
        (rect.height - border.top - border.bottom).coerceAtLeast(0f),
    )
    return UiRect(
        x = if (scroll.horizontal) content.x else paddingBox.x,
        y = if (scroll.vertical) content.y else paddingBox.y,
        width = if (scroll.horizontal) content.width else paddingBox.width,
        height = if (scroll.vertical) content.height else paddingBox.height,
    )
}

internal fun UiComputedStyle.verticalGutter(reserve: UiScrollbarReserve, reference: Float): Float =
    if (reserve.vertical) scrollbar.resolved(reference).gutter else 0f

internal fun UiComputedStyle.horizontalGutter(reserve: UiScrollbarReserve, reference: Float): Float =
    if (reserve.horizontal) scrollbar.resolved(reference).gutter else 0f
