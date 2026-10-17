package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.*
import kotlin.math.min

private class EngineMeasurable(
    private val pipeline: UiLayoutPipeline,
    override val node: UiNode,
    private val resolved: UiNode,
    private val scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
) : UiMeasurable {
    override fun measure(constraints: UiConstraints): UiPlaceable {
        val size = pipeline.measureNode(
            node,
            resolved,
            constraints.maxWidth,
            constraints.maxHeight,
            scrollbarReserves,
            widthOverride = constraints.fixedWidthOrNull(),
            heightOverride = constraints.fixedHeightOrNull(),
        )
        return UiPlaceable(
            width = constraints.constrainWidth(size.width),
            height = constraints.constrainHeight(size.height),
            node = node,
        )
    }
}

internal enum class FlowAxis {
    Horizontal,
    Vertical,
}

internal fun UiLayoutPipeline.placeCustomChildren(scope: ChildPlacementScope, measurePolicy: UiMeasurePolicy) {
    val result = measureCustomLayout(
        scope.node,
        scope.resolved,
        measurePolicy,
        scope.content.width,
        scope.content.height,
        scope.scrollbarReserves,
    )
    for (placement in result.placements) {
        val child = placement.placeable.node
        val childStyle = scope.resolved[child]
        val position = childStyle.position.resolve(scope.content.width, scope.content.height)
        placeScopedNode(
            scope,
            child,
            UiRect(
                scope.content.x + placement.x + position.x,
                scope.content.y + placement.y + position.y,
                placement.placeable.width,
                placement.placeable.height,
            ),
        )
    }
}

internal fun UiLayoutPipeline.placeLinearChildren(
    axis: FlowAxis,
    scope: ChildPlacementScope,
) {
    val node = scope.node
    val resolved = scope.resolved
    val style = scope.style
    val content = scope.content
    val mainAvailable = axis.mainSize(content)
    val gap = style.gap.resolve(mainAvailable)
    val scroll = style.scroll
    val allowWidthOverflow = scroll?.horizontal == true
    val allowHeightOverflow = scroll?.vertical == true
    val measured = measureFlowChildren(
        node,
        resolved,
        content.width,
        content.height,
        scope.scrollbarReserves,
        allowWidthOverflow = allowWidthOverflow,
        allowHeightOverflow = allowHeightOverflow,
    )
    val children = growLinearChildren(
        axis,
        measured,
        mainAvailable,
        gap,
        resolved,
        scope.scrollbarReserves,
        allowOverflow = style.scrollable,
    )
    val totalMain = children.sumOfOuterMain(axis) + gap * (children.size - 1).coerceAtLeast(0)
    val parentAxis = node.measurePolicy.flowAxis()
    val mainAlign = children.singleChildMainAxisAlign(axis) ?: style.childMainAlign(parentAxis, axis)
    val mainOffset = mainAlign.mainStartOffset(mainAvailable, totalMain, children.size)

    var main = axis.mainStart(content) + mainOffset
    val actualGap = mainAlign.mainGap(mainAvailable, totalMain, children.size, gap)
    for (child in children) {
        val position = child.style.position.resolve(content.width, content.height)
        val align = child.crossAlign(style, parentAxis, axis)
        val mainOverflow = when (axis) {
            FlowAxis.Horizontal -> allowWidthOverflow
            FlowAxis.Vertical -> allowHeightOverflow
        }
        val mainSize = child.placedMainSize(axis, content, mainOverflow)
        val crossOverflow = if (axis == FlowAxis.Vertical) allowWidthOverflow else allowHeightOverflow
        val crossSize = child.placedCrossSize(axis, content, align, crossOverflow)
        val rect = axis.placedRect(content, child, position, main, mainSize, crossSize, align)
        placeScopedNode(scope, child.node, rect)
        main += child.mainMarginStart(axis) + mainSize + child.mainMarginEnd(axis) + actualGap
    }
}

