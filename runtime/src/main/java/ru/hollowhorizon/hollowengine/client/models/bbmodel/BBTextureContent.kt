package ru.hollowhorizon.hollowengine.client.models.bbmodel

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import ru.hollowhorizon.hollowengine.client.models.internal.ModelTexture
import ru.hollowhorizon.hollowengine.client.utils.stream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/** Flipbook timing is independent of pose updates, so culled or settled models never freeze textures. */
class BBTextureTimeline(
    val frames: Int,
    val fps: Float,
    order: String = "loop",
    customOrder: String = "",
) {
    private val sequence: List<Int> = when (order) {
        "loop" -> (0 until frames).toList()
        "backwards" -> (0 until frames).reversed().toList()
        "back_and_forth" -> (0 until frames).toList() + (frames - 2 downTo 1).toList()
        "custom" -> customOrder.trim().split(Regex("\\s+")).filter(String::isNotBlank).map {
            requireNotNull(it.toIntOrNull()) { "Invalid Blockbench flipbook frame '$it'" }
        }
        else -> error("Unknown Blockbench flipbook order '$order'")
    }.also { sequence ->
        require(frames > 0 && fps.isFinite() && fps > 0f) { "Invalid Blockbench flipbook timing" }
        require(sequence.isNotEmpty() && sequence.all { it in 0 until frames }) { "Invalid Blockbench flipbook frame order" }
    }

    data class Sample(val frame: Int, val next: Int, val fraction: Float)

    fun sample(seconds: Double): Sample {
        val phase = seconds * fps
        val step = floor(phase).toLong()
        val index = step.mod(sequence.size)
        return Sample(sequence[index], sequence[(index + 1) % sequence.size], (phase - floor(phase)).toFloat())
    }
}

internal class BBTextureContent(
    private val texture: BBTexture,
    file: BlockBenchModel,
    location: ResourceLocation,
    private val clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
) : ModelTexture {
    private val source = open(texture, location).use { stream ->
        requireNotNull(ImageIO.read(stream)) { "Cannot decode Blockbench texture '${texture.name}' in $location" }
    }
    private val width = source.width
    private val uvWidth = texture.uvWidth.takeIf { it > 0 } ?: file.resolution.width
    private val uvHeight = texture.uvHeight.takeIf { it > 0 } ?: file.resolution.height
    internal val frameCount = ceil(source.height.toDouble() * uvWidth / (width.toDouble() * uvHeight) - 0.05).toInt().coerceAtLeast(1)
    private val frameHeight = source.height / frameCount
    private val pixels = source.getRGB(0, 0, width, source.height, null, 0, width)
    val hasTranslucency: Boolean = pixels.any { (it ushr 24) in 1..254 }
    private val timeline = BBTextureTimeline(
        frameCount,
        if (file.meta.format == "java_block") 20f / texture.frameTime.coerceAtLeast(1) else texture.fps,
        texture.frameOrderType,
        texture.frameOrder,
    )
    private var uploaded: DynamicTexture? = null
    private var lastFrame = -1
    private var lastNextFrame = -1
    private var lastFraction = -1f
    private var closed = false

    init {
        require(uvWidth > 0 && uvHeight > 0 && source.height % frameCount == 0) {
            "Invalid Blockbench flipbook dimensions for '${texture.name}'"
        }
    }

    override fun textureId(): Int {
        RenderSystem.assertOnRenderThreadOrInit()
        check(!closed) { "Blockbench texture '${texture.name}' was already released" }
        val target = uploaded ?: DynamicTexture(NativeImage(width, frameHeight, false)).also {
            uploaded = it
            it.bind()
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        }
        val sample = timeline.sample(clock())
        val next = if (texture.frameInterpolate) sample.next else sample.frame
        val fraction = if (next != sample.frame) sample.fraction else 0f
        if (lastFrame != sample.frame || lastNextFrame != next || lastFraction != fraction) {
            val image = checkNotNull(target.pixels)
            for (y in 0 until frameHeight) for (x in 0 until width) {
                val a = pixels[(sample.frame * frameHeight + y) * width + x]
                val b = pixels[(sample.next * frameHeight + y) * width + x]
                val argb = if (fraction == 0f) a else interpolate(a, b, fraction)
                val abgr = (argb and -0x1000000) or ((argb and 255) shl 16) or
                    (argb and 0xff00) or ((argb ushr 16) and 255)
                image.setPixelRGBA(x, y, abgr)
            }
            target.upload()
            lastFrame = sample.frame
            lastNextFrame = next
            lastFraction = fraction
        }
        return target.id
    }

    override fun close() {
        closed = true
        uploaded?.close()
        uploaded = null
        source.flush()
    }

    private fun interpolate(a: Int, b: Int, t: Float): Int {
        var color = 0
        for (shift in 0..24 step 8) {
            val first = (a ushr shift) and 255
            val second = (b ushr shift) and 255
            color = color or ((first + (second - first) * t).toInt() shl shift)
        }
        return color
    }

    companion object {
        private fun open(texture: BBTexture, location: ResourceLocation) = when {
            texture.source.startsWith("data:") -> {
                require(texture.source.substringBefore(',').endsWith(";base64")) { "Expected a base64 texture data URI" }
                Base64.getDecoder().decode(texture.source.substringAfter(',')).inputStream()
            }
            texture.namespace.isNotBlank() -> ResourceLocation.fromNamespaceAndPath(
                texture.namespace, "textures/${texture.folder.trim('/')}/${texture.name}".replace("//", "/"),
            ).stream
            else -> {
                val relative = texture.relativePath.ifBlank { texture.name }.replace('\\', '/')
                val path = location.path.substringBeforeLast('/', "") + "/" + relative
                val components = mutableListOf<String>()
                path.split('/').forEach { part ->
                    when (part) {
                        "", "." -> Unit
                        ".." -> {
                            require(components.isNotEmpty()) { "Texture path escapes the resource namespace: $relative" }
                            components.removeAt(components.lastIndex)
                        }
                        else -> components += part
                    }
                }
                ResourceLocation.fromNamespaceAndPath(location.namespace, components.joinToString("/")).stream
            }
        }
    }
}
