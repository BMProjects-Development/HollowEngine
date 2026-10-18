package ru.hollowhorizon.hollowengine.client.models.gltf

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.hollowhorizon.hollowengine.common.utils.nbt.ListOrSingle

@Serializable
data class GltfMesh(
    val primitives: ListOrSingle<Primitive>,
    val weights: List<Float> = listOf(),
    val name: String? = null,
    val extras: ListOrSingle<MeshExtras> = listOf(),
) {
    @Serializable
    data class MeshExtras(
        val targetNames: ListOrSingle<String> = listOf(),
    )

    @Serializable
    data class Primitive(
        val attributes: Map<String, Int>,
        val indices: Int = -1,
        val material: Int = -1,
        val mode: Int = 4,
        val targets: List<Map<String, Int>> = emptyList(),
    ) {
        @Transient
        var materialRef: GltfMaterial? = null

        companion object {
            const val ATTRIBUTE_POSITION = "POSITION"
            const val ATTRIBUTE_NORMAL = "NORMAL"
            const val ATTRIBUTE_TANGENT = "TANGENT"
            const val ATTRIBUTE_TEXCOORD_0 = "TEXCOORD_0"
            const val ATTRIBUTE_TEXCOORD_1 = "TEXCOORD_1"
            const val ATTRIBUTE_COLOR_0 = "COLOR_0"
            const val ATTRIBUTE_JOINTS_0 = "JOINTS_0"
            const val ATTRIBUTE_WEIGHTS_0 = "WEIGHTS_0"
        }
    }
}