package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.*


internal fun List<MeasuredChild>.sumOfOuterWidth(): Float =
    sumOf { (it.margin.left + it.size.width + it.margin.right).toDouble() }.toFloat()

internal fun List<MeasuredChild>.sumOfOuterHeight(): Float =
    sumOf { (it.margin.top + it.size.height + it.margin.bottom).toDouble() }.toFloat()

internal fun List<MeasuredChild>.maxOfOuterWidth(): Float =
    maxOfOrNull { it.margin.left + it.size.width + it.margin.right } ?: 0f

internal fun List<MeasuredChild>.maxOfOuterHeight(): Float =
    maxOfOrNull { it.margin.top + it.size.height + it.margin.bottom } ?: 0f

internal fun List<MeasuredChild>.maxOfPositionedOuterWidth(referenceWidth: Float, referenceHeight: Float): Float {
    return maxOfOrNull { child ->
        val position = child.style.position.resolve(referenceWidth, referenceHeight)
        child.margin.left + position.x + child.size.width + child.margin.right
    } ?: 0f
}

internal fun List<MeasuredChild>.maxOfPositionedOuterHeight(referenceWidth: Float, referenceHeight: Float): Float {
    return maxOfOrNull { child ->
        val position = child.style.position.resolve(referenceWidth, referenceHeight)
        child.margin.top + position.y + child.size.height + child.margin.bottom
    } ?: 0f
}

internal inline fun List<MeasuredChild>.singleChildMainAxisAlign(selector: (UiComputedStyle) -> UiAlign): UiAlign? {
    if (size != 1) return null
    return selector(first().style).takeUnless { it == UiAlign.AUTO }
}

internal val MeasuredChild.isRowFlexible: Boolean
    get() = style.size.width is UiLength.Fill ||
            style.size.width is UiLength.Percent ||
            grow > 0f ||
            node.isInlineFlow() && style.textWrap && style.size.width is UiLength.Auto

internal val MeasuredChild.isWrappedAutoText: Boolean
    get() = node.isInlineFlow() &&
            style.textWrap &&
            style.size.width is UiLength.Auto &&
            grow <= 0f

internal val MeasuredChild.isColumnFlexible: Boolean
    get() = style.size.height is UiLength.Fill ||
            style.size.height is UiLength.Percent ||
            grow > 0f ||
            node.isInlineFlow() && style.scrollable && style.size.height is UiLength.Auto

internal val MeasuredChild.canStretchAutoWidth: Boolean
    get() = true

internal fun MeasuredChild.rowWeight(): Float {
    if (grow > 0f) return grow
    val width = style.size.width
    return width.rowWeight(size)
}

internal fun UiLength.rowWeight(size: LayoutSize): Float {
    return when (this) {
        UiLength.Fill -> 1f
        is UiLength.Percent -> value
        UiLength.Auto -> size.width.coerceAtLeast(1f)
        UiLength.Fit, is UiLength.Px -> 0f
        is UiLength.Addition -> first.rowWeight(size) + second.rowWeight(size)
        is UiLength.Substraction -> (first.rowWeight(size) - second.rowWeight(size)).coerceAtLeast(0f)
    }
}

internal fun MeasuredChild.columnWeight(): Float {
    if (grow > 0f) return grow
    val height = style.size.height
    return height.columnWeight(size)
}

private val MeasuredChild.grow: Float
    get() = style.grow

internal fun UiLength.columnWeight(size: LayoutSize): Float {
    return when (this) {
        UiLength.Fill -> 1f
        is UiLength.Percent -> value
        UiLength.Auto -> size.height.coerceAtLeast(1f)
        UiLength.Fit, is UiLength.Px -> 0f
        is UiLength.Addition -> first.columnWeight(size) + second.columnWeight(size)
        is UiLength.Substraction -> (first.columnWeight(size) - second.columnWeight(size)).coerceAtLeast(0f)
    }
}

internal fun UiLength.resolveOrNull(reference: Float, deferFlexible: Boolean = false): Float? = when (this) {
    UiLength.Auto, UiLength.Fit -> null
    UiLength.Fill -> if (deferFlexible) null else reference
    is UiLength.Px -> value
    is UiLength.Percent -> if (deferFlexible) null else reference * value
    is UiLength.Addition -> first.resolveOrNull(reference, deferFlexible)
        ?.let { it + (second.resolveOrNull(reference, deferFlexible) ?: return null) }

    is UiLength.Substraction -> first.resolveOrNull(reference, deferFlexible)
        ?.let { it - (second.resolveOrNull(reference, deferFlexible) ?: return null) }
}

internal fun UiConstraints.fixedWidthOrNull(): Float? {
    return if (minWidth == maxWidth) minWidth else null
}

internal fun UiConstraints.fixedHeightOrNull(): Float? {
    return if (minHeight == maxHeight) minHeight else null
}

internal fun replacedIntrinsicSize(style: UiComputedStyle): LayoutSize {
    return when {
        style.image != null || style.item != null || style.entity != null ||
                style.background is UiPaint.Image -> LayoutSize(
            DefaultReplacedElementSize,
            DefaultReplacedElementSize
        )

        else -> LayoutSize(0f, 0f)
    }
}

private const val DefaultReplacedElementSize = 32f

internal fun Float.coerceIn(min: UiLength, max: UiLength, reference: Float): Float {
    val minValue = min.resolve(reference, 0f)
    val maxValue = max.resolve(reference, Float.POSITIVE_INFINITY)
    return coerceIn(minValue, maxValue.coerceAtLeast(minValue))
}


