package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11
import ru.hollowhorizon.hollowengine.client.ui.BeginLayerCommand
import ru.hollowhorizon.hollowengine.client.ui.PopupNode
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.UiNode
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutResult
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.style.UiBackfaceVisibility
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.clip
import ru.hollowhorizon.hollowengine.client.ui.style.scrollable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class UiRenderTarget(
    val framebufferId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val logicalWidth: Float,
    val logicalHeight: Float,
    val scale: Float,
)

internal data class RenderTargetState(
    val framebufferId: Int,
    val framebuffer: UiFramebuffer?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val logicalWidth: Float,
    val logicalHeight: Float,
    val scale: Float,
)

internal data class LayerState(
    val node: UiNode,
    val rect: UiRect,
    val radius: Float,
    val clipShape: Shape?,
    val transform: UiMatrix4,
    val framebuffer: UiLayerFramebuffer,
    val parentClips: List<UiRect>,
    val scale: Float,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val padding: Float,
    val opacity: Float,
    val resolutionScale: Float = 1f,
)

internal data class ScissorBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal const val GaussianMaxRadiusAtFullResolution = 8f

internal fun gaussianSampleScale(radius: Float): Float =
    max(1f, radius.coerceAtLeast(0f) / GaussianMaxRadiusAtFullResolution)

internal fun gaussianDownsampleFactor(radius: Float): Int =
    ceil(gaussianSampleScale(radius)).toInt()

internal fun gaussianSamplePadding(radius: Float): Int =
    ceil(max(radius * 0.5f, 1f) * 3f).toInt()

internal fun backdropSampleBounds(
    rect: UiRect,
    targetWidth: Int,
    targetHeight: Int,
    scaleX: Float,
    scaleY: Float,
    padding: Int,
): ScissorBounds? {
    if (targetWidth <= 0 || targetHeight <= 0 || scaleX <= 0f || scaleY <= 0f ||
        rect.width <= 0f || rect.height <= 0f
    ) {
        return null
    }
    val safePadding = padding.coerceAtLeast(0)
    val left = (floor(rect.x * scaleX).toInt() - safePadding).coerceIn(0, targetWidth)
    val top = (floor(rect.y * scaleY).toInt() - safePadding).coerceIn(0, targetHeight)
    val right = (ceil((rect.x + rect.width) * scaleX).toInt() + safePadding).coerceIn(0, targetWidth)
    val bottom = (ceil((rect.y + rect.height) * scaleY).toInt() + safePadding).coerceIn(0, targetHeight)
    if (right <= left || bottom <= top) return null
    return ScissorBounds(left, top, right - left, bottom - top)
}

internal const val LayerSupersampling = 1f
internal const val MaxTransformResolutionScale = 4f
internal const val MinLayerResolutionScale = 0.25f
internal const val RotatedResolutionScale = 2f

internal fun transformResolutionScale(transform: UiMatrix4, scratch: FloatArray = FloatArray(2)): Float =
    resolutionScale(transform, scratch, 1f)

/** Like [transformResolutionScale], but a layer shrunk on screen also takes fewer pixels in the atlas. */
internal fun layerResolutionScale(transform: UiMatrix4, scratch: FloatArray = FloatArray(2)): Float =
    resolutionScale(transform, scratch, MinLayerResolutionScale)

private fun resolutionScale(transform: UiMatrix4, scratch: FloatArray, minimum: Float): Float {
    transform.axisScales(scratch)
    val magnification = max(scratch[0], scratch[1])
    val supersampling = if (transform.isAxisAligned) 1f else RotatedResolutionScale
    if (!magnification.isFinite()) return 1f
    return (magnification * supersampling).coerceIn(minimum, MaxTransformResolutionScale)
}

internal fun layerPadding(command: BeginLayerCommand): Float = layerPadding(command.filter)

internal fun layerPadding(filter: UiFilterChain, overflow: Float = 0f): Float {
    val blur = filter.blurRadius()
    val guard = 12f
    return ceil(guard + blur * 3f + overflow.coerceIn(0f, MaxLayerOverflow)).coerceAtLeast(guard)
}

private const val MaxLayerOverflow = 512f

/**
 * How far each layer node's subtree reaches outside that node's own rect.
 */
/** How far the content of a layer [node] reaches past its rect, which the layer has to leave room for. */
internal fun UiLayoutResult.layerOverflow(node: UiNode): Float {
    val rect = nodes[node]?.rect ?: return 0f
    val visual = visualBounds(node) ?: return 0f
    return max(
        max(rect.x - visual.x, rect.y - visual.y),
        max(
            visual.x + visual.width - (rect.x + rect.width),
            visual.y + visual.height - (rect.y + rect.height),
        ),
    ).coerceAtLeast(0f)
}

private fun UiLayoutResult.visualBounds(node: UiNode): UiRect? {
    var visual = nodes[node]?.rect ?: return null
    val style = node.resolvedSnapshot
    if (style.clip || style.scrollable) return visual
    for (child in childrenOf(node)) {
        if (child is PopupNode) continue // Popups are drawn by the overlay host, not here.
        visualBounds(child)?.let { visual = visual.union(it) }
    }
    return visual
}

private fun UiRect.union(other: UiRect): UiRect {
    val left = minOf(x, other.x)
    val top = minOf(y, other.y)
    val right = max(x + width, other.x + other.width)
    val bottom = max(y + height, other.y + other.height)
    return UiRect(left, top, right - left, bottom - top)
}

internal var uiBlendWritesAlpha = false
    private set

/** Points the renderer at a target and applies the alpha rule that target wants. */
internal fun uiWriteAlpha(enabled: Boolean) {
    uiBlendWritesAlpha = enabled
    RenderSystem.colorMask(true, true, true, enabled)
}

internal fun configureUiBlend() {
    RenderSystem.enableBlend()
    RenderSystem.blendFuncSeparate(
        GlStateManager.SourceFactor.SRC_ALPHA,
        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        GlStateManager.SourceFactor.ONE,
        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
    )
    RenderSystem.colorMask(true, true, true, uiBlendWritesAlpha)
}

internal fun disableScissor() {
    GL11.glDisable(GL11.GL_SCISSOR_TEST)
}

internal fun <T> withCullStatePreserved(block: () -> T): T {
    val cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE)
    return try {
        block()
    } finally {
        if (cullEnabled) {
            RenderSystem.enableCull()
        } else {
            RenderSystem.disableCull()
        }
    }
}

internal fun UiRect.intersect(other: UiRect): UiRect {
    val left = maxOf(x, other.x)
    val top = maxOf(y, other.y)
    val right = minOf(x + width, other.x + other.width)
    val bottom = minOf(y + height, other.y + other.height)
    return UiRect(left, top, maxOf(0f, right - left), maxOf(0f, bottom - top))
}
