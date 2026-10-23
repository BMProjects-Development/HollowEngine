package ru.hollowhorizon.hollowengine.client.models.bbmodel

import ru.hollowhorizon.hollowengine.client.models.internal.Skin
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.SkinInfluence
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4i

internal object BBArmature {
    fun bones(armature: BBNode, nodes: Collection<BBNode>): List<BBNode> = nodes.filter { node ->
        node.element.type == "armature_bone" && generateSequence(node.parent) { it.parent }
            .firstOrNull { it.element.type == "armature" } === armature
    }

    fun applyWeights(node: BBNode, skin: Skin, nodes: Collection<BBNode>, geometry: List<BBGeometryPart>): List<Primitive> {
        val bones = bones(requireNotNull(node.parent), nodes)
        val prefix = node.element.uuid.take(6) + ":"
        val weights = node.element.vertices.keys.associateWith { vertex ->
            bones.mapIndexedNotNull { joint, bone ->
                val weight = bone.element.vertexWeights[prefix + vertex] ?: bone.element.vertexWeights[vertex] ?: 0f
                require(weight.isFinite() && weight >= 0f) { "Invalid skin weight for $vertex on ${bone.element.uuid}" }
                if (weight > 0f) joint to weight else null
            }.ifEmpty { listOf(bones.size to 1f) }
        }
        return geometry.map { part ->
            val influences = part.vertexKeys.map { key ->
                val entries = weights.getValue(key)
                val sum = entries.sumOf { it.second.toDouble() }
                entries.map { SkinInfluence(it.first, (it.second / sum).toFloat()) }
            }
            val joints = influences.map { entries ->
                val ids = List(4) { entries.getOrNull(it)?.joint ?: 0 }
                Vec4i(ids[0], ids[1], ids[2], ids[3])
            }.toTypedArray()
            val strengths = influences.map { entries ->
                val values = List(4) { entries.getOrNull(it)?.weight ?: 0f }
                Vec4f(values[0], values[1], values[2], values[3])
            }.toTypedArray()
            Primitive(
                positions = part.primitive.positions,
                normals = part.primitive.normals,
                texCoords = part.primitive.texCoords,
                joints = joints,
                jointWeights = strengths,
                indices = part.primitive.indices,
                material = part.primitive.material,
                additionalSkinWeights = influences.takeIf { it.any { entries -> entries.size > 4 } }
                    ?.map { it.drop(4) }?.toTypedArray(),
            ).also { it.jointCount = skin.jointsIds.size }
        }
    }
}