/**
 * The measure policy of the OverlayHost's popup container: measures each [PopupNode] child freely and
 * places it at its own [PopupNode.anchorBounds] + [PopupNode.alignment] (root coordinates, since the
 * container is full-screen at the origin).
 */
val PopupOverlayMeasurePolicy = UiMeasurePolicy { measurables, constraints ->
    val placed = measurables.map { it to it.measure(constraints) }
    layout(constraints.maxWidth, constraints.maxHeight) {
        for ((measurable, placeable) in placed) {
            val popup = measurable.node as? PopupNode ?: continue
            val size = LayoutSize(placeable.width, placeable.height)
            val placed = popup.alignment.popupRect(popup.anchorBounds, size)
            val rect = if (popup.alignment.flipsHorizontally && placed.x + placed.width > constraints.maxWidth) {
                popup.alignment.mirrored().popupRect(popup.anchorBounds, size).takeIf { it.x >= 0f } ?: placed
            } else placed
            val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0f)
            val maxY = (constraints.maxHeight - placeable.height).coerceAtLeast(0f)
            placeable.place(rect.x.coerceIn(0f, maxX), rect.y.coerceIn(0f, maxY))
        }
    }
}

internal fun UiPopupAlignment.popupRect(anchor: UiRect, size: LayoutSize): UiRect {
    val anchorX = anchor.x + anchorHorizontal.alignmentOffset(anchor.width)
    val anchorY = anchor.y + anchorVertical.alignmentOffset(anchor.height)
    val popupX = popupHorizontal.alignmentOffset(size.width)
    val popupY = popupVertical.alignmentOffset(size.height)
    return UiRect(anchorX - popupX + offsetX, anchorY - popupY + offsetY, size.width, size.height)
}

private fun UiAlign.alignmentOffset(size: Float): Float {
    return when (this) {
        UiAlign.CENTER -> size / 2f
        UiAlign.END -> size
        else -> 0f
    }
}

internal fun UiAlign?.mainStartOffset(available: Float, used: Float, count: Int): Float {
    val extra = (available - used).coerceAtLeast(0f)
    return when (this) {
        UiAlign.CENTER -> extra / 2f
        UiAlign.END -> extra
        UiAlign.SPACE_AROUND -> if (count > 0) extra / count / 2f else 0f
        UiAlign.SPACE_EVENLY -> if (count > 0) extra / (count + 1) else 0f
        else -> 0f
    }
}

internal fun UiAlign?.mainGap(available: Float, used: Float, count: Int, gap: Float): Float {
    val extra = (available - used).coerceAtLeast(0f)
    return when (this) {
        UiAlign.SPACE_BETWEEN -> if (count > 1) gap + extra / (count - 1) else gap
        UiAlign.SPACE_AROUND -> if (count > 0) gap + extra / count else gap
        UiAlign.SPACE_EVENLY -> if (count > 0) gap + extra / (count + 1) else gap
        else -> gap
    }
}

internal fun UiAlign.crossOffset(available: Float, size: Float, startMargin: Float, endMargin: Float): Float {
    return when (this) {
        UiAlign.CENTER -> startMargin + (available - size - startMargin - endMargin) / 2f
        UiAlign.END -> available - size - endMargin
        else -> startMargin
    }.coerceAtLeast(startMargin)
}

internal fun UiComputedStyle.effectiveAlignHorizontal(parent: UiComputedStyle?, parentAxis: FlowAxis?): UiAlign? {
    return alignHorizontal.takeUnless { it == UiAlign.AUTO } ?: parent?.childAlignHorizontal(parentAxis)
}

internal fun UiComputedStyle.effectiveAlignVertical(parent: UiComputedStyle?, parentAxis: FlowAxis?): UiAlign? {
    return alignVertical.takeUnless { it == UiAlign.AUTO } ?: parent?.childAlignVertical(parentAxis)
}

internal fun UiComputedStyle.childAlignHorizontal(parentAxis: FlowAxis?): UiAlign? {
    return alignItemsHorizontal.takeUnless { it == UiAlign.AUTO }
        ?: if (parentAxis == FlowAxis.Horizontal) justifyContent.takeUnless { it == UiAlign.AUTO }
        else alignItems.takeUnless { it == UiAlign.AUTO }
}

internal fun UiComputedStyle.childAlignVertical(parentAxis: FlowAxis?): UiAlign? {
    return alignItemsVertical.takeUnless { it == UiAlign.AUTO }
        ?: if (parentAxis == FlowAxis.Horizontal) alignItems.takeUnless { it == UiAlign.AUTO }
        else justifyContent.takeUnless { it == UiAlign.AUTO }
}

internal fun UiInsets.resolve(parentWidth: Float, parentHeight: Float): ResolvedUiInsets {
    return ResolvedUiInsets(
        left = left.resolve(parentWidth),
        top = top.resolve(parentHeight),
        right = right.resolve(parentWidth),
        bottom = bottom.resolve(parentHeight),
    )
}

internal fun UiRect?.intersect(other: UiRect): UiRect {
    if (this == null) return other
    val left = maxOf(x, other.x)
    val top = maxOf(y, other.y)
    val right = minOf(x + width, other.x + other.width)
    val bottom = minOf(y + height, other.y + other.height)
    if (right <= left || bottom <= top) return UiRect(left, top, 0f, 0f)
    if (left == x && top == y && right == x + width && bottom == y + height) return this
    if (left == other.x && top == other.y && right == other.x + other.width && bottom == other.y + other.height) {
        return other
    }
    return UiRect(left, top, right - left, bottom - top)
}
