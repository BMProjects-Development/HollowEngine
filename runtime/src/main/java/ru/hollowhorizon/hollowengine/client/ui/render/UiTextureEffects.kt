package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.UiInsets
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathPoint
import ru.hollowhorizon.hollowengine.client.ui.style.*
import ru.hollowhorizon.hollowengine.common.registry.ModShaders
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class UiTexturedQuad(
    val width: Float,
    val height: Float,
    val transform: UiMatrix4,
    val opacity: Float,
    val flipY: Boolean = false,
    val fit: UiImageFit = UiImageFit.STRETCH,
    val slice: UiInsets = UiInsets.Zero,
    val tint: UiColor = UiColor.White,
    val region: ImagePlacement? = null,
    val alphaMask: Boolean = false,
    val uv: UiImageUv = UiImageUv.Full,
)

internal object UiTextureEffects {
    fun drawTexture(
        texture: Int,
        width: Float,
        height: Float,
        transform: UiMatrix4,
        opacity: Float,
        flipY: Boolean,
        filter: UiFilterChain = UiFilterChain.Empty,
        textureWidth: Float = width,
        textureHeight: Float = height,
        subdivisions: Int = 1,
        maskRadius: Float = 0f,
        maskPadding: Float = 0f,
        blurDirectionX: Float = 0f,
        blurDirectionY: Float = 0f,
        tint: UiColor = UiColor.White,
        opaqueSource: Boolean = false,
    ) {
        GlStateManager._bindTexture(texture)
        RenderSystem.setShaderTexture(0, texture)
        drawTexturedQuad(
            width,
            height,
            transform,
            opacity,
            flipY,
            filter = filter,
            textureWidth = textureWidth,
            textureHeight = textureHeight,
            subdivisions = subdivisions,
            maskRadius = maskRadius,
            maskPadding = maskPadding,
            blurDirectionX = blurDirectionX,
            blurDirectionY = blurDirectionY,
            tint = tint,
            opaqueSource = opaqueSource,
        )
    }