internal fun UiLayoutPipeline.placeFreeChildren(scope: ChildPlacementScope) {
    val node = scope.node
    val resolved = scope.resolved
    val style = scope.style
    val content = scope.content
    for (child in measureFlowChildren(
        node,
        resolved,
        content.width,
        content.height,
        scope.scrollbarReserves,
        allowWidthOverflow = style.scrollable,
        allowHeightOverflow = style.scrollable,
    )) {
        val position = child.style.position.resolve(content.width, content.height)
        val parentAxis = node.measurePolicy.flowAxis()
        val alignX = child.style.effectiveAlignHorizontal(style, parentAxis) ?: UiAlign.START
        val alignY = child.style.effectiveAlignVertical(style, parentAxis) ?: UiAlign.START
        val width = child.style.size.width
        val height = child.style.size.height
        val childWidth = if (width.dependsOnAvailableSpace) {
            width.resolveWidth(alignX, child.style, child, content)
        } else {
            child.size.width.boundToBox(width, content.width - child.margin.horizontal, style.scrollable)
        }
        val childHeight = if (height.dependsOnAvailableSpace) {
            height.resolveHeight(alignY, child.style, child, content)
        } else {
            child.size.height.boundToBox(height, content.height - child.margin.vertical, style.scrollable)
        }
        val x = content.x + alignX.crossOffset(content.width, childWidth, child.margin.left, child.margin.right)
        val y = content.y + alignY.crossOffset(content.height, childHeight, child.margin.top, child.margin.bottom)
        val rect = UiRect(x + position.x, y + position.y, childWidth, childHeight)
        placeScopedNode(scope, child.node, rect)
    }
}

private fun Float.boundToBox(length: UiLength, available: Float, scrollable: Boolean): Float {
    if (scrollable || !length.isContentSized || available <= 0f) return this
    return min(this, available)
}

internal fun UiLayoutPipeline.measureFlowChildren(
    node: UiNode,
    resolved: UiNode,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    deferFlexibleWidth: Boolean = false,
    deferFlexibleHeight: Boolean = false,
    allowWidthOverflow: Boolean = false,
    allowHeightOverflow: Boolean = false,
): List<MeasuredChild> {
    return measureFlowChildren(
        layoutChildren(node),
        resolved,
        availableWidth,
        availableHeight,
        scrollbarReserves,
        deferFlexibleWidth = deferFlexibleWidth,
        deferFlexibleHeight = deferFlexibleHeight,
        allowWidthOverflow = allowWidthOverflow,
        allowHeightOverflow = allowHeightOverflow,
    )
}

internal fun UiLayoutPipeline.measureFlowChildren(
    children: List<UiNode>,
    resolved: UiNode,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    deferFlexibleWidth: Boolean = false,
    deferFlexibleHeight: Boolean = false,
    allowWidthOverflow: Boolean = false,
    allowHeightOverflow: Boolean = false,
): List<MeasuredChild> {
    if (children.isEmpty()) return emptyList()
    val measured = ArrayList<MeasuredChild>(children.size)
    for (child in children) {
        val style = resolved[child]
        val margin = style.margin.resolve(availableWidth, availableHeight)
        val size = measureNode(
            child,
            resolved,
            availableWidth,
            availableHeight,
            scrollbarReserves,
            deferFlexibleWidth = deferFlexibleWidth,
            deferFlexibleHeight = deferFlexibleHeight,
            allowWidthOverflow = allowWidthOverflow,
            allowHeightOverflow = allowHeightOverflow,
        )
        measured += MeasuredChild(
            node = child,
            style = style,
            size = size,
            margin = margin,
        )
    }
    return measured
}

internal fun UiLayoutPipeline.measureCustomLayout(
    node: UiNode,
    resolved: UiNode,
    measurePolicy: UiMeasurePolicy,
    availableWidth: Float,
    availableHeight: Float,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
): UiMeasureResult {
    val constraints = UiConstraints(
        maxWidth = availableWidth.coerceAtLeast(0f),
        maxHeight = availableHeight.coerceAtLeast(0f),
    )
    val measurables = layoutChildren(node).map { child ->
        EngineMeasurable(this, child, resolved, scrollbarReserves)
    }
    val scope = UiMeasureScope()
    val result = with(measurePolicy) {
        scope.measure(measurables, constraints)
    }
    return result.copy(
        width = constraints.constrainWidth(result.width),
        height = constraints.constrainHeight(result.height),
    )
}

internal fun UiLayoutPipeline.growRowChildren(
    children: List<MeasuredChild>,
    availableWidth: Float,
    gap: Float,
    resolved: UiNode,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    allowOverflow: Boolean = false,
): List<MeasuredChild> {
    return growLinearChildren(
        FlowAxis.Horizontal,
        children,
        availableWidth,
        gap,
        resolved,
        scrollbarReserves,
        allowOverflow,
    )
}

