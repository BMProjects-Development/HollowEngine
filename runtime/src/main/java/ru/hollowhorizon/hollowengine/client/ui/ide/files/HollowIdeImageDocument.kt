package ru.hollowhorizon.hollowengine.client.ui.ide.files

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt

internal class HollowIdeImageDocument(
    path: String,
    bytes: ByteArray,
    override val readOnly: Boolean = false,
) : HollowIdeFileDocument, UndoOwner {
    private val format = ImageFileFormat.fromPath(path)
    private var image = NativeImage.read(bytes)
    private val texture = DynamicTexture(image)
    override val history = HollowIdePixelChange.history()
    private var textureDirty = false
    private var closed = false

    val width: Int get() = image.width
    val height: Int get() = image.height
    val textureLocation: ResourceLocation = Minecraft.getInstance().textureManager.register(
        "hollowide-image-${NextTextureId.incrementAndGet()}",
        texture,
    )
    val isModified: Boolean get() = !history.isAtSaved
    fun beginEdit() = HollowIdeImageEdit()

    fun colorAt(x: Int, y: Int): UiColor? {
        if (x !in 0 until width || y !in 0 until height) return null
        return image.getPixelRGBA(x, y).toUiColor()
    }

    fun paintLine(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        radius: Int,
        color: UiColor,
        erase: Boolean,
        edit: HollowIdeImageEdit,
    ): Boolean {
        var changed = false
        var x = fromX
        var y = fromY
        val deltaX = abs(toX - fromX)
        val deltaY = -abs(toY - fromY)
        val stepX = if (fromX < toX) 1 else -1
        val stepY = if (fromY < toY) 1 else -1
        var error = deltaX + deltaY
        val packedColor = if (erase) 0 else color.toNativeRgba()

        while (true) {
            changed = stamp(x, y, radius.coerceAtLeast(0), packedColor, edit) || changed
            if (x == toX && y == toY) break
            val doubled = error * 2
            if (doubled >= deltaY) {
                error += deltaY
                x += stepX
            }
            if (doubled <= deltaX) {
                error += deltaX
                y += stepY
            }
        }
        if (changed) textureDirty = true
        return changed
    }

    fun commit(edit: HollowIdeImageEdit): Boolean {
        if (edit.isEmpty) return false
        history.record(edit.build(::readPixel, ::restorePixel))
        return true
    }

    fun uploadIfDirty() {
        if (!textureDirty || closed) return
        texture.upload()
        textureDirty = false
    }

    override fun encode(): ByteArray = when (format) {
        ImageFileFormat.PNG -> image.asByteArray()
        ImageFileFormat.JPEG -> encodeJpeg()
    }

    override fun reload(bytes: ByteArray) {
        val reloaded = NativeImage.read(bytes)
        texture.setPixels(reloaded)
        image = reloaded
        texture.upload()
        textureDirty = false
        history.clear()
    }

    override fun markSaved() = history.markSaved()

    override fun close() {
        if (closed) return
        closed = true
        Minecraft.getInstance().textureManager.release(textureLocation)
    }

    companion object {
        private val NextTextureId = AtomicLong()
    }

    private fun stamp(
        centerX: Int,
        centerY: Int,
        radius: Int,
        packedColor: Int,
        edit: HollowIdeImageEdit,
    ): Boolean {
        var changed = false
        val radiusSquared = radius * radius
        for (offsetY in -radius..radius) {
            for (offsetX in -radius..radius) {
                if (offsetX * offsetX + offsetY * offsetY > radiusSquared) continue
                val x = centerX + offsetX
                val y = centerY + offsetY
                if (x !in 0 until width || y !in 0 until height) continue
                val current = image.getPixelRGBA(x, y)
                if (current == packedColor) continue
                edit.record(y * width + x, current)
                image.setPixelRGBA(x, y, packedColor)
                changed = true
            }
        }
        return changed
    }

    private fun readPixel(index: Int): Int = image.getPixelRGBA(index % width, index / width)

    /** Puts a pixel back from the history; the texture catches up on the next [uploadIfDirty]. */
    private fun restorePixel(index: Int, color: Int) {
        image.setPixelRGBA(index % width, index / width, color)
        textureDirty = true
    }

    private fun encodeJpeg(): ByteArray {
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = image.getPixelRGBA(x, y).toOpaqueArgb()
            }
        }
        buffered.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(buffered, "jpg", output)) { "No JPEG image writer is available" }
            output.toByteArray()
        }
    }
}

private enum class ImageFileFormat {
    PNG,
    JPEG;

    companion object {
        fun fromPath(path: String): ImageFileFormat = when {
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> JPEG
            else -> PNG
        }
    }
}

private fun Int.toUiColor(): UiColor = UiColor(
    red = (this and 0xFF) / 255f,
    green = (this ushr 8 and 0xFF) / 255f,
    blue = (this ushr 16 and 0xFF) / 255f,
    alpha = (this ushr 24 and 0xFF) / 255f,
)

private fun UiColor.toNativeRgba(): Int {
    val red = (red.coerceIn(0f, 1f) * 255f).roundToInt()
    val green = (green.coerceIn(0f, 1f) * 255f).roundToInt()
    val blue = (blue.coerceIn(0f, 1f) * 255f).roundToInt()
    val alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    return alpha shl 24 or (blue shl 16) or (green shl 8) or red
}

private fun Int.toOpaqueArgb(): Int {
    val red = this and 0xFF
    val green = this ushr 8 and 0xFF
    val blue = this ushr 16 and 0xFF
    val alpha = this ushr 24 and 0xFF
    val inverseAlpha = 255 - alpha
    val opaqueRed = (red * alpha + 255 * inverseAlpha) / 255
    val opaqueGreen = (green * alpha + 255 * inverseAlpha) / 255
    val opaqueBlue = (blue * alpha + 255 * inverseAlpha) / 255
    return 0xFF shl 24 or (opaqueRed shl 16) or (opaqueGreen shl 8) or opaqueBlue
}
