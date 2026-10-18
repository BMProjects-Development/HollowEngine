package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.UiNode
import ru.hollowhorizon.hollowengine.client.ui.scroll.ScrollbarNode
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollOffset
import ru.hollowhorizon.hollowengine.client.ui.style.UiComputedStyle
import ru.hollowhorizon.hollowengine.client.ui.style.layer
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayout
import java.util.*

data class UiRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun contains(px: Float, py: Float): Boolean = px >= x && py >= y && px <= x + width && py <= y + height

    /** Whether this rectangle shares any area with [other] (touching edges alone do not count). */
    fun overlaps(other: UiRect): Boolean =
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height

    companion object {
        val Zero = UiRect(0f, 0f, 0f, 0f)
    }
}


data class UiLayoutNode(
    val node: UiNode,
    val rect: UiRect,
    val content: UiRect,
    val clip: UiRect?,
    val outerClip: UiRect? = null,
    val overflowClip: UiRect = content,
    val worldTransform: UiMatrix4,
    val inputTransform: UiMatrix4,
    val needsFramebuffer: Boolean,
    val insideFramebuffer: Boolean = false,
    val scrollOffset: UiScrollOffset = UiScrollOffset.Zero,
    val scrollRange: UiScrollOffset = UiScrollOffset.Zero,
    val scrollArea: UiRect = content,
    val textLayout: UiTextLayout? = null,
    val inlineDecoration: InlineGroupDecoration? = null,
)

data class UiLayoutResult(
    val root: UiNode,
    val nodes: Map<UiNode, UiLayoutNode>,
    val traversalOrder: List<UiNode> = nodes.keys.toList(),
    /** Framework-synthesized scrollbars per scroll container (not part of node.children). */
    val scrollbars: Map<UiNode, List<ScrollbarNode>> = emptyMap(),
    /** Child edges captured by the layout pass. Completed frames never traverse the live Compose tree. */
    val childrenByNode: Map<UiNode, List<UiNode>> = snapshotVisibleChildren(nodes),
) {
    operator fun get(node: UiNode): UiLayoutNode = nodes.getValue(node)

    fun childrenOf(node: UiNode): List<UiNode> = childrenByNode[node].orEmpty()
}

internal fun snapshotVisibleChildren(
    layouts: Map<UiNode, UiLayoutNode>,
    childrenOf: (UiNode) -> List<UiNode> = { node -> node.children },
): Map<UiNode, List<UiNode>> {
    val result = IdentityHashMap<UiNode, List<UiNode>>()
    for (node in layouts.keys) {
        val source = childrenOf(node)
        if (source.isEmpty()) continue
        var visible: ArrayList<UiNode>? = null
        for (child in source) {
            if (child !in layouts) continue
            (visible ?: ArrayList<UiNode>(source.size).also { visible = it }).add(child)
        }
        val children = visible ?: continue
        if (children.size > 1 && children.any { it.resolvedSnapshot.layer != children[0].resolvedSnapshot.layer }) {
            children.sortBy { it.resolvedSnapshot.layer }
        }
        result[node] = children
    }
    return result
}

internal data class UiScrollbarReserve(
    val vertical: Boolean = false,
    val horizontal: Boolean = false,
) {
    val active: Boolean get() = vertical || horizontal

    companion object {
        val None = UiScrollbarReserve()
    }
}

internal data class LayoutSize(val width: Float, val height: Float)

internal data class MeasuredChild(
    val node: UiNode,
    val style: UiComputedStyle,
    val size: LayoutSize,
    val margin: ResolvedUiInsets,
)

internal data class NodeBoxes(
    val scrollArea: UiRect,
    val content: UiRect,
)


internal data class ResolvedUiInsets(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom
}
