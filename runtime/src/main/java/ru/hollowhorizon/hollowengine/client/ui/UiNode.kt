package ru.hollowhorizon.hollowengine.client.ui

import ru.hollowhorizon.hollowengine.client.ui.layout.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiComputedStyle
import ru.hollowhorizon.hollowengine.client.ui.style.defaultModifierSnapshot
import java.util.*

interface UiNode {
    val type: String
    val id: String?
    var measurePolicy: UiMeasurePolicy
    val tags: MutableSet<String>
    val attributes: MutableMap<String, String>
    val states: MutableSet<UiState>
    val modifiers: MutableList<Modifier>
    var resolvedModifiers: List<Modifier>

    /** The canvases among [resolvedModifiers]; most nodes have none, and all of them are asked twice a frame. */
    val resolvedCanvases: List<UiCanvasModifier>

    /** Those of [resolvedModifiers] that layout reports to; the rest are not worth looking through once a frame. */
    val resolvedReports: List<Modifier>
    var resolvedSnapshot: UiComputedStyle
    val children: UiChildren
    val layoutState: UiNodeLayoutState
}

typealias UiChildren = MutableList<UiNode>

fun UiChildren(): UiChildren = mutableListOf()

val UiNode.root: UiNode get() = this

operator fun UiNode.get(node: UiNode): UiComputedStyle = node.resolvedSnapshot

fun UiNode.firstInSubtree(predicate: (UiNode) -> Boolean): UiNode? {
    val stack = ArrayDeque<UiNode>()
    stack.add(this)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (predicate(node)) return node
        for (index in node.children.indices.reversed()) stack.add(node.children[index])
    }
    return null
}

open class BaseUiNode(
    final override val type: String,
    final override val id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifiers: Iterable<Modifier> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
    measurePolicy: UiMeasurePolicy = UiMeasurePolicies.Column,
) : UiNode {
    final override val layoutState = UiNodeLayoutState()
    final override val tags: MutableSet<String> = InvalidatingMutableSet(tags) { invalidateDraw() }
    final override val attributes: MutableMap<String, String> =
        InvalidatingMutableMap(attributes) { invalidateDraw() }
    final override val states: MutableSet<UiState> = InvalidatingMutableSet { invalidateDraw() }
    final override val modifiers: MutableList<Modifier> =
        InvalidatingMutableList(modifiers) { invalidateDraw() }
    final override var resolvedModifiers: List<Modifier> = modifiers.toList()
        set(value) {
            if (field === value) return
            field = value
            resolvedCanvases = value.filterIsInstance<UiCanvasModifier>()
            resolvedReports = value.filter { it.reportsLayout() }
        }
    final override var resolvedCanvases: List<UiCanvasModifier> = resolvedModifiers.filterIsInstance<UiCanvasModifier>()
        private set
    final override var resolvedReports: List<Modifier> = resolvedModifiers.filter { it.reportsLayout() }
        private set
    final override var resolvedSnapshot: UiComputedStyle = defaultModifierSnapshot()
    final override val children = UiChildren()
    final override var measurePolicy: UiMeasurePolicy = measurePolicy
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    /**
     * Per-line box-decoration geometry computed by a parent inline flow when this node is an inline
     * group (a span or a nested inline flow). Read back into the layout node so background/border
     * can be painted per line. Null for nodes that are not part of an inline flow.
     */
    var inlineDecoration: InlineGroupDecoration? = null
        internal set

    fun add(vararg modifiers: Modifier): BaseUiNode = apply {
        this.modifiers.addAll(modifiers)
    }

    fun tag(vararg values: String): BaseUiNode = apply {
        tags.addAll(values.map { it.trimTagPrefix() })
    }

    fun state(vararg values: UiState): BaseUiNode = apply {
        states.addAll(values)
    }
}

class BoxNode(
    id: String? = null,
    measurePolicy: UiMeasurePolicy = UiMeasurePolicies.box(),
    tags: Iterable<String> = emptyList(),
    modifiers: Iterable<Modifier> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
) : BaseUiNode(
    UiBoxType,
    id?.trimIdPrefix(),
    tags.map { it.trimTagPrefix() },
    modifiers,
    attributes,
    measurePolicy,
)

/**
 * A popup: the content container of an open overlay. It lives only as a child of the OverlayHost's
 * popup container (see `PopupOverlayMeasurePolicy`), which places it absolutely at [anchorBounds] (its
 * anchor's bounds in root coordinates, supplied by the caller via `Modifier.onPlaced` or a cursor
 * point) with [alignment].
 */
class PopupNode(
    anchorBounds: UiRect,
    alignment: UiPopupAlignment = UiPopupAlignment.BelowStart,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifiers: Iterable<Modifier> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
) : BaseUiNode(
    UiPopupType,
    id?.trimIdPrefix(),
    tags.map { it.trimTagPrefix() },
    modifiers,
    attributes,
    UiMeasurePolicies.Column,
) {
    var anchorBounds: UiRect = anchorBounds
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }

    var alignment: UiPopupAlignment = alignment
        set(value) {
            if (field == value) return
            field = value
            invalidateLayout()
        }
}

private fun String.trimIdPrefix() = removePrefix("#")

private fun String.trimTagPrefix() = removePrefix(".")
