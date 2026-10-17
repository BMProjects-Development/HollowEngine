package ru.hollowhorizon.hollowengine.client.models.gltf

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.hollowhorizon.hollowengine.common.utils.nbt.ListOrSingle

@Serializable
data class GltfAnimation(
    val channels: ListOrSingle<Channel>,
    val samplers: ListOrSingle<Sampler>,
    val name: String? = null,
    val speed: Float = 1f
) {

    @Serializable
    data class Channel(
        val sampler: Int,
        val target: Target,
    ) {
        @Transient
        lateinit var samplerRef: Sampler
    }

    @Serializable
    data class Target(
        val node: Int = -1,
        val path: String,
    ) {
        @Transient
        var nodeRef: GltfNode? = null
    }

    @Serializable
    data class Sampler(
        val input: Int,
        val interpolation: String = "LINEAR",
        val output: Int,
    ) {
        @Transient
        lateinit var inputAccessorRef: GltfAccessor

        @Transient
        lateinit var outputAccessorRef: GltfAccessor
    }
}