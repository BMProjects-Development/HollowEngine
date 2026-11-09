package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import kotlin.math.max
import kotlin.math.min

private const val BackdropWorkspaceGrowthLimit = 256
private const val BackdropWorkspaceAlignment = 64
private const val BackdropWorkspaceShrinkFrames = 300
private const val AtlasGrowthStep = 128
private const val AtlasShrinkFrames = 300
private const val AtlasShrinkRatio = 4L
private const val AtlasKeptArea = 1L shl 20

internal fun alignFramebufferAxis(required: Int, maxTextureSize: Int): Int {
    require(required > 0 && maxTextureSize > 0)
    val clamped = required.coerceAtMost(maxTextureSize)
    val aligned = ((clamped + BackdropWorkspaceAlignment - 1) / BackdropWorkspaceAlignment) *
            BackdropWorkspaceAlignment
    return min(aligned, maxTextureSize)
}

internal fun growFramebufferAxis(required: Int, current: Int, maxTextureSize: Int): Int {
    require(required > 0 && required <= maxTextureSize)
    if (current >= required) return current
    val grown = if (current > 0) {
        val growth = min(max(current / 2, 1), BackdropWorkspaceGrowthLimit)
        max(required, current + growth)
    } else {
        required
    }
    return alignFramebufferAxis(grown, maxTextureSize)
}

/** The size to give an atlas for [layerRequests]: at least [current], with little more room than they take. */
internal fun atlasSizeFor(layerRequests: List<UiLayerRequest>, current: UiAtlasSize?, maxTextureSize: Int): UiAtlasSize {
    var requiredWidth = current?.width ?: 1
    var requiredHeight = current?.height ?: 1
    layerRequests.forEach {
        requiredWidth = max(requiredWidth, it.width + UiAtlasGuardPixels * 2)
        requiredHeight = max(requiredHeight, it.height + UiAtlasGuardPixels * 2)
    }
    var width = growFramebufferAxis(requiredWidth.coerceAtMost(maxTextureSize), current?.width ?: 0, maxTextureSize)
    var height = growFramebufferAxis(requiredHeight.coerceAtMost(maxTextureSize), current?.height ?: 0, maxTextureSize)
    while (!layersFit(layerRequests, width, height)) {
        val canGrowWidth = width < maxTextureSize
        val canGrowHeight = height < maxTextureSize
        if (!canGrowWidth && !canGrowHeight) break
        if (width <= height && canGrowWidth || !canGrowHeight) {
            width = min(width + AtlasGrowthStep, maxTextureSize)
        } else {
            height = min(height + AtlasGrowthStep, maxTextureSize)
        }
    }
    return UiAtlasSize(width, height)
}

/** Whether an atlas of [width]x[height] holds several times more than [layerRequests] need. */
internal fun isAtlasOversized(layerRequests: List<UiLayerRequest>, width: Int, height: Int): Boolean {
    val area = width.toLong() * height
    val used = layerRequests.sumOf {
        (it.width + UiAtlasGuardPixels * 2L) * (it.height + UiAtlasGuardPixels * 2L)
    }
    return area > AtlasKeptArea && used * AtlasShrinkRatio < area
}

private fun layersFit(layerRequests: List<UiLayerRequest>, width: Int, height: Int): Boolean {
    val testAllocator = UiAtlasAllocator(width, height)
    return layerRequests.all {
        testAllocator.allocate(
            it.width + UiAtlasGuardPixels * 2,
            it.height + UiAtlasGuardPixels * 2,
        ) != null
    }
}

internal class UiFramebufferPool {
    private val scratchFramebuffers = mutableListOf<UiFramebuffer>()
    private val retiredAtlases = mutableListOf<RetiredUiFramebuffer>()
    private var atlas: UiFramebuffer? = null
    private var allocator = UiAtlasAllocator(1, 1)
    private var atlasOversizedFrames = 0
    private var backdropWorkspace: UiBackdropBlurWorkspace? = null
    private var backdropRequestedWidth = 0
    private var backdropRequestedHeight = 0
    private var backdropUnderusedFrames = 0

