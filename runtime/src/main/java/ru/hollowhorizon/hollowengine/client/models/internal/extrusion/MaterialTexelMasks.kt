package ru.hollowhorizon.hollowengine.client.models.internal.extrusion

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.client.renderer.texture.Tickable
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.system.MemoryUtil
import ru.hollowhorizon.hollowengine.client.models.internal.Material

/** Reads the same uploaded texels the material samples, including Minecraft's normalized downloaded skins. */
internal object MaterialTexelMasks {
    private const val MAX_TEXELS = 262_144
    private const val MAX_CACHED_MASKS = 64
    private val cache = LinkedHashMap<Texture, TexelMask?>(16, 0.75f, true)

    class Texture(private val owner: Any, val id: Int) {
        override fun equals(other: Any?): Boolean = other is Texture && other.owner === owner && other.id == id
        override fun hashCode(): Int = 31 * System.identityHashCode(owner) + id
    }

    var revision = 0
        private set

    fun invalidate() {
        cache.clear()
        revision++
    }

    fun texture(material: Material): Texture? {
        if (material.texture == Material.MISSING_TEXTURE) {
            val content = material.textureContent ?: return null
            if (content.animated) return null
            return Texture(content, content.textureId())
        }
        val texture = Minecraft.getInstance().textureManager.getTexture(material.texture)
        if (texture === MissingTextureAtlasSprite.getTexture() || texture is Tickable) return null
        return Texture(texture, texture.id)
    }

    fun get(texture: Texture): TexelMask? {
        if (cache.containsKey(texture)) return cache[texture]
        val mask = read(texture.id)
        if (!mask.ready) return null
        cache[texture] = mask.mask
        if (cache.size > MAX_CACHED_MASKS) cache.remove(cache.keys.first())
        return mask.mask
    }

    fun isReady(texture: Texture): Boolean = cache.containsKey(texture)

    private data class ReadResult(val ready: Boolean, val mask: TexelMask? = null)

    private fun read(texture: Int): ReadResult {
        val binding = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
        RenderSystem.bindTexture(texture)
        try {
            val width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            val height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
            if (width <= 0 || height <= 0) return ReadResult(ready = false)
            if (width.toLong() * height > MAX_TEXELS) return ReadResult(ready = true)

            val packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
            val packSettings = intArrayOf(
                GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
            )
            val previous = packSettings.map(GL11::glGetInteger)
            val rgba = MemoryUtil.memAlloc(width * height * 4)
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
                packSettings.drop(1).forEach { GL11.glPixelStorei(it, 0) }
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba)
                val visible = BooleanArray(width * height) { rgba.get(it * 4 + 3) != 0.toByte() }
                return ReadResult(ready = true, TexelMask(width, height, visible))
            } finally {
                MemoryUtil.memFree(rgba)
                packSettings.forEachIndexed { index, setting -> GL11.glPixelStorei(setting, previous[index]) }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer)
            }
        } finally {
            RenderSystem.bindTexture(binding)
        }
    }
}
