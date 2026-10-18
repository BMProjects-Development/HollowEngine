package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.UiImageShadow
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.utils.popPose
import ru.hollowhorizon.hollowengine.client.utils.pushPose
import ru.hollowhorizon.hollowengine.client.utils.setIdentity
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

internal data class UiImageShadowKey(
    val image: UiImageShadow,
    val sourceTexture: Int,
    val revision: Long,
    val sourceBounds: UiRect,
    val width: Float,
    val height: Float,
    val scale: Float,
    val blur: Float,
    val spread: Float,
)

internal data class UiImageShadowMask(val texture: ResourceLocation, val bounds: UiRect)

internal fun imageShadowBounds(bounds: UiRect, blur: Float, spread: Float): UiRect {
    val padding = ceil(abs(spread) + blur.coerceAtLeast(0f) * 1.5f + 2f)
    return UiRect(bounds.x - padding, bounds.y - padding, bounds.width + padding * 2f, bounds.height + padding * 2f)
}

/** Cached white RGB + source alpha masks; color, opacity, placement and offsets do not invalidate them. */
internal class UiImageShadowCache : AutoCloseable {
    private class Entry(
        val mask: UiImageShadowMask,
        val texture: MaskTexture,
        val workspace: Workspace?,
        var frame: Long,
    ) {
        val pixels: Long get() = texture.framebuffer.width.toLong() * texture.framebuffer.height * if (workspace == null) 1 else 3
        fun close() {
            Minecraft.getInstance().textureManager.release(mask.texture)
            workspace?.close()
        }
    }

