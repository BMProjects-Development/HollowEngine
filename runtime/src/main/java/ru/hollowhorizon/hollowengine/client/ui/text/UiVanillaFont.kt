package ru.hollowhorizon.hollowengine.client.ui.text

import com.mojang.blaze3d.platform.NativeImage
import kotlinx.serialization.json.*
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.json.json
import java.util.concurrent.ConcurrentHashMap

/**
 * Minecraft's own font *assets*, `assets/<namespace>/font/<name>.json` plus the bitmap sheets it points at.
 */
object UiVanillaFont {
    const val FamilyPrefix = "vanilla"

    /** Pixels per em. Vanilla's nominal glyph box, from which its 9px line height derives. */
    const val EmPixels = 8f

    /** Baseline offset from the line's top edge: the `7.0f` bearing `SheetGlyphInfo` measures against. */
    const val BaselinePixels = 7f

    private const val LineHeightPixels = 9f
    private const val DefaultBitmapHeight = 8
    private const val FallbackGlyph = '�'
    private const val LegacyFallbackGlyph = '?'
    private const val FallbackAdvancePixels = 6f
    private const val RetryCooldownNanos = 2_000_000_000L

    private val faces = ConcurrentHashMap<ResourceLocation, UiVanillaFontFace>()
    private val glyphFonts = ConcurrentHashMap<ResourceLocation, UiVanillaGlyphFont>()
    private val failedAt = ConcurrentHashMap<ResourceLocation, Long>()

    @Volatile
    private var epoch = UiFontResources.generation

    fun isVanillaFamily(fontFamily: String): Boolean =
        fontFamily == FamilyPrefix || fontFamily.startsWith("$FamilyPrefix:")

    fun locationOf(fontFamily: String): ResourceLocation? {
        if (!isVanillaFamily(fontFamily)) return null
        val name = fontFamily.removePrefix(FamilyPrefix).removePrefix(":").ifBlank { "default" }
        return if (':' in name) ResourceLocation.tryParse(name) else ResourceLocation.withDefaultNamespace(name)
    }

    fun face(fontFamily: String): UiVanillaFontFace? {
        val location = locationOf(fontFamily) ?: return null
        dropStaleCaches()
        faces[location]?.let { return it }
        val lastFailure = failedAt[location]
        if (lastFailure != null && System.nanoTime() - lastFailure < RetryCooldownNanos) return null
        return runCatching { loadFace(location) }
            .onFailure {
                failedAt[location] = System.nanoTime()
                HollowEngine.LOGGER.warn("Could not read vanilla font {}: {}", location, it.message)
            }
            .getOrNull()
            ?.also {
                faces[location] = it
                failedAt.remove(location)
            }
    }

    fun glyphFont(fontFamily: String): UiGlyphFont? {
        val location = locationOf(fontFamily) ?: return null
        dropStaleCaches()
        glyphFonts[location]?.let { return it }
        val face = face(fontFamily) ?: return null
        return UiVanillaGlyphFont(face).also { glyphFonts[location] = it }
    }

    fun unloadAll() {
        faces.clear()
        glyphFonts.clear()
        failedAt.clear()
    }

    private fun dropStaleCaches() {
        val generation = UiFontResources.generation
        if (epoch == generation) return
        epoch = generation
        unloadAll()
    }

    private fun loadFace(location: ResourceLocation): UiVanillaFontFace {
        val target = FaceBuilder()
        readFont(location, target, HashSet())
        check(target.glyphs.isNotEmpty()) { "no usable providers in font/${location.path}.json" }
        return UiVanillaFontFace(location, target.glyphs, target.coloredSheets, target.sheetSizes)
    }