internal fun UiLayoutPipeline.growColumnChildren(
    children: List<MeasuredChild>,
    availableHeight: Float,
    gap: Float,
    resolved: UiNode,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    allowOverflow: Boolean = false,
): List<MeasuredChild> {
    return growLinearChildren(
        FlowAxis.Vertical,
        children,
        availableHeight,
        gap,
        resolved,
        scrollbarReserves,
        allowOverflow,
    )
}

private fun UiLayoutPipeline.growLinearChildren(
    axis: FlowAxis,
    children: List<MeasuredChild>,
    availableMain: Float,
    gap: Float,
    resolved: UiNode,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    allowOverflow: Boolean,
): List<MeasuredChild> {
    val gapTotal = gap * (children.size - 1).coerceAtLeast(0)
    var fixedMain = 0f
    var flexibleMargins = 0f
    var flexibleCount = 0
    var totalWeight = 0f
    for (child in children) {
        if (child.isFlexible(axis)) {
            flexibleCount++
            flexibleMargins += child.mainMargins(axis)
            totalWeight += child.weight(axis)
        } else {
            fixedMain += child.outerMain(axis)
        }
    }
    val availableForFlexible = (availableMain - fixedMain - flexibleMargins - gapTotal).coerceAtLeast(0f)
    val distributed = if (flexibleCount == 0 || totalWeight <= 0f) {
        children
    } else {
        val next = ArrayList<MeasuredChild>(children.size)
        for (child in children) {
            if (!child.isFlexible(axis)) {
                next += child
                continue
            }
            val targetMain = availableForFlexible * (child.weight(axis) / totalWeight)
            next += child.copy(size = measureChildMain(axis, child, targetMain, resolved, scrollbarReserves))
        }
        next
    }
    var outerMain = 0f
    for (child in distributed) {
        outerMain += child.outerMain(axis)
    }
    val overflow = (outerMain + gapTotal - availableMain).coerceAtLeast(0f)
    if (allowOverflow) return distributed
    if (overflow <= 0f) return distributed
    var shrinkableMain = 0f
    for (child in distributed) {
        if (!child.hasFixedMain(axis) && child.mainSize(axis) > 0f) shrinkableMain += child.mainSize(axis)
    }
    if (shrinkableMain <= 0f) return distributed
    val shrunk = ArrayList<MeasuredChild>(distributed.size)
    for (child in distributed) {
        val mainSize = child.mainSize(axis)
        if (child.hasFixedMain(axis) || mainSize <= 0f) {
            shrunk += child
            continue
        }
        val targetMain = (mainSize - overflow * (mainSize / shrinkableMain)).coerceAtLeast(0f)
        shrunk += child.copy(size = measureChildMain(axis, child, targetMain, resolved, scrollbarReserves))
    }
    return shrunk
}

private fun UiLayoutPipeline.measureChildMain(
    axis: FlowAxis,
    child: MeasuredChild,
    targetMain: Float,
    resolved: UiNode,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
): LayoutSize {
    return when (axis) {
        FlowAxis.Horizontal -> measureNode(
            child.node,
            resolved,
            targetMain,
            child.size.height,
            scrollbarReserves,
            widthOverride = targetMain.takeUnless { child.isWrappedAutoText },
        )

        FlowAxis.Vertical -> measureNode(
            child.node,
            resolved,
            child.size.width,
            targetMain,
            scrollbarReserves,
            heightOverride = targetMain,
        )
    }
}

private fun List<MeasuredChild>.sumOfOuterMain(axis: FlowAxis): Float {
    return sumOf { child -> child.outerMain(axis).toDouble() }.toFloat()
}

private fun List<MeasuredChild>.singleChildMainAxisAlign(axis: FlowAxis): UiAlign? {
    return singleChildMainAxisAlign { style ->
        when (axis) {
            FlowAxis.Horizontal -> style.alignHorizontal
            FlowAxis.Vertical -> style.alignVertical
        }
    }
}

private fun UiComputedStyle.childMainAlign(parentAxis: FlowAxis?, axis: FlowAxis): UiAlign? {
    return when (axis) {
        FlowAxis.Horizontal -> childAlignHorizontal(parentAxis)
        FlowAxis.Vertical -> childAlignVertical(parentAxis)
    }
}