    fun beginFrame(layerRequests: List<UiLayerRequest>) {
        retireOldAtlases()
        maintainBackdropWorkspace()
        val shrink = shouldShrinkAtlas(layerRequests)
        if (layerRequests.isEmpty()) {
            allocator = UiAtlasAllocator(1, 1)
            if (shrink) replaceAtlas(null)
            return
        }
        val current = atlas
        val size = if (current != null && !shrink && layersFit(layerRequests, current.width, current.height)) {
            UiAtlasSize(current.width, current.height)
        } else {
            val kept = current?.takeUnless { shrink }?.let { UiAtlasSize(it.width, it.height) }
            atlasSizeFor(layerRequests, kept, GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE).coerceAtLeast(1))
        }
        if (current == null || current.width != size.width || current.height != size.height) {
            replaceAtlas(UiFramebuffer(size.width, size.height))
        }
        allocator = UiAtlasAllocator(size.width, size.height)
    }

    /** True once the atlas has stayed oversized for a while, so that one big layer does not hold it for good. */
    private fun shouldShrinkAtlas(layerRequests: List<UiLayerRequest>): Boolean {
        val current = atlas
        val oversized = current != null && isAtlasOversized(layerRequests, current.width, current.height)
        atlasOversizedFrames = if (oversized) atlasOversizedFrames + 1 else 0
        if (atlasOversizedFrames < AtlasShrinkFrames) return false
        atlasOversizedFrames = 0
        return true
    }

    private fun replaceAtlas(next: UiFramebuffer?) {
        atlas?.let { retiredAtlases += RetiredUiFramebuffer(it) }
        atlas = next
    }

    fun acquireLayer(width: Int, height: Int): UiLayerFramebuffer {
        if (atlas == null) {
            val atlasWidth = width + UiAtlasGuardPixels * 2
            val atlasHeight = height + UiAtlasGuardPixels * 2
            atlas = UiFramebuffer(atlasWidth, atlasHeight)
            allocator = UiAtlasAllocator(atlasWidth, atlasHeight)
        }
        val framebuffer = atlas ?: error("UI framebuffer atlas was not initialized")
        val allocation = allocator.allocate(
            width + UiAtlasGuardPixels * 2,
            height + UiAtlasGuardPixels * 2,
        ) ?: error("UI framebuffer atlas is too small for ${width}x$height layer")
        return UiLayerFramebuffer(
            framebuffer,
            UiAtlasRegion(
                x = allocation.x + UiAtlasGuardPixels,
                y = allocation.y + UiAtlasGuardPixels,
                width = width,
                height = height,
                clearX = allocation.x,
                clearY = allocation.y,
                clearWidth = allocation.width,
                clearHeight = allocation.height,
            )
        )
    }

    fun acquire(width: Int, height: Int, exclude: UiFramebuffer? = null): UiFramebuffer {
        val framebuffer = scratchFramebuffers.firstOrNull {
            it !== exclude && !it.inUse && it.width == width && it.height == height
        } ?: UiFramebuffer(width, height, withDepth = false).also {
            scratchFramebuffers += it
        }
        framebuffer.inUse = true
        return framebuffer
    }

    fun release(framebuffer: UiFramebuffer) {
        framebuffer.inUse = false
    }

    fun backdropBlurWorkspace(width: Int, height: Int): UiBackdropBlurWorkspace {
        require(width > 0 && height > 0) { "Backdrop workspace size must be positive" }
        backdropRequestedWidth = max(backdropRequestedWidth, width)
        backdropRequestedHeight = max(backdropRequestedHeight, height)
        val current = backdropWorkspace
        if (current != null && current.width >= width && current.height >= height) return current
        val maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE).coerceAtLeast(1)
        require(width <= maxTextureSize && height <= maxTextureSize) {
            "Backdrop workspace ${width}x$height exceeds GL_MAX_TEXTURE_SIZE $maxTextureSize"
        }
        return replaceBackdropWorkspace(
            growFramebufferAxis(width, current?.width ?: 0, maxTextureSize),
            growFramebufferAxis(height, current?.height ?: 0, maxTextureSize),
        )
    }

    fun close() {
        atlas?.close()
        atlas = null
        atlasOversizedFrames = 0
        backdropWorkspace?.close()
        backdropWorkspace = null
        backdropRequestedWidth = 0
        backdropRequestedHeight = 0
        backdropUnderusedFrames = 0
        retiredAtlases.forEach { it.framebuffer.close() }
        retiredAtlases.clear()
        scratchFramebuffers.forEach(UiFramebuffer::close)
        scratchFramebuffers.clear()
    }

    private fun retireOldAtlases() {
        val iterator = retiredAtlases.iterator()
        while (iterator.hasNext()) {
            val retired = iterator.next()
            retired.framesLeft--
            if (retired.framesLeft <= 0) {
                retired.framebuffer.close()
                iterator.remove()
            }
        }
    }

    private fun maintainBackdropWorkspace() {
        val current = backdropWorkspace ?: run {
            backdropRequestedWidth = 0
            backdropRequestedHeight = 0
            return
        }
        val unused = backdropRequestedWidth == 0 || backdropRequestedHeight == 0
        val underused = unused ||
                backdropRequestedWidth * 2 <= current.width ||
                backdropRequestedHeight * 2 <= current.height
        backdropUnderusedFrames = if (underused) backdropUnderusedFrames + 1 else 0
        if (backdropUnderusedFrames >= BackdropWorkspaceShrinkFrames) {
            if (unused) {
                current.close()
                backdropWorkspace = null
            } else {
                val maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE).coerceAtLeast(1)
                replaceBackdropWorkspace(
                    alignFramebufferAxis(backdropRequestedWidth, maxTextureSize),
                    alignFramebufferAxis(backdropRequestedHeight, maxTextureSize),
                )
            }
            backdropUnderusedFrames = 0
        }
        backdropRequestedWidth = 0
        backdropRequestedHeight = 0
    }

    private fun replaceBackdropWorkspace(width: Int, height: Int): UiBackdropBlurWorkspace {
        backdropWorkspace?.close()
        return UiBackdropBlurWorkspace(
            capture = UiFramebuffer(width, height, withDepth = false),
            intermediate = UiFramebuffer(width, height, withDepth = false),
        ).also { backdropWorkspace = it }
    }

}

