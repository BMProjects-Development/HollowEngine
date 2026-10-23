package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec4f
import ru.hollowhorizon.hollowengine.client.models.internal.Material as InternalMaterial
import ru.hollowhorizon.hollowengine.client.models.internal.Skin as InternalSkin

internal fun convertMesh(
    mesh: MeshGeometry,
    model: Model,
    skin: InternalSkin?,
    material: (Material) -> InternalMaterial,
): List<Primitive> {
    val (joints, weights) = skinData(mesh, skin)
    val morphs = morphTargets(mesh)
    val slots = mesh.materials.distinct()
    return slots.map { slot ->
        val sourceMaterial = if (slot < 0) null else model.materials.getOrNull(slot) ?: domError(
            "material slot $slot is out of range",
            mesh.element
        )
        val texture = sourceMaterial?.textures?.get("DiffuseColor")
        val uvSet = texture?.props?.invoke("UVSet", "").orEmpty()
        val uvIndex =
            if (uvSet.isEmpty() || (uvSet == "default" && uvSet !in mesh.uvNames)) 0 else mesh.uvNames.indexOf(uvSet)
                .takeIf { it >= 0 } ?: domError("missing UV set $uvSet", mesh.element)
        val uvTranslation = texture?.uvTrans ?: Vec2f.ZERO
        val uvScaling = texture?.uvScaling ?: Vec2f.ONES
        val transformedUvs = uvTranslation != Vec2f.ZERO || uvScaling != Vec2f.ONES
        val indices =
            mesh.indices.chunked(3).filterIndexed { triangle, _ -> mesh.materials[triangle] == slot }.flatten()
                .toIntArray()
        Primitive(
            positions = mesh.vertices.toTypedArray(),
            normals = mesh.normals.takeIf { it.isNotEmpty() }?.toTypedArray(),
            texCoords = mesh.getTextureCoords(uvIndex).takeIf { it.isNotEmpty() }?.map {
                Vec2f(it.x * uvScaling.x + uvTranslation.x, 1f - (it.y * uvScaling.y + uvTranslation.y))
            }?.toTypedArray(),
            tangents = mesh.tangents.takeIf { it.isNotEmpty() && mesh.normals.isNotEmpty() && mesh.binormals.isNotEmpty() && !transformedUvs }
                ?.mapIndexed { index, tangent ->
                    val normal = mesh.normals.getOrNull(index)
                    val binormal = mesh.binormals.getOrNull(index)
                    val sign = if (normal != null && binormal != null && normal.cross(tangent, MutableVec3f())
                            .dot(binormal) < 0f
                    ) -1f else 1f
                    Vec4f(tangent.x, tangent.y, tangent.z, -sign)
                }?.toTypedArray(),
            joints = joints,
            jointWeights = weights,
            indices = indices,
            material = sourceMaterial?.let(material) ?: InternalMaterial(name = "material_default"),
            morphTargets = morphs,
            weights = morphWeights(mesh),
        ).also { it.jointCount = skin?.jointsIds?.size ?: 0 }
    }
}
