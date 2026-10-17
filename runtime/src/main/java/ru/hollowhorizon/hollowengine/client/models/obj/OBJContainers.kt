package ru.hollowhorizon.hollowengine.client.models.obj

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

class OBJDataMesh {
    var name: String? = null
    var groups: MutableMap<OBJMaterial, MutableList<OBJFace>> = LinkedHashMap()

    fun toNode(
        materials: Map<OBJMaterial, Material>,
        positions: MutableList<Vec3f>,
        normals: MutableList<Vec3f>,
        textures: MutableList<Vec2f>,
        index: Int,
    ): NodeDefinition {
        return NodeDefinition(
            index,
            name = name,
            mutableListOf(),
            TrsTransformF(),
            Mesh(groups.map { (material, faces) ->
                createPrimitive(
                    materials[material] ?: error("Material ${material.name} not found!"),
                    positions, normals, textures, faces
                )
            }, floatArrayOf())
        )
    }

    fun createPrimitive(
        material: Material,
        positions: MutableList<Vec3f>,
        normals: MutableList<Vec3f>,
        textures: MutableList<Vec2f>,
        faces: List<OBJFace>,
    ): Primitive {
        val faces = faces.flatMap { it.idxGroups.toList() }

        return Primitive(
            positions = faces.map { positions[it.idxPos] }.toTypedArray(),
            normals = faces.map {
                if (it.idxVecNormal != OBJIndexGroup.NO_VALUE) normals[it.idxVecNormal] else Vec3f(
                    0f,
                    0f,
                    0f
                )
            }.toTypedArray(),
            texCoords = faces.map {
                if (it.idxTextCoord != OBJIndexGroup.NO_VALUE) textures[it.idxTextCoord] else Vec2f(
                    0f,
                    0f
                )
            }.toTypedArray(),
            material = material
        )
    }

}

data class OBJMaterial(var name: String) {
    var r: Float = 1.0f
    var g: Float = 1.0f
    var b: Float = 1.0f
    var a: Float = 1.0f
    var hasTexture: Boolean = false
    var linear: Boolean = false
    var texture: ResourceLocation? = null
    var normalTexture: ResourceLocation? = null
    var specularTexture: ResourceLocation? = null
}

class OBJFace(lines: Array<String>) {
    val idxGroups: Array<OBJIndexGroup> = (0..2).map { parseLine(lines[it]) }.toTypedArray()

    private fun parseLine(line: String): OBJIndexGroup {
        val idxGroup = OBJIndexGroup()
        val lineTokens = line.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val length = lineTokens.size
        idxGroup.idxPos = lineTokens[0].toInt() - 1
        if (length > 1) {
            val textCoord = lineTokens[1]
            if (textCoord.isNotEmpty()) idxGroup.idxTextCoord = textCoord.toInt() - 1
            if (length > 2) idxGroup.idxVecNormal = lineTokens[2].toInt() - 1
        }

        return idxGroup
    }
}

class OBJIndexGroup {
    var idxPos: Int = -1
    var idxTextCoord: Int = -1
    var idxVecNormal: Int = -1

    companion object {
        const val NO_VALUE: Int = -1
    }
}