    private fun readFont(
        location: ResourceLocation,
        target: FaceBuilder,
        visited: MutableSet<ResourceLocation>,
    ) {
        if (!visited.add(location)) return
        val definition = ResourceLocation.fromNamespaceAndPath(location.namespace, "font/${location.path}.json")
        val files = readAssetStack(definition)
        check(files.isNotEmpty()) { "no font definition at $definition" }
        for (file in files.asReversed()) {
            val providers = json.parseToJsonElement(file.decodeToString())
                .jsonObject["providers"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
            for (provider in providers) readProvider(provider, target, visited)
        }
    }

    private fun readProvider(
        provider: JsonObject,
        target: FaceBuilder,
        visited: MutableSet<ResourceLocation>,
    ) {
        when (provider["type"]?.jsonPrimitive?.contentOrNull) {
            "bitmap" -> readBitmapProvider(provider, target)
            "space" -> readSpaceProvider(provider, target)
            "reference" -> readReferenceProvider(provider, target, visited)
            else -> Unit
        }
    }

    /** A reference expands in place, so it inherits the surrounding declaration order. */
    private fun readReferenceProvider(
        provider: JsonObject,
        target: FaceBuilder,
        visited: MutableSet<ResourceLocation>,
    ) {
        val id = provider["id"]?.jsonPrimitive?.contentOrNull?.let(ResourceLocation::tryParse) ?: return
        runCatching { readFont(id, target, visited) }.onFailure {
            HollowEngine.LOGGER.warn("Vanilla font reference {} is unreadable: {}", id, it.message)
        }
    }

    private fun readSpaceProvider(provider: JsonObject, target: FaceBuilder) {
        val advances = provider["advances"]?.jsonObject ?: return
        for ((key, value) in advances) {
            val codepoint = key.singleCodepointOrNull() ?: continue
            val advance = value.jsonPrimitive.floatOrNull ?: continue
            target.glyphs.putIfAbsent(codepoint, UiVanillaGlyph.blank(advance / EmPixels))
        }
    }

    private fun readBitmapProvider(provider: JsonObject, target: FaceBuilder) {
        val file = provider["file"]?.jsonPrimitive?.contentOrNull?.let(ResourceLocation::tryParse) ?: return
        val rows = provider["chars"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return
        if (rows.isEmpty()) return
        val grid = rows.map { it.codePoints().toArray() }
        val columns = grid[0].size
        if (columns == 0) return
        val height = provider["height"]?.jsonPrimitive?.intOrNull ?: DefaultBitmapHeight
        val ascent = provider["ascent"]?.jsonPrimitive?.int ?: return
        val texture = ResourceLocation.fromNamespaceAndPath(file.namespace, "textures/${file.path}")

        val bytes = readAsset(texture) ?: run {
            HollowEngine.LOGGER.warn("Vanilla font sheet {} is missing", texture)
            return
        }
        val sheet = runCatching { NativeImage.read(NativeImage.Format.RGBA, bytes.inputStream()) }.getOrElse {
            HollowEngine.LOGGER.warn("Vanilla font sheet {} is unreadable: {}", texture, it.message)
            return
        }

        sheet.use { image ->
            val cellWidth = image.width / columns
            val cellHeight = image.height / grid.size
            if (cellWidth <= 0 || cellHeight <= 0) return
            if (hasColouredInk(image)) target.coloredSheets += texture
            target.sheetSizes[texture] = UiSheetSize(image.width.toFloat(), image.height.toFloat())
            val scale = height.toFloat() / cellHeight.toFloat()
            val top = -ascent / EmPixels
            val bottom = (height - ascent) / EmPixels
            val right = cellWidth * scale / EmPixels
            for (row in grid.indices) {
                for (column in grid[row].indices) {
                    val codepoint = grid[row][column]
                    if (codepoint == 0 || codepoint in target.glyphs) continue
                    val trimmed = trimmedWidth(image, cellWidth, cellHeight, column, row)
                    val advance = ((0.5 + trimmed * scale).toInt() + 1) / EmPixels
                    target.glyphs[codepoint] = UiVanillaGlyph(
                        advance = advance,
                        left = 0f,
                        top = top,
                        right = right,
                        bottom = bottom,
                        uMin = (column * cellWidth).toFloat() / image.width,
                        vTop = (row * cellHeight).toFloat() / image.height,
                        uMax = ((column + 1) * cellWidth).toFloat() / image.width,
                        vBottom = ((row + 1) * cellHeight).toFloat() / image.height,
                        texture = texture,
                    )
                }
            }
        }
    }

    private fun hasColouredInk(image: NativeImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getPixelRGBA(x, y)
                if ((pixel ushr 24) and 0xFF == 0) continue
                if (pixel and 0xFFFFFF != 0xFFFFFF) return true
            }
        }
        return false
    }