private class RetiredUiFramebuffer(
    val framebuffer: UiFramebuffer,
    var framesLeft: Int = RetiredAtlasFrames,
)

internal data class UiLayerRequest(
    val width: Int,
    val height: Int,
)

internal data class UiAtlasRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val clearX: Int,
    val clearY: Int,
    val clearWidth: Int,
    val clearHeight: Int,
)

internal class UiBackdropBlurWorkspace(
    val capture: UiFramebuffer,
    val intermediate: UiFramebuffer,
) {
    val width: Int get() = capture.width
    val height: Int get() = capture.height

    fun close() {
        capture.close()
        intermediate.close()
    }
}

internal class UiLayerFramebuffer(
    val atlas: UiFramebuffer,
    val region: UiAtlasRegion,
) {
    val framebuffer: Int get() = atlas.framebuffer
    val texture: Int get() = atlas.texture
    val width: Int get() = region.width
    val height: Int get() = region.height

    fun bind() {
        atlas.bind()
        RenderSystem.viewport(region.x, region.y, region.width, region.height)
    }

    fun clear() {
        atlas.bind()
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(region.clearX, region.clearY, region.clearWidth, region.clearHeight)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        if (!depthMask) GL11.glDepthMask(true)
        val writesAlpha = uiBlendWritesAlpha
        if (!writesAlpha) uiWriteAlpha(true)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
        if (!writesAlpha) uiWriteAlpha(false)
        if (!depthMask) GL11.glDepthMask(false)
        disableScissor()
    }

    fun u0(): Float = region.x / atlas.width.toFloat()

    fun v0(): Float = region.y / atlas.height.toFloat()

    fun u1(): Float = (region.x + region.width) / atlas.width.toFloat()

    fun v1(): Float = (region.y + region.height) / atlas.height.toFloat()
}

internal class UiFramebuffer(
    val width: Int,
    val height: Int,
    private val withDepth: Boolean = true,
) {
    val framebuffer: Int = GL30.glGenFramebuffers()
    val texture: Int = GL11.glGenTextures()
    private val depth: Int = if (withDepth) GL30.glGenRenderbuffers() else 0
    var inUse: Boolean = false

    init {
        val previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val previousDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING)
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                width,
                height,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                0L
            )
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
            if (withDepth) {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth)
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, width, height)
                GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth)
            }
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
                "UI framebuffer is incomplete for ${width}x$height"
            }
        } finally {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer)
        }
    }

    fun bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        RenderSystem.viewport(0, 0, width, height)
    }

    fun close() {
        GL30.glDeleteFramebuffers(framebuffer)
        GL11.glDeleteTextures(texture)
        if (withDepth) GL30.glDeleteRenderbuffers(depth)
    }
}

private class UiAtlasAllocator(width: Int, height: Int) {
    private val freeRects = mutableListOf(UiPackedRect(0, 0, width, height))

    fun allocate(width: Int, height: Int): UiPackedRect? {
        var index = -1
        var bestWaste = Int.MAX_VALUE
        for (candidateIndex in freeRects.indices) {
            val candidate = freeRects[candidateIndex]
            if (!candidate.fits(width, height)) continue
            val waste = candidate.waste(width, height)
            if (waste < bestWaste) {
                index = candidateIndex
                bestWaste = waste
            }
        }
        if (index < 0) return null
        val free = freeRects.removeAt(index)
        val allocated = UiPackedRect(free.x, free.y, width, height)
        val rightWidth = free.width - width
        val bottomHeight = free.height - height
        if (rightWidth > 0) freeRects += UiPackedRect(free.x + width, free.y, rightWidth, height)
        if (bottomHeight > 0) freeRects += UiPackedRect(free.x, free.y + height, free.width, bottomHeight)
        pruneContainedRects()
        return allocated
    }

    private fun pruneContainedRects() {
        var index = 0
        while (index < freeRects.size) {
            val rect = freeRects[index]
            var contained = false
            var other = 0
            while (other < freeRects.size && !contained) {
                contained = other != index && freeRects[other].contains(rect)
                other++
            }
            if (contained) {
                freeRects.removeAt(index)
            } else {
                index++
            }
        }
    }
}

private data class UiPackedRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun fits(width: Int, height: Int): Boolean = this.width >= width && this.height >= height

    fun waste(width: Int, height: Int): Int = this.width * this.height - width * height

    fun contains(other: UiPackedRect): Boolean =
        other.x >= x &&
                other.y >= y &&
                other.x + other.width <= x + width &&
                other.y + other.height <= y + height
}

internal data class UiAtlasSize(
    val width: Int,
    val height: Int,
)

private const val UiAtlasGuardPixels = 2
private const val RetiredAtlasFrames = 3
