package ru.hollowhorizon.hollowengine.client.models.internal

/** A model-owned texture. Creation and destruction of its GL resources happen on the render thread. */
interface ModelTexture : AutoCloseable {
    val animated: Boolean get() = false
    fun textureId(): Int
}
