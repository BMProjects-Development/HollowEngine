package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import java.util.Collections
import java.util.IdentityHashMap

/** Source paths for the active model scene, shared by every instance of the asset. */
class ModelNodePaths internal constructor(roots: List<NodeDefinition>) {
    private val nodesByPath = LinkedHashMap<List<ScenePathSegment>, NodeDefinition>()
    private val pathsByNode = IdentityHashMap<NodeDefinition, List<ScenePathSegment>>()

    init {
        val visitedSynthetic = Collections.newSetFromMap(IdentityHashMap<NodeDefinition, Boolean>())
        fun authored(nodes: List<NodeDefinition>): List<NodeDefinition> = buildList {
            nodes.forEach { node ->
                if (node.isSynthetic) {
                    require(visitedSynthetic.add(node)) { "A synthetic model node occurs more than once in the active scene" }
                    addAll(authored(node.children))
                } else add(node)
            }
        }

        fun visit(siblings: List<NodeDefinition>, parent: List<ScenePathSegment>) {
            val counts = HashMap<String, Int>()
            authored(siblings).forEach { node ->
                val name = node.name?.takeUnless(String::isBlank) ?: "Node_${node.index}"
                val occurrence = (counts[name] ?: 0) + 1
                counts[name] = occurrence
                val path = parent + ScenePathSegment(name, occurrence)
                nodesByPath[path] = node
                require(pathsByNode.put(node, path) == null) { "A model node occurs more than once in the active scene" }
                visit(node.children, path)
            }
        }

        visit(roots, emptyList())
    }

    fun node(path: List<ScenePathSegment>): NodeDefinition? = nodesByPath[path]

    fun path(node: NodeDefinition): List<ScenePathSegment>? = pathsByNode[node]?.toList()

    internal fun segment(node: NodeDefinition): ScenePathSegment? = pathsByNode[node]?.lastOrNull()
}
