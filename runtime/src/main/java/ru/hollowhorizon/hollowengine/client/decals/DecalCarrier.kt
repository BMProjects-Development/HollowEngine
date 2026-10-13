package ru.hollowhorizon.hollowengine.client.decals

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler

/**
 * The entity that carries a decal: the one the decal is placed inside of, which wears the picture while every other
 * entity is left alone; see [DecalDepth.blocks].
 */
internal object DecalCarrier {
    /** The box of the entity [source] lies inside of, camera-relative from [camera], where it is drawn; or null. */
    fun of(source: Vector3f, camera: Vec3): AABB? {
        val level = Minecraft.getInstance().level ?: return null
        val point = Vec3(camera.x + source.x, camera.y + source.y, camera.z + source.z)
        val partialTick = TickHandler.partialTick
        return level.getEntities(null as Entity?, AABB.ofSize(point, SEARCH, SEARCH, SEARCH)).asSequence()
            .filter { it.isAlive && !it.isSpectator }
            .map { entity -> entity.boundingBox.move(entity.getPosition(partialTick).subtract(entity.position())) }
            .filter { it.inflate(MARGIN).contains(point) }
            .minByOrNull { it.size }
            ?.move(-camera.x, -camera.y, -camera.z)
    }

    /** How far from its place a decal looks for the entity carrying it, in blocks; boxes are found by overlap. */
    private const val SEARCH = 0.5

    /** How far outside an entity the place of a decal may be for the entity to count as carrying it. */
    private const val MARGIN = 0.05
}

/**
 * Runs an upload from client memory with the unpack state it expects: the game and the Media addon leave a pixel
 * buffer bound and row lengths and skips set, and an upload under them reads past the buffer it is given.
 */
internal inline fun plainUnpack(upload: () -> Unit) {
    val buffer = GL33.glGetInteger(GL33.GL_PIXEL_UNPACK_BUFFER_BINDING)
    val rowLength = GL33.glGetInteger(GL33.GL_UNPACK_ROW_LENGTH)
    val skipRows = GL33.glGetInteger(GL33.GL_UNPACK_SKIP_ROWS)
    val skipPixels = GL33.glGetInteger(GL33.GL_UNPACK_SKIP_PIXELS)
    val alignment = GL33.glGetInteger(GL33.GL_UNPACK_ALIGNMENT)
    GL33.glBindBuffer(GL33.GL_PIXEL_UNPACK_BUFFER, 0)
    GL33.glPixelStorei(GL33.GL_UNPACK_ROW_LENGTH, 0)
    GL33.glPixelStorei(GL33.GL_UNPACK_SKIP_ROWS, 0)
    GL33.glPixelStorei(GL33.GL_UNPACK_SKIP_PIXELS, 0)
    GL33.glPixelStorei(GL33.GL_UNPACK_ALIGNMENT, 1)
    try {
        upload()
    } finally {
        GL33.glBindBuffer(GL33.GL_PIXEL_UNPACK_BUFFER, buffer)
        GL33.glPixelStorei(GL33.GL_UNPACK_ROW_LENGTH, rowLength)
        GL33.glPixelStorei(GL33.GL_UNPACK_SKIP_ROWS, skipRows)
        GL33.glPixelStorei(GL33.GL_UNPACK_SKIP_PIXELS, skipPixels)
        GL33.glPixelStorei(GL33.GL_UNPACK_ALIGNMENT, alignment)
    }
}
