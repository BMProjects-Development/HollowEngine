package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollOffset
import ru.hollowhorizon.hollowengine.client.ui.style.*

internal fun UiLayoutPipeline.placeNodeNow(
    node: UiNode,
    resolved: UiNode,
    rect: UiRect,
    parentRect: UiRect,
    parentClip: UiRect?,
    parentTransform: UiMatrix4,
    parentInputTransform: UiMatrix4,
    insideFramebuffer: Boolean,
    scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    layouts: MutableMap<UiNode, UiLayoutNode>,
) {
    val profile = activeProfile
    if (profile != null) {
        profile.placedNodes++
        profile.matrixCalculations++
    }
    val style = resolved[node]
    val localX = rect.x - parentRect.x
    val localY = rect.y - parentRect.y
    val nodeTransform = style.transform
    val transform: UiMatrix4
    val inputTransform: UiMatrix4
    if (nodeTransform.isTranslationOnly) {
        val tx = localX + nodeTransform.translate.x
        val ty = localY + nodeTransform.translate.y
        val tz = style.position.z + nodeTransform.translate.z
        transform = parentTransform.translated(tx, ty, tz)
        inputTransform =
            if (parentInputTransform === parentTransform) transform
            else parentInputTransform.translated(tx, ty, tz)
    } else {
        val pivot = nodeTransform.pivot.resolve(rect.width, rect.height)
        val localTransform = nodeTransform.matrix(pivot, localX, localY, style.position.z)
        transform = parentTransform * localTransform
        inputTransform =
            if (parentInputTransform === parentTransform) transform
            else parentInputTransform * localTransform
    }
    val placed = reusableLayout?.nodes?.get(node)
    if (placed != null && placed.isValidFor(node, rect, parentClip, transform, inputTransform, insideFramebuffer)) {
        reusePlacedSubtree(node, reusableLayout!!, layouts)
        return
    }
    val boxes = nodeBoxes(rect, style, scrollbarReserves[node] ?: UiScrollbarReserve.None)
    val scrollOffset = style.scroll?.state?.offset ?: UiScrollOffset.Zero
    val textLayout = (node as? SpanNode)?.lineLayout
    val overflowClip = overflowClipRect(rect, style, boxes.content)
    val clip = if (style.clip || style.scrollable) parentClip.intersect(overflowClip) else parentClip
    val opacityNeedsLayer = style.opacity < 1f && node.children.isNotEmpty()
    val needsFramebuffer = opacityNeedsLayer ||
            nodeTransform.needsFramebuffer && !node.drawsInSpace() ||
            !insideFramebuffer && node.requiresTextLayer(transform) ||
            style.filter.requiresLayer ||
            style.backdropFilter.requiresLayer ||
            style.clipShape != null && style.clip
    if (profile != null) {
        if (needsFramebuffer) profile.framebufferNodes++
        if (textLayout != null) profile.recordTextLayout(textLayout)
    }

    layouts[node] = UiLayoutNode(
        node = node,
        rect = rect,
        content = boxes.content,
        clip = clip,
        outerClip = parentClip,
        overflowClip = overflowClip,
        worldTransform = transform,
        inputTransform = inputTransform,
        needsFramebuffer = needsFramebuffer,
        insideFramebuffer = insideFramebuffer,
        scrollOffset = scrollOffset,
        scrollArea = boxes.scrollArea,
        textLayout = textLayout,
        inlineDecoration = (node as? BaseUiNode)?.inlineDecoration,
        subtreeRevision = node.layoutState.subtreeLayoutRevision,
    )

    if (node.children.isEmpty()) return
    val viewport = if (scrollOffset == UiScrollOffset.Zero) {
        boxes.content
    } else {
        boxes.content.copy(x = boxes.content.x - scrollOffset.x, y = boxes.content.y - scrollOffset.y)
    }
    val policy = node.measurePolicy
    policy.policy().place(
        this,
        ChildPlacementScope(
            node = node,
            resolved = resolved,
            style = style,
            measurePolicy = policy,
            content = viewport,
            parentRect = rect,
            transform = transform,
            inputTransform = inputTransform,
            clip = clip,
            insideFramebuffer = insideFramebuffer || needsFramebuffer,
            scrollbarReserves = scrollbarReserves,
            layouts = layouts,
        ),
    )
}

/**
 * Whether this placement of [node] still stands. Text is left out: where a span wraps comes from the flow around it,
 * which a span or a nested flow cannot see from here.
 */
private fun UiLayoutNode.isValidFor(
    node: UiNode,
    rect: UiRect,
    parentClip: UiRect?,
    transform: UiMatrix4,
    inputTransform: UiMatrix4,
    insideFramebuffer: Boolean,
): Boolean = subtreeRevision == node.layoutState.subtreeLayoutRevision &&
        node !is SpanNode &&
        (node.measurePolicy as? UiBuiltInMeasurePolicy)?.kind != UiBuiltInMeasurePolicyKind.INLINE_FLOW &&
        this.rect == rect &&
        this.insideFramebuffer == insideFramebuffer &&
        outerClip == parentClip &&
        worldTransform.sameAs(transform) &&
        this.inputTransform.sameAs(inputTransform)

private fun UiLayoutPipeline.reusePlacedSubtree(
    node: UiNode,
    previous: UiLayoutResult,
    layouts: MutableMap<UiNode, UiLayoutNode>,
) {
    layouts[node] = previous[node]
    val children = previous.childrenByNode[node] ?: return
    reusedChildren[node] = children
    for (index in children.indices) reusePlacedSubtree(children[index], previous, layouts)
}

private fun ChildPlacementScope.unscrolled(node: UiNode, rect: UiRect): UiRect {
    val pin = node.resolvedSnapshot.scrollPinned ?: return rect
    val offset = style.scroll?.state?.offset ?: return rect
    return rect.copy(
        x = if (pin.horizontal) rect.x + offset.x else rect.x,
        y = if (pin.vertical) rect.y + offset.y else rect.y,
    )
}

internal fun UiLayoutPipeline.placeScopedNode(
    scope: ChildPlacementScope,
    node: UiNode,
    rect: UiRect,
    clip: UiRect? = scope.clip,
) {
    placeNodeNow(
        node = node,
        resolved = scope.resolved,
        rect = scope.unscrolled(node, rect),
        parentRect = scope.parentRect,
        parentClip = clip,
        parentTransform = scope.transform,
        parentInputTransform = scope.inputTransform,
        insideFramebuffer = scope.insideFramebuffer,
        scrollbarReserves = scope.scrollbarReserves,
        layouts = scope.layouts,
    )
}
