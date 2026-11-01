package ru.hollowhorizon.hollowengine.client.ui.text

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import org.lwjgl.opengl.GL11
import ru.hollowhorizon.hollowengine.client.utils.font.DynamicGlyphAtlas
import ru.hollowhorizon.hollowengine.client.utils.font.GlyphCellUploader
import ru.hollowhorizon.hollowengine.client.utils.font.MsdfBakeSpec
import ru.hollowhorizon.hollowengine.client.utils.font.MsdfGlyph

/**
 * The page's texture, and the one place a baked cell becomes pixels on it.
 */
internal class GlyphAtlasTexture(val size: Int) : GlyphCellUploader {
    val textureId: Int = GL11.glGenTextures()
    private var staging = NativeImage(InitialStagingSize, InitialStagingSize, false)

    init {
        RenderSystem.bindTexture(textureId)
        TextureUtil.prepareImage(textureId, size, size)
        NativeImage(size, size, false).use {
            it.upload(0, 0, 0, 0, 0, size, size, true, true, false, false)
        }
    }

    override fun upload(x: Int, y: Int, width: Int, height: Int, rgb: ByteArray) {
        val image = stagingFor(width, height)
        var source = 0
        for (row in 0 until height) {
            for (column in 0 until width) {
                val red = rgb[source].toInt() and 0xFF
                val green = rgb[source + 1].toInt() and 0xFF
                val blue = rgb[source + 2].toInt() and 0xFF
                image.setPixelRGBA(column, row, (0xFF shl 24) or (blue shl 16) or (green shl 8) or red)
                source += 3
            }
        }
        RenderSystem.bindTexture(textureId)
        image.upload(0, x, y, 0, 0, width, height, true, true, false, false)
    }

    private fun stagingFor(width: Int, height: Int): NativeImage {
        val current = staging
        if (width <= current.width && height <= current.height) return current
        val needed = maxOf(current.width, width, height)
        var side = InitialStagingSize
        while (side < needed) side *= 2
        current.close()
        return NativeImage(side, side, false).also { staging = it }
    }

    fun close() {
        GL11.glDeleteTextures(textureId)
        staging.close()
    }

    private companion object {
        const val InitialStagingSize = 128
    }
}

internal class UiDynamicGlyphFont(
    private val metrics: UiMsdfFontMetrics,
    private val atlas: DynamicGlyphAtlas,
    private val texture: GlyphAtlasTexture,
    spec: MsdfBakeSpec,
) : UiGlyphFont {
    private val page = UiGlyphAtlasPage(
        textureId = texture.textureId,
        width = texture.size.toFloat(),
        height = texture.size.toFloat(),
        distanceRange = spec.pixelRange,
        sampling = UiGlyphSampling.MSDF,
    )

    private val placed = Int2ObjectOpenHashMap<UiPlacedGlyph>()
    private var placedEpoch = -1

    override val lineHeight: Float get() = metrics.meta.metrics.lineHeight
    override val ascender: Float get() = metrics.meta.metrics.ascender
    override val descender: Float get() = metrics.meta.metrics.descender
    override val underlineY: Float get() = metrics.meta.metrics.underlineY
    override val underlineThickness: Float get() = metrics.meta.metrics.underlineThickness
    override val emPixels: Float = spec.pixelSize

    override fun glyph(codepoint: Int): UiPlacedGlyph? {
        if (placedEpoch != atlas.epoch) {
            placed.clear()
            placedEpoch = atlas.epoch
        }
        placed[codepoint]?.let { return it }
        val drawn = if (metrics.covers(codepoint)) codepoint else UiMsdfFont.FallbackGlyph.code
        val glyph = atlas.glyphOf(drawn) ?: return null
        if (glyph.isEmpty()) return null
        return glyph.place(page).also { placed[codepoint] = it }
    }

    override fun advance(codepoint: Int): Float = metrics.advance(codepoint, 1f)
}

internal fun MsdfGlyph.place(page: UiGlyphAtlasPage): UiPlacedGlyph = UiPlacedGlyph(
    advance = advance,
    left = planeBounds.left,
    top = -planeBounds.top,
    right = planeBounds.right,
    bottom = -planeBounds.bottom,
    uMin = atlasBounds.left / page.width,
    vTop = atlasBounds.top / page.height,
    uMax = atlasBounds.right / page.width,
    vBottom = atlasBounds.bottom / page.height,
    page = page,
)
