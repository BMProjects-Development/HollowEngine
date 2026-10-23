package ru.hollowhorizon.hollowengine.client.models.internal

/** A model-owned texture. Creation and destruction of its GL resources happen on the render thread. */
interface ModelTexture : AutoCloseable {
    fun textureId(): Int
}
