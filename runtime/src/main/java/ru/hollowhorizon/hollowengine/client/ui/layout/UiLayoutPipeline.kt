package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.*
import java.util.*

class UiLayoutPipeline {
    internal var activeProfile: UiProfileFrame? = null
    internal var measureDepth = 0
    private val scrollbarCache = ScrollbarCache()
    private var lastScrollbarReserves: Map<UiNode, UiScrollbarReserve> = emptyMap()

    internal val inlineFlowChildLayouts = HashMap<UiNode, List<InlinePlacement>>()
    internal val inlineFlowFlattened = HashSet<UiNode>()

    /** The layout this pass may take unchanged subtrees from. */
    internal var reusableLayout: UiLayoutResult? = null

    /** Child edges of the subtrees taken from [reusableLayout]. */
    internal val reusedChildren = IdentityHashMap<UiNode, List<UiNode>>()

    /**
     * Lays out the tree. [previous] lets a subtree that kept its revision and its place be taken as it was; it has
     * to be a layout of the same root, viewport and scroll offsets.
     */
    fun compute(
        resolved: UiNode,
        width: Float,
        height: Float,
        scrollState: UiScrollState = UiScrollState(),
        profile: UiProfileFrame? = null,
        previous: UiLayoutResult? = null,
    ): UiLayoutResult {
        val previousProfile = activeProfile
        activeProfile = profile
        try {
            val warmReserves = lastScrollbarReserves
            reusableLayout = previous
            val warmLayout = computeLayouts(resolved, width, height, warmReserves)
            reusableLayout = null
            val reserves = detectScrollbarReserves(warmLayout) { it.children }
            val finalLayout = if (reserves == warmReserves) {
                warmLayout
            } else {
                lastScrollbarReserves = reserves
                computeLayouts(resolved, width, height, reserves)
            }
            applyScrollRanges(finalLayout, scrollState) { it.children }
            val scrollbars = placeScrollbarNodes(finalLayout, scrollbarCache)
            val traversalOrder = finalLayout.keys.toList()
            val childrenByNode = snapshotVisibleChildren(finalLayout, known = reusedChildren)
            return UiLayoutResult(
                root = resolved,
                nodes = finalLayout,
                traversalOrder = traversalOrder,
                scrollbars = scrollbars,
                childrenByNode = childrenByNode,
            )
        } finally {
            reusableLayout = null
            reusedChildren.clear()
            activeProfile = previousProfile
            measureDepth = 0
        }
    }

    /**
     * Re-places only the subtrees whose scroll offset moved, reusing everything else from [previous].
     */
    internal fun rescroll(
        resolved: UiNode,
        previous: UiLayoutResult,
        scrollState: UiScrollState,
        profile: UiProfileFrame? = null,
    ): UiLayoutResult? {
        val moved = movedScrollContainers(previous) ?: return null
        if (moved.isEmpty()) return previous
        val previousProfile = activeProfile
        activeProfile = profile
        try {
            val layouts = LinkedHashMap(previous.nodes)
            if (profile != null) {
                profile.measurePasses++
                profile.placementPasses++
                profile.incrementalLayouts++
            }
            for (container in moved) replaceScrolledSubtree(container, resolved, layouts)
            applyScrollRanges(layouts, scrollState) { node -> previous.childrenOf(node) }
            val scrollbars = placeScrollbarNodes(layouts, scrollbarCache)
            return UiLayoutResult(
                root = resolved,
                nodes = layouts,
                traversalOrder = layouts.keys.toList(),
                scrollbars = scrollbars,
                childrenByNode = previous.childrenByNode,
            )
        } finally {
            activeProfile = previousProfile
            measureDepth = 0
        }
    }

    private fun movedScrollContainers(previous: UiLayoutResult): List<UiNode>? {
        var moved: MutableList<UiNode>? = null
        for ((node, layout) in previous.nodes) {
            val spec = node.scrollSpec() ?: continue
            if (spec.state.offset == layout.scrollOffset) continue
            if (node !== previous.root && node.layoutState.parentNode == null) return null
            (moved ?: ArrayList<UiNode>(2).also { moved = it }).add(node)
        }
        val candidates = moved ?: return emptyList()
        if (candidates.size == 1) return candidates
        return candidates.filterNot { candidate ->
            candidates.any { other -> other !== candidate && candidate.isDescendantOf(other) }
        }
    }

    private fun UiNode.isDescendantOf(ancestor: UiNode): Boolean {
        var parent = layoutState.parentNode
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.layoutState.parentNode
        }
        return false
    }

    private fun replaceScrolledSubtree(
        container: UiNode,
        resolved: UiNode,
        layouts: MutableMap<UiNode, UiLayoutNode>,
    ) {
        val existing = layouts[container] ?: return
        val offset = container.scrollSpec()?.state?.offset ?: UiScrollOffset.Zero
        layouts[container] = existing.copy(scrollOffset = offset)
        if (container.children.isEmpty()) return
        val content = existing.content
        val style = resolved[container]
        val policy = container.measurePolicy
        policy.policy().place(
            this,
            ChildPlacementScope(
                node = container,
                resolved = resolved,
                style = style,
                measurePolicy = policy,
                content = content.copy(x = content.x - offset.x, y = content.y - offset.y),
                parentRect = existing.rect,
                transform = existing.worldTransform,
                inputTransform = existing.inputTransform,
                clip = existing.clip,
                insideFramebuffer = existing.insideFramebuffer || existing.needsFramebuffer,
                scrollbarReserves = lastScrollbarReserves,
                layouts = layouts,
            ),
        )
    }

    private fun computeLayouts(
        resolved: UiNode,
        width: Float,
        height: Float,
        scrollbarReserves: Map<UiNode, UiScrollbarReserve>,
    ): MutableMap<UiNode, UiLayoutNode> {
        val layouts = linkedMapOf<UiNode, UiLayoutNode>()
        val viewport = UiRect(0f, 0f, width, height)
        inlineFlowChildLayouts.clear()
        inlineFlowFlattened.clear()
        reusedChildren.clear()
        val profile = activeProfile
        if (profile != null) {
            profile.measurePasses++
            profile.placementPasses++
        }
        val rootRect = rootRect(resolved, width, height, scrollbarReserves)
        val placementStartedAt = if (profile != null) System.nanoTime() else 0L
        val measureBeforePlacement = profile?.measureNanos ?: 0L
        placeNodeNow(
            node = resolved,
            resolved = resolved,
            rect = rootRect,
            parentRect = viewport,
            parentClip = null,
            parentTransform = UiMatrix4.identity(),
            parentInputTransform = UiMatrix4.identity(),
            insideFramebuffer = false,
            scrollbarReserves = scrollbarReserves,
            layouts = layouts,
        )
        if (profile != null) {
            val nestedMeasureNanos = profile.measureNanos - measureBeforePlacement
            profile.placementNanos += (System.nanoTime() - placementStartedAt - nestedMeasureNanos).coerceAtLeast(0L)
        }
        return layouts
    }

    internal fun layoutChildren(node: UiNode): List<UiNode> = node.children
}