    private val entries = LinkedHashMap<UiImageShadowKey, Entry>(16, 0.75f, true)
    private var frame = 0L
    private var generation = -1
    private var pixels = 0L
    private val deviceLimit by lazy { GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE).toFloat() }
    var draws = 0
        private set

    fun beginFrame() {
        frame++
        draws = 0
        if (generation != UiPathTileResources.generation) {
            close()
            generation = UiPathTileResources.generation
        }
    }

    /** Called after the final batch: no queued draw may retain a texture when it is evicted. */
    fun endFrame() {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (pixels <= MaxCachedPixels && frame - entry.frame <= MaxUnusedFrames) continue
            pixels -= entry.pixels
            entry.close()
            iterator.remove()
        }
    }

    fun get(
        key: UiImageShadowKey,
        cacheAcrossFrames: Boolean,
        drawImage: (UiMatrix4) -> Unit,
    ): UiImageShadowMask {
        val previous = entries[key]
        if (previous != null && (cacheAcrossFrames || previous.frame == frame)) {
            previous.frame = frame
            return previous.mask
        }
        val bounds = imageShadowBounds(key.sourceBounds, key.blur, key.spread)
        val needsFiltering = key.blur > 0f || key.spread != 0f || key.image.clipRect != null || key.image.filter != UiFilterChain.Empty
        val pixelBudget = MaxCachedPixels / if (!cacheAcrossFrames && needsFiltering) 3 else 1
        val scale = min(key.scale, min(
            min(deviceLimit / bounds.width, deviceLimit / bounds.height),
            sqrt(pixelBudget.toDouble() / (bounds.width.toDouble() * bounds.height)).toFloat(),
        ))
        val width = ceil(bounds.width * scale).toInt().coerceIn(1, deviceLimit.toInt())
        val heightLimit = min(deviceLimit.toLong(), pixelBudget / width).toInt().coerceAtLeast(1)
        val height = ceil(bounds.height * scale).toInt().coerceIn(1, heightLimit)
        val texture = previous?.texture ?: MaskTexture(UiFramebuffer(width, height, withDepth = false))
        val mask = previous?.mask ?: UiImageShadowMask(
            ResourceLocation.fromNamespaceAndPath(HollowEngine.MODID, "generated/ui/shadow/${Ids.incrementAndGet()}"), bounds,
        )
        val workspace = if (needsFiltering) previous?.workspace ?: Workspace(width, height) else null
        try {
            generate(texture.framebuffer, workspace, key, bounds, drawImage)
        } catch (failure: Throwable) {
            if (previous == null) {
                texture.close()
                if (!cacheAcrossFrames) workspace?.close()
            }
            throw failure
        } finally {
            if (cacheAcrossFrames) workspace?.close()
        }
        if (previous == null) {
            Minecraft.getInstance().textureManager.register(mask.texture, texture)
            val entry = Entry(mask, texture, workspace.takeUnless { cacheAcrossFrames }, frame)
            entries[key] = entry
            pixels += entry.pixels
        } else previous.frame = frame
        return mask
    }

    private fun generate(
        target: UiFramebuffer,
        workspace: Workspace?,
        key: UiImageShadowKey,
        bounds: UiRect,
        drawImage: (UiMatrix4) -> Unit,
    ) {
        withImageShadowTarget(bounds) {
            val first = workspace?.first ?: target
            first.bind()
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            configureUiBlend()
            drawImage(UiMatrix4.translation(-bounds.x, -bounds.y, 0f))
            draws++
            if (workspace == null) return@withImageShadowTarget
            var source = first
            var scratch = workspace.second
            val image = key.image
            if (image.clipRect != null || image.filter != UiFilterChain.Empty) {
                scratch.bind()
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                val clipBounds = image.clipRect ?: UiRect(0f, 0f, key.width, key.height)
                val transform = UiMatrix4.translation(clipBounds.x - bounds.x, clipBounds.y - bounds.y, 0f)
                val u0 = (clipBounds.x - bounds.x) / bounds.width
                val v0 = 1f - (clipBounds.y - bounds.y) / bounds.height
                val u1 = u0 + clipBounds.width / bounds.width
                val v1 = v0 - clipBounds.height / bounds.height
                if (image.clipShape != null) {
                    UiTextureEffects.drawTexturedShapeRegion(
                        source.texture, clipBounds.width, clipBounds.height, image.clipShape,
                        transform, 1f, u0, v0, u1, v1, false, image.filter, source.width, source.height,
                        alphaMask = true,
                    )
                } else {
                    UiTextureEffects.drawTexturedRegion(
                        source.texture, clipBounds.width, clipBounds.height, transform, 1f,
                        u0, v0, u1, v1, false, image.filter, source.width, source.height,
                        maskRadius = image.radius, maskScale = source.width / bounds.width, alphaMask = true,
                    )
                }
                draws++
                source = workspace.second
                scratch = first
            }
            if (key.spread != 0f) {
                UiImageShadowFilter.draw(source, scratch, key.spread * target.width / bounds.width, true, true)
                UiImageShadowFilter.draw(scratch, source, key.spread * target.height / bounds.height, false, true)
                draws += 2
            }
            if (key.blur > 0f) {
                UiImageShadowFilter.draw(source, scratch, key.blur * target.width / bounds.width, true, false)
                UiImageShadowFilter.draw(scratch, target, key.blur * target.height / bounds.height, false, false)
                draws += 2
            } else {
                UiImageShadowFilter.draw(source, target, 0f, true, false)
                draws++
            }
        }
    }

    override fun close() {
        entries.values.forEach(Entry::close)
        entries.clear()
        pixels = 0
    }

    private class MaskTexture(val framebuffer: UiFramebuffer) : AbstractTexture() {
        init { id = framebuffer.texture }
        override fun load(resourceManager: ResourceManager) = Unit
        override fun close() {
            if (id == NOT_ASSIGNED) return
            framebuffer.close()
            id = NOT_ASSIGNED
        }
    }

    private class Workspace(width: Int, height: Int) : AutoCloseable {
        val first = UiFramebuffer(width, height, withDepth = false)
        val second = UiFramebuffer(width, height, withDepth = false)
        override fun close() {
            first.close()
            second.close()
        }
    }

    private companion object {
        const val MaxCachedPixels = 4L * 1024 * 1024
        const val MaxUnusedFrames = 300
        val Ids = AtomicLong()
    }
}

/** Restores the actual projection, including when nested inside a framebuffer-layer prepass. */
private fun withImageShadowTarget(bounds: UiRect, draw: () -> Unit) {
    val projection = Matrix4f(RenderSystem.getProjectionMatrix())
    val sorting = RenderSystem.getVertexSorting()
    val viewport = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_VIEWPORT, it) }
    val clip = IntArray(4).also { GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, it) }
    val scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
    val clearColor = FloatArray(4).also { GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, it) }
    val read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
    val write = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
    val alpha = uiBlendWritesAlpha
    val stack = RenderSystem.getModelViewStack()
    stack.pushPose()
    try {
        disableScissor()
        uiWriteAlpha(true)
        RenderSystem.disableDepthTest()
        stack.setIdentity()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.setProjectionMatrix(
            Matrix4f().setOrtho(0f, bounds.width, bounds.height, 0f, -1000f, 1000f), VertexSorting.ORTHOGRAPHIC_Z,
        )
        withCullStatePreserved {
            RenderSystem.disableCull()
            draw()
        }
    } finally {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, write)
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GL11.glScissor(clip[0], clip[1], clip[2], clip[3])
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST) else disableScissor()
        uiWriteAlpha(alpha)
        configureUiBlend()
        RenderSystem.setProjectionMatrix(projection, sorting)
        stack.popPose()
        RenderSystem.applyModelViewMatrix()
    }
}
