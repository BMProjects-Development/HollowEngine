package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.addedBones

/**
 * Builds the bones [rig] adds to a model, under the nodes of [roots], wherever a model's nodes are built, so
 * what hangs on them and the chains that end on them work alike when drawn and on the server.
 */
fun addRigBones(roots: List<RuntimeNode>, rig: ModelRig, holder: Attachment?): List<RuntimeNode> {
    val added = rig.addedBones
    if (added.isEmpty()) return roots

    val nodes = HashMap<String, RuntimeNode>()
    roots.forEach { root -> root.walk().forEach { nodes.putIfAbsent(it.name, it) } }
    var index = (nodes.values.maxOfOrNull { it.definition.index } ?: -1) + 1
    val result = roots.toMutableList()

    val pending = added.filterKeys { it !in nodes }.toMutableMap()
    while (pending.isNotEmpty()) {
        val ready = pending.filterValues { it.parent == null || it.parent in nodes }
        if (ready.isEmpty()) break
        ready.forEach { (name, origin) ->
            val definition = NodeDefinition(index++, name, mutableListOf(), origin.localTransform())
            val parent = origin.parent?.let(nodes::getValue)
            nodes[name] = parent?.adopt(definition) ?: RuntimeNode(definition, holder).also(result::add)
            pending.remove(name)
        }
    }
    return result
}