private fun MeasuredChild.crossAlign(parentStyle: UiComputedStyle, parentAxis: FlowAxis?, axis: FlowAxis): UiAlign {
    return when (axis) {
        FlowAxis.Horizontal -> style.alignVertical.takeUnless { it == UiAlign.AUTO }
            ?: parentStyle.childAlignVertical(parentAxis)
            ?: UiAlign.START

        FlowAxis.Vertical -> style.alignHorizontal.takeUnless { it == UiAlign.AUTO }
            ?: parentStyle.childAlignHorizontal(parentAxis)
            ?: UiAlign.STRETCH
    }
}

private fun MeasuredChild.placedMainSize(axis: FlowAxis, content: UiRect, allowOverflow: Boolean): Float {
    val available = (axis.mainSize(content) - mainMargins(axis)).coerceAtLeast(0f)
    val size = mainSize(axis)
    return if (allowOverflow) size else size.coerceAtMost(available)
}

private fun MeasuredChild.placedCrossSize(axis: FlowAxis, content: UiRect, align: UiAlign, allowOverflow: Boolean): Float {
    return when (axis) {
        FlowAxis.Horizontal -> {
            val resolved = style.size.height.resolveHeight(align, style, this, content)
            if (allowOverflow) maxOf(resolved, size.height) else resolved
        }

        FlowAxis.Vertical -> {
            val resolved = style.size.width.resolveWidth(align, style, this, content)
            if (allowOverflow) maxOf(resolved, size.width)
            else resolved.coerceAtMost((content.width - margin.left - margin.right).coerceAtLeast(0f))
        }
    }
}

private fun FlowAxis.placedRect(
    content: UiRect,
    child: MeasuredChild,
    position: UiVec3,
    mainStart: Float,
    mainSize: Float,
    crossSize: Float,
    align: UiAlign,
): UiRect {
    return when (this) {
        FlowAxis.Horizontal -> {
            val y = content.y + align.crossOffset(content.height, crossSize, child.margin.top, child.margin.bottom)
            UiRect(mainStart + child.margin.left + position.x, y + position.y, mainSize, crossSize)
        }

        FlowAxis.Vertical -> {
            val x = content.x + align.crossOffset(content.width, crossSize, child.margin.left, child.margin.right)
            UiRect(x + position.x, mainStart + child.margin.top + position.y, crossSize, mainSize)
        }
    }
}

private fun FlowAxis.mainStart(content: UiRect): Float {
    return when (this) {
        FlowAxis.Horizontal -> content.x
        FlowAxis.Vertical -> content.y
    }
}

private fun FlowAxis.mainSize(content: UiRect): Float {
    return when (this) {
        FlowAxis.Horizontal -> content.width
        FlowAxis.Vertical -> content.height
    }
}

private fun MeasuredChild.isFlexible(axis: FlowAxis): Boolean {
    return when (axis) {
        FlowAxis.Horizontal -> isRowFlexible
        FlowAxis.Vertical -> isColumnFlexible
    }
}

private fun MeasuredChild.weight(axis: FlowAxis): Float {
    return when (axis) {
        FlowAxis.Horizontal -> rowWeight()
        FlowAxis.Vertical -> columnWeight()
    }
}

private fun MeasuredChild.mainSize(axis: FlowAxis): Float {
    return when (axis) {
        FlowAxis.Horizontal -> size.width
        FlowAxis.Vertical -> size.height
    }
}

private fun MeasuredChild.mainMargins(axis: FlowAxis): Float {
    return mainMarginStart(axis) + mainMarginEnd(axis)
}

private fun MeasuredChild.mainMarginStart(axis: FlowAxis): Float {
    return when (axis) {
        FlowAxis.Horizontal -> margin.left
        FlowAxis.Vertical -> margin.top
    }
}

private fun MeasuredChild.mainMarginEnd(axis: FlowAxis): Float {
    return when (axis) {
        FlowAxis.Horizontal -> margin.right
        FlowAxis.Vertical -> margin.bottom
    }
}

private fun MeasuredChild.outerMain(axis: FlowAxis): Float {
    return mainMarginStart(axis) + mainSize(axis) + mainMarginEnd(axis)
}

private fun MeasuredChild.hasFixedMain(axis: FlowAxis): Boolean {
    return when (axis) {
        FlowAxis.Horizontal -> style.size.width is UiLength.Px || style.size.width is UiLength.Fit
        FlowAxis.Vertical -> style.size.height is UiLength.Px || style.size.height is UiLength.Fit
    }
}