    fun drawTexturedQuad(
        width: Float,
        height: Float,
        transform: UiMatrix4,
        opacity: Float,
        flipY: Boolean,
        fit: UiImageFit = UiImageFit.STRETCH,
        texture: ResourceLocation? = null,
        filter: UiFilterChain = UiFilterChain.Empty,
        slice: UiInsets = UiInsets.Zero,
        textureWidth: Float = width,
        textureHeight: Float = height,
        subdivisions: Int = 1,
        maskRadius: Float = 0f,
        maskPadding: Float = 0f,
        blurDirectionX: Float = 0f,
        blurDirectionY: Float = 0f,
        tint: UiColor = UiColor.White,
        alphaMask: Boolean = false,
        uv: UiImageUv = UiImageUv.Full,
        opaqueSource: Boolean = false,
    ) {
        setTextureShader(
            filter,
            textureWidth,
            textureHeight,
            width,
            height,
            maskRadius,
            maskPadding,
            blurDirectionX = blurDirectionX,
            blurDirectionY = blurDirectionY,
            opaqueSource = opaqueSource,
            alphaMask = alphaMask,
        )
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        val placement = imagePlacement(width, height, fit, texture, uv)
        val finalTint = tint.withOpacity(opacity).filtered(filter)
        val quadTransform = transform.translated(placement.x, placement.y)
        if (fit.isSliced) {
            addSlicedTexturedQuad(buffer, quadTransform, placement, texture, fit, slice, flipY, finalTint)
            BufferUploader.drawWithShader(buffer.buildOrThrow())
            return
        }
        addTexturedQuad(buffer, quadTransform, placement, flipY, finalTint, subdivisions)
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    fun drawTexturedQuads(
        texture: ResourceLocation,
        quads: List<UiTexturedQuad>,
        filter: UiFilterChain = UiFilterChain.Empty,
    ) {
        if (quads.isEmpty()) return
        val textureSize = textureSize(texture)
        val shaderTextureSize = textureSize ?: (1f to 1f)
        RenderSystem.setShaderTexture(0, texture)
        setTextureShader(filter, shaderTextureSize.first, shaderTextureSize.second, alphaMask = quads.any { it.alphaMask })
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        for (quad in quads) {
            val placement = quad.region ?: imagePlacement(quad.width, quad.height, quad.fit, textureSize, quad.uv)
            val finalTint = quad.tint.withOpacity(quad.opacity).filtered(filter)
            val quadTransform = quad.transform.translated(placement.x, placement.y)
            if (quad.fit.isSliced) {
                addSlicedTexturedQuad(
                    buffer,
                    quadTransform,
                    placement,
                    texture,
                    quad.fit,
                    quad.slice,
                    quad.flipY,
                    finalTint
                )
            } else {
                addTexturedQuad(buffer, quadTransform, placement, quad.flipY, finalTint)
            }
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    fun drawTexturedRegion(
        texture: Int,
        width: Float,
        height: Float,
        transform: UiMatrix4,
        opacity: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        flipY: Boolean,
        filter: UiFilterChain,
        textureWidth: Int,
        textureHeight: Int,
        subdivisions: Int = 1,
        maskRadius: Float = 0f,
        maskScale: Float = 0f,
        maskPadding: Float = 0f,
        blurDirectionX: Float = 0f,
        blurDirectionY: Float = 0f,
        tint: UiColor = UiColor.White,
        opaqueSource: Boolean = false,
        alphaMask: Boolean = false,
    ) {
        GlStateManager._bindTexture(texture)
        RenderSystem.setShaderTexture(0, texture)
        setTextureShader(
            filter,
            textureWidth.toFloat(),
            textureHeight.toFloat(),
            logicalWidth = width,
            logicalHeight = height,
            maskRadius = maskRadius,
            maskPadding = maskPadding,
            maskScale = maskScale,
            maskU = minOf(u0, u1),
            maskV = minOf(v0, v1),
            maskWidth = abs(u1 - u0),
            maskHeight = abs(v1 - v0),
            sampleU = minOf(u0, u1),
            sampleV = minOf(v0, v1),
            sampleWidth = abs(u1 - u0),
            sampleHeight = abs(v1 - v0),
            blurDirectionX = blurDirectionX,
            blurDirectionY = blurDirectionY,
            opaqueSource = opaqueSource,
            alphaMask = alphaMask,
        )
        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        val finalTint = tint.withOpacity(opacity)
        addTexturedQuad(
            buffer,
            transform,
            ImagePlacement(0f, 0f, width, height, u0, v0, u1, v1),
            flipY,
            finalTint,
            subdivisions,
        )
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }

    fun drawTexturedShapeRegion(
        texture: Int,
        width: Float,
        height: Float,
        shape: Shape,
        transform: UiMatrix4,
        opacity: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        flipY: Boolean,
        filter: UiFilterChain,
        textureWidth: Int,
        textureHeight: Int,
        tint: UiColor = UiColor.White,
        alphaMask: Boolean = false,
    ) {
        val triangles = cachedFillTriangles(shape, width, height)
        if (triangles.isEmpty()) return
        withCullStatePreserved {
            RenderSystem.disableCull()
            GlStateManager._bindTexture(texture)
            RenderSystem.setShaderTexture(0, texture)
            setTextureShader(
                filter,
                textureWidth.toFloat(),
                textureHeight.toFloat(),
                logicalWidth = width,
                logicalHeight = height,
                sampleU = minOf(u0, u1),
                sampleV = minOf(v0, v1),
                sampleWidth = abs(u1 - u0),
                sampleHeight = abs(v1 - v0),
                alphaMask = alphaMask,
            )
            val buffer =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR)
            val finalTint = tint.withOpacity(opacity)
            triangles.forEach { triangle ->
                addShapeVertex(buffer, transform, width, height, triangle.first, u0, v0, u1, v1, flipY, finalTint)
                addShapeVertex(buffer, transform, width, height, triangle.second, u0, v0, u1, v1, flipY, finalTint)
                addShapeVertex(buffer, transform, width, height, triangle.third, u0, v0, u1, v1, flipY, finalTint)
            }
            BufferUploader.drawWithShader(buffer.buildOrThrow())
        }
    }

    private fun setTextureShader(
        filter: UiFilterChain,
        textureWidth: Float,
        textureHeight: Float,
        logicalWidth: Float = textureWidth,
        logicalHeight: Float = textureHeight,
        maskRadius: Float = 0f,
        maskPadding: Float = 0f,
        maskScale: Float = 0f,
        maskU: Float? = null,
        maskV: Float? = null,
        maskWidth: Float? = null,
        maskHeight: Float? = null,
        sampleU: Float? = null,
        sampleV: Float? = null,
        sampleWidth: Float? = null,
        sampleHeight: Float? = null,
        blurDirectionX: Float = 0f,
        blurDirectionY: Float = 0f,
        opaqueSource: Boolean = false,
        alphaMask: Boolean = false,
    ) {
        val effectShader = ModShaders.UI_EFFECT
        val hasMask = maskRadius > 0f
        if (filter.effects.isEmpty() && !hasMask && !alphaMask && !opaqueSource || effectShader == null) {
            // Nothing the effect shader would do differently; the plain path is cheaper.
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
            configureUiBlend()
            return
        }
        RenderSystem.setShader { effectShader }
        configureUiBlend()
        effectShader.getUniform("Grayscale")?.set(filter.grayscaleAmount())
        val blurRadius = filter.blurRadius()
        effectShader.getUniform("BlurRadius")?.set(blurRadius)
        effectShader.getUniform("BlurSampleScale")?.set(gaussianSampleScale(blurRadius))
        effectShader.getUniform("BlurDirection")?.set(blurDirectionX, blurDirectionY)
        effectShader.getUniform("TexelSize")
            ?.set(1f / textureWidth.coerceAtLeast(1f), 1f / textureHeight.coerceAtLeast(1f))
        val radiusScale = maskScale.takeIf { it > 0f } ?: (((textureWidth / logicalWidth.coerceAtLeast(1f)) +
                (textureHeight / logicalHeight.coerceAtLeast(1f))) * 0.5f)
        val padU = maskPadding * radiusScale / textureWidth.coerceAtLeast(1f)
        val padV = maskPadding * radiusScale / textureHeight.coerceAtLeast(1f)
        val finalMaskU = if (maskU != null) maskU + padU else padU
        val finalMaskV = if (maskV != null) maskV + padV else padV
        val finalMaskW = (if (maskWidth != null) maskWidth - padU * 2f else 1f - padU * 2f).coerceAtLeast(0f)
        val finalMaskH = (if (maskHeight != null) maskHeight - padV * 2f else 1f - padV * 2f).coerceAtLeast(0f)
        val finalSampleU = sampleU ?: 0f
        val finalSampleV = sampleV ?: 0f
        val finalSampleW = sampleWidth ?: 1f
        val finalSampleH = sampleHeight ?: 1f
        effectShader.getUniform("SampleRect")?.set(finalSampleU, finalSampleV, finalSampleW, finalSampleH)
        effectShader.getUniform("MaskRect")?.set(finalMaskU, finalMaskV, finalMaskW, finalMaskH)
        effectShader.getUniform("MaskRadius")?.set(maskRadius * radiusScale)
        effectShader.getUniform("MaskSoftness")?.set(1.25f * radiusScale)
        effectShader.getUniform("OpaqueSource")?.set(if (opaqueSource) 1f else 0f)
        effectShader.getUniform("AlphaMask")?.set(if (alphaMask) 1f else 0f)
        setGradientMask(effectShader, filter.linearMask())
    }

    /**
     * Feeds the gradient mask to the shader. Stops are sorted and padded out to the fixed size the
     * shader carries; the direction is the angle turned into a UV vector, with 0deg pointing up as CSS
     * does rather than along +x.
     */
    private fun setGradientMask(shader: ShaderInstance, mask: UiFilterEffect.LinearMask?) {
        if (mask == null) {
            shader.getUniform("GradientCount")?.set(0f)
            return
        }
        val stops = mask.stops
            .sortedBy { it.position }
            .take(UiFilterEffect.LinearMask.MAX_STOPS)
        val radians = linearGradientRadians(mask.angle)
        shader.getUniform("GradientCount")?.set(stops.size.toFloat())
        shader.getUniform("GradientDirection")?.set(cos(radians).toFloat(), sin(radians).toFloat())
        shader.getUniform("GradientStops")?.set(
            stops.getOrNull(0)?.position ?: 0f,
            stops.getOrNull(1)?.position ?: 1f,
            stops.getOrNull(2)?.position ?: 1f,
            stops.getOrNull(3)?.position ?: 1f,
        )
        shader.getUniform("GradientAlphas")?.set(
            stops.getOrNull(0)?.alpha ?: 1f,
            stops.getOrNull(1)?.alpha ?: 1f,
            stops.getOrNull(2)?.alpha ?: 1f,
            stops.getOrNull(3)?.alpha ?: 1f,
        )
    }

    private fun addTexturedVertex(
        buffer: BufferBuilder,
        transform: UiMatrix4,
        width: Float,
        height: Float,
        xProgress: Float,
        yProgress: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        flipY: Boolean,
        tint: UiColor,
    ) {
        val point = transform.transform(width * xProgress, height * yProgress)
        val u = u0 + (u1 - u0) * xProgress
        val baseV = v0 + (v1 - v0) * yProgress
        val flippedV = v1 + (v0 - v1) * yProgress
        val v = if (flipY) flippedV else baseV
        buffer.addVertex(point.x, point.y, point.z).setUv(u, v).setColor(tint.red, tint.green, tint.blue, tint.alpha)
    }

    private fun addShapeVertex(
        buffer: BufferBuilder,
        transform: UiMatrix4,
        width: Float,
        height: Float,
        point: UiPathPoint,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        flipY: Boolean,
        tint: UiColor,
    ) {
        val position = transform.transform(point.x, point.y)
        val xProgress = point.x / width.coerceAtLeast(0.0001f)
        val yProgress = point.y / height.coerceAtLeast(0.0001f)
        val u = u0 + (u1 - u0) * xProgress
        val baseV = v0 + (v1 - v0) * yProgress
        val flippedV = v1 + (v0 - v1) * yProgress
        val v = if (flipY) flippedV else baseV
        buffer.addVertex(position.x, position.y, position.z)
            .setUv(u, v)
            .setColor(tint.red, tint.green, tint.blue, tint.alpha)
    }

    private fun addTexturedQuad(
        buffer: BufferBuilder,
        transform: UiMatrix4,
        placement: ImagePlacement,
        flipY: Boolean,
        tint: UiColor,
        subdivisions: Int = 1,
    ) {
        val segments = subdivisions.coerceAtLeast(1)
        for (yIndex in 0 until segments) {
            val y0 = yIndex.toFloat() / segments.toFloat()
            val y1 = (yIndex + 1).toFloat() / segments.toFloat()
            for (xIndex in 0 until segments) {
                val x0 = xIndex.toFloat() / segments.toFloat()
                val x1 = (xIndex + 1).toFloat() / segments.toFloat()
                addTexturedVertex(
                    buffer,
                    transform,
                    placement.width,
                    placement.height,
                    x0,
                    y0,
                    placement.u0,
                    placement.v0,
                    placement.u1,
                    placement.v1,
                    flipY,
                    tint
                )
                addTexturedVertex(
                    buffer,
                    transform,
                    placement.width,
                    placement.height,
                    x0,
                    y1,
                    placement.u0,
                    placement.v0,
                    placement.u1,
                    placement.v1,
                    flipY,
                    tint
                )
                addTexturedVertex(
                    buffer,
                    transform,
                    placement.width,
                    placement.height,
                    x1,
                    y1,
                    placement.u0,
                    placement.v0,
                    placement.u1,
                    placement.v1,
                    flipY,
                    tint
                )
                addTexturedVertex(
                    buffer,
                    transform,
                    placement.width,
                    placement.height,
                    x1,
                    y0,
                    placement.u0,
                    placement.v0,
                    placement.u1,
                    placement.v1,
                    flipY,
                    tint
                )
            }
        }
    }

    private fun addSlicedTexturedQuad(
        buffer: BufferBuilder,
        transform: UiMatrix4,
        placement: ImagePlacement,
        texture: ResourceLocation?,
        fit: UiImageFit,
        slice: UiInsets,
        flipY: Boolean,
        tint: UiColor,
    ) {
        val textureSize = texture?.let(::textureSize) ?: (placement.width to placement.height)
        val sourceSize = textureSize.first * abs(placement.u1 - placement.u0) to
                textureSize.second * abs(placement.v1 - placement.v0)
        val slices =
            slice.resolve(placement.width, placement.height).clamp(placement.width, placement.height, sourceSize)
        val horizontal = when (fit) {
            UiImageFit.THREE_SLICE_VERTICAL -> listOf(SliceSpan(0f, placement.width, 0f, 1f))
            else -> sliceSpans(placement.width, sourceSize.first, slices.left, slices.right)
        }
        val vertical = when (fit) {
            UiImageFit.THREE_SLICE_HORIZONTAL -> listOf(SliceSpan(0f, placement.height, 0f, 1f))
            else -> sliceSpans(placement.height, sourceSize.second, slices.top, slices.bottom)
        }
        for (y in vertical) {
            if (y.destinationEnd <= y.destinationStart) continue
            for (x in horizontal) {
                if (x.destinationEnd <= x.destinationStart) continue
                addTexturedPatch(
                    buffer,
                    transform,
                    x.destinationStart,
                    y.destinationStart,
                    x.destinationEnd,
                    y.destinationEnd,
                    placement.u(x.sourceStart),
                    placement.v(y.sourceStart),
                    placement.u(x.sourceEnd),
                    placement.v(y.sourceEnd),
                    flipY,
                    tint,
                )
            }
        }
    }

    private fun addTexturedPatch(
        buffer: BufferBuilder,
        transform: UiMatrix4,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        flipY: Boolean,
        tint: UiColor,
    ) {
        addTexturedPatchVertex(buffer, transform, x0, y0, u0, if (flipY) v1 else v0, tint)
        addTexturedPatchVertex(buffer, transform, x0, y1, u0, if (flipY) v0 else v1, tint)
        addTexturedPatchVertex(buffer, transform, x1, y1, u1, if (flipY) v0 else v1, tint)
        addTexturedPatchVertex(buffer, transform, x1, y0, u1, if (flipY) v1 else v0, tint)
    }

    private fun addTexturedPatchVertex(
        buffer: BufferBuilder,
        transform: UiMatrix4,
        x: Float,
        y: Float,
        u: Float,
        v: Float,
        tint: UiColor,
    ) {
        val point = transform.transform(x, y)
        buffer.addVertex(point.x, point.y, point.z).setUv(u, v).setColor(tint.red, tint.green, tint.blue, tint.alpha)
    }
}

private val UiImageFit.isSliced: Boolean
    get() = this == UiImageFit.NINE_SLICE ||
            this == UiImageFit.THREE_SLICE_VERTICAL ||
            this == UiImageFit.THREE_SLICE_HORIZONTAL

private data class SliceSpan(
    val destinationStart: Float,
    val destinationEnd: Float,
    val sourceStart: Float,
    val sourceEnd: Float,
)

private data class TextureSlices(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun clamp(width: Float, height: Float, textureSize: Pair<Float, Float>): TextureSlices {
        val horizontalSlices = (left + right).coerceAtLeast(1f)
        val verticalSlices = (top + bottom).coerceAtLeast(1f)
        val horizontalScale = minOf(1f, width / horizontalSlices, textureSize.first / horizontalSlices)
        val verticalScale = minOf(1f, height / verticalSlices, textureSize.second / verticalSlices)
        return TextureSlices(
            left = left * horizontalScale,
            top = top * verticalScale,
            right = right * horizontalScale,
            bottom = bottom * verticalScale,
        )
    }
}

private fun UiInsets.resolve(width: Float, height: Float): TextureSlices = TextureSlices(
    left = left.resolve(width),
    top = top.resolve(height),
    right = right.resolve(width),
    bottom = bottom.resolve(height),
)

private fun sliceSpans(size: Float, sourceSize: Float, startSlice: Float, endSlice: Float): List<SliceSpan> {
    val source = sourceSize.coerceAtLeast(1f)
    return listOf(
        SliceSpan(0f, startSlice, 0f, startSlice / source),
        SliceSpan(startSlice, size - endSlice, startSlice / source, 1f - endSlice / source),
        SliceSpan(size - endSlice, size, 1f - endSlice / source, 1f),
    )
}
