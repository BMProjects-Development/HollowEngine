package ru.hollowhorizon.hollowengine.client.models.gltf

import kotlinx.serialization.Serializable
import org.lwjgl.opengl.GL11

@Serializable
data class GltfSampler(
    val magFilter: Int = GL11.GL_LINEAR,
    val minFilter: Int = GL11.GL_LINEAR,
    val wrapS: Int = GL11.GL_REPEAT,
    val wrapT: Int = GL11.GL_REPEAT,
    val name: String? = null,
)