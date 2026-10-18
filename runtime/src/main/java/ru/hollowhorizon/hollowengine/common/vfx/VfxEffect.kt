package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSection
import ru.hollowhorizon.hollowengine.common.playback.TimelineEvent

/** One authored effect: a tree of nodes and timeline that drives them. */
@Serializable
data class VfxEffect(
    val version: Int = CURRENT_VERSION,
    val nodes: List<VfxNodeSpec> = emptyList(),
    val timeline: VfxTimelineSpec = VfxTimelineSpec(),
) {
    /** Every node of tree, parents before children. */
    fun walk(): List<VfxNodeSpec> = nodes.flatMap { it.walkSelf() }

    fun node(id: String): VfxNodeSpec? = walk().firstOrNull { it.id == id }

    /** The node [id] sits under, or null for a root node or an unknown id. */
    fun parentOf(id: String): VfxNodeSpec? = walk().firstOrNull { parent -> parent.children.any { it.id == id } }

    /** Every expression in the file, so they compile as one unit. */
    fun expressions(): List<String> =
        (walk().flatMap { it.expressions() } + timeline.sections.flatMap(PlaybackSection::conditions))
            .filter { it.isNotBlank() }.distinct()

    /** The effect with [node] put in place of the node with the same id, wherever it sits. */
    fun withNode(node: VfxNodeSpec): VfxEffect = copy(nodes = nodes.map { it.replacing(node) })

    fun withoutNode(id: String): VfxEffect = copy(nodes = nodes.mapNotNull { it.removing(id) })

    /** [child] added under [parentId], or at the root when it is null. */
    fun withChild(parentId: String?, child: VfxNodeSpec): VfxEffect {
        if (parentId == null) return copy(nodes = nodes + child)
        return copy(nodes = nodes.map { it.adding(parentId, child) })
    }

    /** Whether [id] is [ancestor] itself or anywhere under it. */
    fun isWithin(id: String, ancestor: String): Boolean =
        node(ancestor)?.walkSelf()?.any { it.id == id } ?: false

    /**
     * The node [id] taken from where it is and put under [parentId] (the root when null) at [index], or
     * at the end when [index] is out of range. A node cannot go under itself; that leaves the effect as
     * it was.
     */
    fun withMoved(id: String, parentId: String?, index: Int = Int.MAX_VALUE): VfxEffect {
        val moved = node(id) ?: return this
        if (parentId != null && isWithin(parentId, id)) return this
        val without = withoutNode(id)
        if (parentId == null) return without.copy(nodes = without.nodes.inserting(moved, index))
        return without.copy(nodes = without.nodes.map { it.inserting(parentId, moved, index) })
    }

    /** The node [id] moved [offset] places among the nodes that share its parent. */
    fun withShifted(id: String, offset: Int): VfxEffect {
        val parent = parentOf(id)
        val siblings = parent?.children ?: nodes
        val from = siblings.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + offset).coerceIn(0, siblings.lastIndex)
        if (to == from) return this
        return withMoved(id, parent?.id, to)
    }

    /**
     * A copy of the node [id] and everything under it, with fresh ids, placed right after it, and a copy
     * of every timeline track that drove the copied nodes. Returns the effect and the id of the copy.
     */
    fun withDuplicate(id: String): Pair<VfxEffect, String>? {
        val original = node(id) ?: return null
        val renamed = HashMap<String, String>()
        val clone = original.copiedWithFreshIds(renamed)

        val parent = parentOf(id)
        val index = (parent?.children ?: nodes).indexOfFirst { it.id == id } + 1
        val placed = if (parent == null) copy(nodes = nodes.inserting(clone, index))
        else copy(nodes = nodes.map { it.inserting(parent.id, clone, index) })

        val tracks = timeline.tracks.mapNotNull { track -> renamed[track.node]?.let { track.copy(node = it) } }
        return placed.copy(timeline = placed.timeline.copy(tracks = placed.timeline.tracks + tracks)) to clone.id
    }

    /** The effect with every node placed at its origin, switched on and unnamed: what placement leaves. */
    fun withoutPlacement(): VfxEffect = copy(nodes = nodes.map { it.withoutPlacement() })

    companion object {
        const val CURRENT_VERSION = 1

        val EMPTY = VfxEffect()
    }
}

private fun VfxNodeSpec.withoutPlacement(): VfxNodeSpec = withCommon(
    name = "",
    enabled = true,
    transform = VfxTransform.IDENTITY,
    children = children.map { it.withoutPlacement() },
)

private fun VfxNodeSpec.walkSelf(): List<VfxNodeSpec> = buildList {
    add(this@walkSelf)
    children.forEach { addAll(it.walkSelf()) }
}

private fun VfxNodeSpec.replacing(node: VfxNodeSpec): VfxNodeSpec {
    if (id == node.id) return node
    if (children.isEmpty()) return this
    return withCommon(children = children.map { it.replacing(node) })
}

private fun VfxNodeSpec.removing(target: String): VfxNodeSpec? {
    if (id == target) return null
    if (children.isEmpty()) return this
    return withCommon(children = children.mapNotNull { it.removing(target) })
}

private fun List<VfxNodeSpec>.inserting(node: VfxNodeSpec, index: Int): List<VfxNodeSpec> =
    toMutableList().also { it.add(index.coerceIn(0, size), node) }

private fun VfxNodeSpec.inserting(parentId: String, child: VfxNodeSpec, index: Int): VfxNodeSpec {
    if (id == parentId) return withCommon(children = children.inserting(child, index))
    if (children.isEmpty()) return this
    return withCommon(children = children.map { it.inserting(parentId, child, index) })
}

/** This node and its children under new ids, recording in [renamed] which old id became which. */
private fun VfxNodeSpec.copiedWithFreshIds(renamed: MutableMap<String, String>): VfxNodeSpec {
    val fresh = newVfxNodeId(id.substringBefore('-').ifBlank { "node" })
    renamed[id] = fresh
    return withCommon(id = fresh, children = children.map { it.copiedWithFreshIds(renamed) })
}

private fun VfxNodeSpec.adding(parentId: String, child: VfxNodeSpec): VfxNodeSpec {
    if (id == parentId) return withCommon(children = children + child)
    if (children.isEmpty()) return this
    return withCommon(children = children.map { it.adding(parentId, child) })
}

@Serializable
data class VfxTrackCurve(
    val channel: Int = 0,
    val visible: Boolean = true,
    val keys: List<VfxKey> = emptyList(),
)

/**
 * One animated property of one node. One curve per scalar component of it.
 */
@Serializable
data class VfxTrack(
    val node: String = "",
    val property: VfxProperty = VfxProperty.ENABLED,
    val curves: List<VfxTrackCurve> = emptyList(),
)

@Serializable
data class VfxTimelineSpec(
    val duration: Float = 5f,
    val loop: Boolean = true,
    val tracks: List<VfxTrack> = emptyList(),
    val events: List<TimelineEvent> = emptyList(),
    val sections: List<PlaybackSection> = emptyList(),
) {
    fun track(node: String, property: VfxProperty): VfxTrack? =
        tracks.firstOrNull { it.node == node && it.property == property }

    val isEmpty: Boolean get() = tracks.isEmpty()

    /** Whether any section has rules, so the timeline does not simply run from start to end. */
    val hasRules: Boolean get() = sections.any(PlaybackSection::hasRules)
}