    private fun readAssetStack(location: ResourceLocation): List<ByteArray> {
        val manager = runCatching { Minecraft.getInstance()?.resourceManager }.getOrNull()
        if (manager != null) {
            return runCatching { manager.getResourceStack(location) }.getOrNull().orEmpty()
                .map { resource -> resource.open().use { it.readBytes() } }
        }
        val path = "assets/${location.namespace}/${location.path}"
        val classpath = UiVanillaFont::class.java.classLoader.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        return listOfNotNull(classpath?.use { it.readBytes() })
    }

    private fun readAsset(location: ResourceLocation): ByteArray? {
        val manager = runCatching { Minecraft.getInstance()?.resourceManager }.getOrNull()
        if (manager != null) {
            val resource = runCatching { manager.getResource(location).orElse(null) }.getOrNull()
            return resource?.open()?.use { it.readBytes() }
        }
        return readAssetStack(location).lastOrNull()
    }

    private class FaceBuilder {
        val glyphs = HashMap<Int, UiVanillaGlyph>()
        val coloredSheets = HashSet<ResourceLocation>()

        /** Pixel size of each sheet, so the renderer can sample texel centers rather than cell seams. */
        val sheetSizes = HashMap<ResourceLocation, UiSheetSize>()
    }

    private fun trimmedWidth(image: NativeImage, cellWidth: Int, cellHeight: Int, column: Int, row: Int): Int {
        for (x in cellWidth - 1 downTo 0) {
            val imageX = column * cellWidth + x
            for (y in 0 until cellHeight) {
                if (image.getLuminanceOrAlpha(imageX, row * cellHeight + y).toInt() != 0) return x + 1
            }
        }
        return 0
    }

    private fun String.singleCodepointOrNull(): Int? = codePoints().toArray().singleOrNull()

    internal val fallbackCodepoints = intArrayOf(FallbackGlyph.code, LegacyFallbackGlyph.code)

    internal val fallbackAdvance: Float get() = FallbackAdvancePixels / EmPixels
    internal val lineHeightEm: Float get() = LineHeightPixels / EmPixels
    internal val ascenderEm: Float get() = BaselinePixels / EmPixels
    internal val descenderEm: Float get() = (BaselinePixels - LineHeightPixels) / EmPixels

    internal val underlineYEm: Float get() = -1.5f / EmPixels
    internal val underlineThicknessEm: Float get() = 1f / EmPixels

    internal val strikethroughYEm: Float get() = 3f / EmPixels
}

internal data class UiVanillaGlyph(
    val advance: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val uMin: Float,
    val vTop: Float,
    val uMax: Float,
    val vBottom: Float,
    val texture: ResourceLocation?,
) {
    companion object {
        fun blank(advance: Float) = UiVanillaGlyph(advance, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, null)
    }
}

/** How many pixels a font sheet is across, which is what turns a UV back into texels. */
internal data class UiSheetSize(val width: Float, val height: Float)

class UiVanillaFontFace internal constructor(
    val location: ResourceLocation,
    internal val glyphs: Map<Int, UiVanillaGlyph>,
    internal val coloredSheets: Set<ResourceLocation> = emptySet(),
    internal val sheetSizes: Map<ResourceLocation, UiSheetSize> = emptyMap(),
) {
    internal fun glyphOrFallback(codepoint: Int): UiVanillaGlyph? {
        glyphs[codepoint]?.let { return it }
        for (fallback in UiVanillaFont.fallbackCodepoints) glyphs[fallback]?.let { return it }
        return null
    }

    fun advance(codepoint: Int): Float =
        (glyphs[codepoint] ?: glyphOrFallback(codepoint))?.advance ?: UiVanillaFont.fallbackAdvance

    fun width(text: String, fontSize: Float): Float {
        var total = 0f
        text.forEachCodepoint { total += advance(it) }
        return total * fontSize
    }

    fun lineHeight(fontSize: Float): Float = UiVanillaFont.lineHeightEm * fontSize
}
