package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexSorting
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.SimpleTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.render.render
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutResult
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.*
import ru.hollowhorizon.hollowengine.client.ui.text.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.popPose
import ru.hollowhorizon.hollowengine.client.utils.pushPose
import ru.hollowhorizon.hollowengine.client.utils.setIdentity
import ru.hollowhorizon.hollowengine.common.entities.nameplateMode
import ru.hollowhorizon.hollowengine.common.attachments.components.NameplateMode
import ru.hollowhorizon.hollowengine.common.registry.ModShaders
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

private data class SvgRasterKey(
    val location: ResourceLocation,
    val revision: Long,
    val width: Int,
    val height: Int,
)

private data class SvgRasterTexture(
    val location: ResourceLocation,
    val texture: DynamicTexture,
)

private data class SvgRasterQuad(
    val texture: ResourceLocation,
    val width: Float,
    val height: Float,
    val transform: UiMatrix4,
)

/** The slot size vanilla's item decorations are drawn for; anything else is scaled from it. */
private const val VanillaSlotSize = 16f

/** How far items may march toward the viewer before the projection's near plane would clip them. */
private const val MaxItemDepth = 900f
private const val MinSvgRasterSize = 16
private const val MaxSvgRasterSize = 4096
private const val TextClipEpsilon = 0.01f
private const val ProjectiveLayerTextureSubdivisions = 12

private const val PORTRAIT_YAW = 180f

/** Color links are drawn in when the style does not name one. */
private val LinkColor = UiColor(0.34f, 0.67f, 1f, 1f)

/** Below this an underline can fall between pixel centers and vanish entirely. */
private const val MinTextRuleThickness = 0.5f

/** Corner rounding of a span background or an inline code chip. */
private const val TextRectRadius = 2f

/** Below this a second stamp is not worth a draw: it would land inside the first one's edge. */
private const val MinBoldStampOffset = 0.15f

internal fun layerTextureSubdivisions(transform: UiMatrix4): Int =
    if (transform.hasPlanarPerspective) ProjectiveLayerTextureSubdivisions else 1

internal fun requiresTextClip(
    overflow: UiTextOverflow,
    contentWidth: Float,
    contentHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    scrollX: Float,
    scrollY: Float,
): Boolean {
    if (overflow == UiTextOverflow.SHOW) return false
    return abs(scrollX) > TextClipEpsilon ||
            abs(scrollY) > TextClipEpsilon ||
            contentWidth > viewportWidth + TextClipEpsilon ||
            contentHeight > viewportHeight + TextClipEpsilon
}

internal fun svgRasterPixelSize(size: Float, scale: Float): Int {
    val requiredSize = ceil(size * scale).toInt().coerceIn(1, MaxSvgRasterSize)
    if (requiredSize <= MinSvgRasterSize) return MinSvgRasterSize
    return Integer.highestOneBit(requiredSize - 1).shl(1).coerceAtMost(MaxSvgRasterSize)
}

internal fun UiRenderCommand.isSegmentBatchable(): Boolean = when (this) {
    is DrawShadowCommand -> true
    is DrawBoxCommand -> !renderToFramebuffer
    is DrawShapeCommand -> true
    is DrawImageCommand -> !renderToFramebuffer && filter == UiFilterChain.Empty
    is DrawParticlesCommand -> filter == UiFilterChain.Empty
    is DrawTextCommand -> true
    is PushClipCommand, is PopClipCommand -> true
    else -> false
}

class MinecraftUiRenderer {
    private val commandRenderer = UiCommandRenderer()
    private val segment = ArrayList<UiRenderCommand>()
    private val framebuffers = UiFramebufferPool()
    private val imageShadows = UiImageShadowCache()
    private val layerStack = ArrayDeque<LayerState>()
    private val clipStack = ArrayDeque<UiRect>()
    private val shapeBatch = UiTriangleBatch()
    private val analyticRectBatch = UiAnalyticRectBatch()
    private val analyticRectRenderer = UiAnalyticRectRenderer()
    private val pathTileBatch = UiPathTileBatch()
    private val pathTileRenderer = UiPathTileRenderer()
    private val msdfTextBatch = UiMsdfTextBatch()
    private var emitUnifiedGlyphs = false
    private var glyphClip = UiShaderClip.None
    private val layerRequests = mutableListOf<UiLayerRequest>()

    /** Padding each layer's framebuffer was reserved with this frame; see [prepareFramebuffers]. */
    private val layerPaddings = IdentityHashMap<UiNode, Float>()
    private val preparedLayers = IdentityHashMap<UiNode, PreparedUiLayer>()
    private val brokenSvgSources = mutableSetOf<String>()
    private val svgRasterTextures = ConcurrentHashMap<SvgRasterKey, SvgRasterTexture>()
    private var svgRasterGeneration = -1
    private var layerProjectionActive = false
    private var renderTarget: UiRenderTarget? = null
    private var preparingLayerAtlas = false
    private val itemAxisScales = FloatArray(2)
    private val resolutionScratch = FloatArray(2)

    /**
     * Depth handed to the next item this frame.
     *
     * Item models are the only thing in a UI frame that writes depth, and they write it around their own
     * z. Two of them at the same z cut into each other wherever they overlap on screen: neighbouring 3D
     * blocks, or the cursor stack passing over a slot. Each item therefore gets its own slice, advancing in
     * draw order so a later item is always in front, which is the painter's order the rest of the UI uses.
     */
    private var itemDepthOffset = 0f

    /** The profile frame collecting GPU-submission counts, live only during [render]. */
    private var activeProfile: UiProfileFrame? = null

    private val analyticBatchBounds = UiBatchBounds()
    private val pathTileBatchBounds = UiBatchBounds()
    private val shapeBatchBounds = UiBatchBounds()
    private val imageBatchBounds = UiBatchBounds()
    private val textBatchBounds = UiBatchBounds()
    private val phaseImageBatches = linkedMapOf<ResourceLocation, MutableList<UiTexturedQuad>>()
    private var quadMinX = 0f
    private var quadMinY = 0f
    private var quadMaxX = 0f
    private var quadMaxY = 0f

    private val phaseClipStack = ArrayDeque<UiRect>()
    private val segmentBaseClips = ArrayList<UiRect>()
    private var phaseClip: UiRect? = null
    private var pathTileBatchClip: UiRect? = null
    private var shapeBatchClip: UiRect? = null
    private var imageBatchClip: UiRect? = null
    private var textBatchClip: UiRect? = null
    private val segmentPhases = BooleanArray(UiRenderPhase.entries.size)

    private var scissorState: Any? = ScissorUnknown

    private fun setScissor(clip: UiRect?) {
        if (scissorState !== ScissorUnknown && scissorState == clip) return
        activeProfile?.scissorChanges++
        if (clip != null) {
            applyScissor(clip)
        } else {
            disableScissor()
            scissorState = null
        }
    }

    private enum class UiBatchKind { ANALYTIC_RECT, PATH_TILE, SHAPE, IMAGE, TEXT }

    /** Screen-space AABB of a (0,0,width,height) quad under [transform] into quadMin/Max fields. */
    private fun computeQuadBounds(width: Float, height: Float, transform: UiMatrix4, padding: Float = 0f) {
        if (transform.isAxisAligned) {
            val x0 = transform.transformX(0f)
            val x1 = transform.transformX(width)
            val y0 = transform.transformY(0f)
            val y1 = transform.transformY(height)
            quadMinX = min(x0, x1) - padding
            quadMaxX = max(x0, x1) + padding
            quadMinY = min(y0, y1) - padding
            quadMaxY = max(y0, y1) + padding
            return
        }
        val c0 = transform.transform(0f, 0f)
        val c1 = transform.transform(width, 0f)
        val c2 = transform.transform(width, height)
        val c3 = transform.transform(0f, height)
        quadMinX = min(min(c0.x, c1.x), min(c2.x, c3.x)) - padding
        quadMaxX = max(max(c0.x, c1.x), max(c2.x, c3.x)) + padding
        quadMinY = min(min(c0.y, c1.y), min(c2.y, c3.y)) - padding
        quadMaxY = max(max(c0.y, c1.y), max(c2.y, c3.y)) + padding
    }

    private fun quadFullyClipped(): Boolean {
        val clip = phaseClip ?: return false
        return quadMaxX <= clip.x || quadMinX >= clip.x + clip.width ||
                quadMaxY <= clip.y || quadMinY >= clip.y + clip.height
    }

    /** Flushes every pending batch (except [except]) whose pixels the current quad overlaps. */
    private fun flushBatchesOverlappingQuad(except: UiBatchKind?) {
        if (except != UiBatchKind.ANALYTIC_RECT &&
            analyticBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        ) flushAnalyticRectBatch()
        if (except != UiBatchKind.PATH_TILE &&
            pathTileBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        ) flushPathTileBatch()
        if (except != UiBatchKind.SHAPE &&
            shapeBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        ) flushShapeBatch()
        if (except != UiBatchKind.IMAGE &&
            imageBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        ) flushPhaseImageBatches()
        if (except != UiBatchKind.TEXT &&
            textBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        ) flushTextBatch()
    }

    /** Renders directly unless framebuffer layers require a collected atlas pre-pass. */
    fun render(frame: HollowUiFrame, target: UiRenderTarget? = null) {
        val profile = frame.profile
        val renderStartedAt = if (profile != null) System.nanoTime() else 0L
        activeProfile = profile
        val previousTarget = renderTarget
        renderTarget = target
        val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        val blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        try {
            UiGlyphFonts.pumpDynamicGlyphs()
            if (svgRasterGeneration != UiPathTileResources.generation) {
                releaseSvgRasters()
                svgRasterGeneration = UiPathTileResources.generation
            }
            imageShadows.beginFrame()
            releasePreparedLayers()
            itemDepthOffset = 0f
            val hasFramebufferLayers = prepareFramebuffers(frame.layout)
            RenderSystem.enableBlend()
            configureUiBlend()
            RenderSystem.disableDepthTest()
            GL11.glDepthMask(false)
            bindRootTarget()
            clearSegment()
            if (hasFramebufferLayers) {
                val plan = UiLayerRenderPlan.create(commandRenderer.collect(frame.root, frame.layout, profile))
                prepareLayerAtlas(plan)
                bindRootTarget()
                renderPreparedRoot(plan)
            } else {
                commandRenderer.render(frame.root, frame.layout, ::submit, profile)
            }
            renderSegment(segment)
            clearSegment()
            flushTextBatch()
            disableScissor()
            scissorState = null
            while (layerStack.isNotEmpty()) finishLayer()
        } finally {
            if (layerProjectionActive) restoreMainProjection()
            GL11.glDepthMask(depthMask)
            uiWriteAlpha(true)
            RenderSystem.defaultBlendFunc()
            if (!blendEnabled) RenderSystem.disableBlend()
            releasePreparedLayers()
            renderTarget = previousTarget
            activeProfile?.let { it.imageDraws += imageShadows.draws }
            imageShadows.endFrame()
            activeProfile = null
            if (profile != null) {
                profile.renderNanos += System.nanoTime() - renderStartedAt
                profile.owner.complete(profile)
            }
        }
    }

    private fun submit(command: UiRenderCommand) {
        if (command.isSegmentBatchable()) {
            segment += command
            when (command) {
                is DrawShadowCommand -> segmentPhases[UiRenderPhase.BACKGROUND.ordinal] = true
                is DrawBoxCommand -> segmentPhases[command.phase.ordinal] = true
                is DrawShapeCommand -> segmentPhases[command.phase.ordinal] = true
                is DrawImageCommand -> segmentPhases[command.phase.ordinal] = true
                is DrawParticlesCommand -> segmentPhases[command.phase.ordinal] = true
                is DrawTextCommand -> segmentPhases[command.phase.ordinal] = true
                else -> Unit
            }
            return
        }
        renderSegment(segment)
        clearSegment()
        render(command)
        flushGeometryBatches()
        flushTextBatch()
    }

    private fun clearSegment() {
        segment.clear()
        segmentPhases.fill(false)
    }

    fun close() {
        while (layerStack.isNotEmpty()) finishLayer()
        releaseSvgRasters()
        analyticRectRenderer.close()
        pathTileRenderer.close()
        msdfTextBatch.close()
        framebuffers.close()
        imageShadows.close()
    }

    private fun render(command: UiRenderCommand) {
        when (command) {
            is BeginLayerCommand -> beginLayer(command)
            is EndLayerCommand -> finishLayer(preparingLayerAtlas && layerStack.size == 1)
            // The flush already happened in submit()
            is FlushBarrierCommand -> Unit
            is DrawBackdropFilterCommand -> drawBackdropFilter(command)
            is DrawShadowCommand -> drawShadow(command)
            is PushClipCommand -> pushClip(command.rect, command.transform)
            is PopClipCommand -> popClip()
            is DrawBoxCommand -> drawBox(command)
            is DrawShapeCommand -> drawShape(command)
            is DrawTextCommand -> drawText(command)
            is DrawImageCommand -> drawImage(command)
            is DrawRawTextureCommand -> drawRawTexture(command)
            is DrawParticlesCommand -> drawParticles(command)
            is DrawItemCommand -> drawItem(command)
            is DrawEntityCommand -> drawEntity(command)
            is DrawCanvasGlCommand -> drawCanvasGl(command)
        }
    }

    private fun prepareLayerAtlas(plan: UiLayerRenderPlan) {
        preparingLayerAtlas = true
        try {
            for (layer in plan.layers) {
                clipStack.clear()
                clearSegment()
                for (index in layer.startIndex..layer.endIndex) submit(plan.commands[index])
                renderSegment(segment)
                clearSegment()
                check(layerStack.isEmpty()) { "Framebuffer layer pre-pass left an unfinished nested layer" }
            }
        } finally {
            preparingLayerAtlas = false
            clipStack.clear()
            phaseClipStack.clear()
            flushPhaseImageBatches()
            flushGeometryBatches()
            flushTextBatch()
            restoreMainProjection()
        }
    }

    private fun renderPreparedRoot(plan: UiLayerRenderPlan) {
        var index = 0
        for (layer in plan.layers) {
            while (index < layer.startIndex) submit(plan.commands[index++])
            renderSegment(segment)
            clearSegment()
            compositePreparedLayer(layer.command.node)
            index = layer.endIndex + 1
        }
        while (index < plan.commands.size) submit(plan.commands[index++])
    }

    private fun compositePreparedLayer(node: UiNode) {
        val prepared = preparedLayers.remove(node) ?: return
        setScissor(clipStack.lastOrNull())
        val transform = calculateRootTransform(prepared.layer)
        drawLayerTexture(
            prepared.layer,
            prepared.source.texture,
            prepared.source.width,
            prepared.source.height,
            prepared.source.u0,
            prepared.source.v0,
            prepared.source.u1,
            prepared.source.v1,
            prepared.layer.filter.withoutBlur(),
            transform,
        )
        prepared.ownedSource?.let(framebuffers::release)
        RenderSystem.disableDepthTest()
        GL11.glDepthMask(false)
    }

    private fun releasePreparedLayers() {
        preparedLayers.values.forEach { it.ownedSource?.let(framebuffers::release) }
        preparedLayers.clear()
    }

    private fun renderSegment(commands: List<UiRenderCommand>) {
        if (commands.isEmpty()) return
        segmentBaseClips.clear()
        segmentBaseClips.addAll(clipStack)
        for (phase in UiRenderPhase.entries) {
            if (segmentPhases[phase.ordinal]) renderPhase(commands, phase)
        }
        commitSegmentClips(commands)
        flushPhaseImageBatches()
        flushGeometryBatches()
        flushTextBatch()
        setScissor(clipStack.lastOrNull())
    }

    /** Replays the segment's clip commands into the persistent [clipStack]. */
    private fun commitSegmentClips(commands: List<UiRenderCommand>) {
        clipStack.clear()
        clipStack.addAll(segmentBaseClips)
        for (command in commands) {
            when (command) {
                is PushClipCommand -> {
                    val local = transformedLocalRect(command.rect, effective(command.transform))
                    clipStack.addLast(clipStack.lastOrNull()?.intersect(local) ?: local)
                }

                is PopClipCommand -> if (clipStack.isNotEmpty()) clipStack.removeLast()
                else -> Unit
            }
        }
    }

    private fun renderPhase(commands: List<UiRenderCommand>, phase: UiRenderPhase) {
        phaseClipStack.clear()
        phaseClipStack.addAll(segmentBaseClips)
        phaseClip = phaseClipStack.lastOrNull()
        commands.forEach { command ->
            when (command) {
                is PushClipCommand -> {
                    val local = transformedLocalRect(command.rect, effective(command.transform))
                    val next = phaseClipStack.lastOrNull()?.intersect(local) ?: local
                    phaseClipStack.addLast(next)
                    phaseClip = next
                }

                is PopClipCommand -> {
                    if (phaseClipStack.isNotEmpty()) phaseClipStack.removeLast()
                    phaseClip = phaseClipStack.lastOrNull()
                }

                is DrawShadowCommand -> if (phase == UiRenderPhase.BACKGROUND) {
                    if (!appendShapeShadows(command)) {
                        var padding = 0f
                        for (shadow in command.shadows) {
                            padding = max(
                                padding,
                                max(abs(shadow.offset.x), abs(shadow.offset.y)) +
                                        shadow.blur.coerceAtLeast(0f) + shadow.spread.coerceAtLeast(0f),
                            )
                        }
                        computeQuadBounds(
                            command.rect.width, command.rect.height, effective(command.transform), padding,
                        )
                        flushBatchesOverlappingQuad(except = null)
                        setScissor(phaseClip)
                        drawShadow(command)
                    }
                }

                is DrawBoxCommand -> if (command.phase == phase) {
                    if (!appendAnalyticRect(command) && !appendBatchedShapes(command)) {
                        computeQuadBounds(command.rect.width, command.rect.height, effective(command.transform))
                        flushBatchesOverlappingQuad(except = null)
                        setScissor(phaseClip)
                        drawBox(command)
                    }
                }

                is DrawShapeCommand -> if (command.phase == phase) {
                    if (!appendPathTile(command) && !appendBatchedShapes(command)) {
                        computeQuadBounds(command.rect.width, command.rect.height, effective(command.transform))
                        flushBatchesOverlappingQuad(except = null)
                        setScissor(phaseClip)
                        drawShape(command)
                    }
                }

                is DrawImageCommand -> if (command.phase == phase) {
                    appendPhaseImage(command)
                }

                is DrawParticlesCommand -> if (command.phase == phase) {
                    appendPhaseParticles(command)
                }

                is DrawRawTextureCommand -> if (command.phase == phase) {
                    computeQuadBounds(command.rect.width, command.rect.height, effective(command.transform))
                    flushBatchesOverlappingQuad(except = null)
                    setScissor(phaseClip)
                    drawRawTexture(command)
                }

                is DrawTextCommand -> if (command.phase == phase) {
                    appendPhaseText(command)
                }

                else -> Unit
            }
        }
        flushPhaseImageBatches()
        phaseClip = phaseClipStack.lastOrNull()
    }

    private fun appendPhaseImage(command: DrawImageCommand) {
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return
        computeQuadBounds(command.rect.width, command.rect.height, effective(command.transform))
        if (quadFullyClipped()) return
        flushBatchesOverlappingQuad(UiBatchKind.IMAGE)
        val overlapsPendingImages = imageBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        if (phaseImageBatches.isNotEmpty() && (imageBatchClip != phaseClip || overlapsPendingImages)) {
            flushPhaseImageBatches()
        }
        if (!appendSvgImage(command, phaseImageBatches)) appendImageBatch(command, phaseImageBatches)
        imageBatchClip = phaseClip
        imageBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
    }

    private fun appendPhaseParticles(command: DrawParticlesCommand) {
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        computeQuadBounds(command.rect.width, command.rect.height, transform)
        if (quadFullyClipped()) return
        flushBatchesOverlappingQuad(UiBatchKind.IMAGE)
        val overlapsOtherTextures = phaseImageBatches.keys.any { it != TextureAtlas.LOCATION_PARTICLES } &&
                imageBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
        if (phaseImageBatches.isNotEmpty() && (imageBatchClip != phaseClip || overlapsOtherTextures)) {
            flushPhaseImageBatches()
        }
        appendParticleQuads(command, transform, phaseImageBatches)
        imageBatchClip = phaseClip
        imageBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
    }

    private fun drawParticles(command: DrawParticlesCommand) {
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        val batches = linkedMapOf<ResourceLocation, MutableList<UiTexturedQuad>>()
        appendParticleQuads(command, transform, batches)
        activeProfile?.let { it.imageDraws += batches.size }
        batches.forEach { (texture, quads) -> UiTextureEffects.drawTexturedQuads(texture, quads, command.filter) }
    }

    private fun appendPhaseText(command: DrawTextCommand) {
        val boundsWidth = max(command.rect.width, command.layout.maxNaturalLineWidth)
        val boundsHeight = max(command.rect.height, command.layout.height)
        computeQuadBounds(boundsWidth, boundsHeight, effective(command.transform))
        if (quadFullyClipped()) return
        if (analyticRectRenderer.isAvailable) {
            flushBatchesOverlappingQuad(UiBatchKind.ANALYTIC_RECT)
            clipStack.clear()
            clipStack.addAll(phaseClipStack)
            drawText(command)
            analyticBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        } else {
            flushBatchesOverlappingQuad(UiBatchKind.TEXT)
            if (!msdfTextBatch.isEmpty && textBatchClip != phaseClip) flushMsdfTextBatch()
            textBatchClip = phaseClip
            clipStack.clear()
            clipStack.addAll(phaseClipStack)
            setScissor(phaseClip)
            drawText(command)
            textBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        }
    }

    private fun flushPhaseImageBatches() {
        if (phaseImageBatches.isNotEmpty()) setScissor(imageBatchClip)
        flushImageBatches(phaseImageBatches)
        imageBatchBounds.clear()
    }

    private fun flushImageBatches(imageBatches: MutableMap<ResourceLocation, MutableList<UiTexturedQuad>>) {
        if (imageBatches.isEmpty()) return
        activeProfile?.let { it.imageDraws += imageBatches.size }
        imageBatches.forEach { (texture, quads) -> UiTextureEffects.drawTexturedQuads(texture, quads) }
        imageBatches.clear()
    }

    private fun appendImageBatch(
        command: DrawImageCommand,
        batches: MutableMap<ResourceLocation, MutableList<UiTexturedQuad>>,
    ) {
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return
        val location = ResourceLocation.tryParse(command.source) ?: return
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        batches.getOrPut(location) { mutableListOf() } += UiTexturedQuad(
            width = command.rect.width,
            height = command.rect.height,
            transform = transform,
            opacity = command.opacity,
            fit = command.fit,
            slice = command.slice,
            tint = command.tint,
            uv = command.uv,
        )
    }

    private fun appendSvgImage(
        command: DrawImageCommand,
        batches: MutableMap<ResourceLocation, MutableList<UiTexturedQuad>>,
    ): Boolean {
        val quad = svgRasterQuad(
            width = command.rect.width,
            height = command.rect.height,
            source = command.source,
            opacity = command.opacity,
            transform = effective(command.transform),
            fit = command.fit,
            backfaceVisibility = command.backfaceVisibility,
        ) ?: return svgLocation(command.source) != null
        batches.getOrPut(quad.texture) { mutableListOf() } += UiTexturedQuad(
            width = quad.width,
            height = quad.height,
            transform = quad.transform,
            opacity = command.opacity,
            fit = command.fit.svgRasterDrawFit(),
            slice = command.slice,
            tint = command.tint,
        )
        return true
    }

    private fun svgRasterQuad(
        width: Float,
        height: Float,
        source: String,
        opacity: Float,
        transform: UiMatrix4,
        fit: UiImageFit,
        backfaceVisibility: UiBackfaceVisibility = UiBackfaceVisibility.VISIBLE,
    ): SvgRasterQuad? {
        val location = svgLocation(source) ?: return null
        if (width <= 0f || height <= 0f || opacity <= 0f) return null
        if (isBackfaceHidden(width, height, transform, backfaceVisibility)) return null
        val revision = HollowUiResourceAccess.version(location)
        return runCatching {
            val intrinsicSize = UiSvgRasterizer.intrinsicSize(location, revision)
            val placement = imagePlacement(width, height, fit, intrinsicSize)
            val resolution = transformResolutionScale(transform, resolutionScratch)
            val pixelWidth = rasterPixelSize(placement.width * resolution)
            val pixelHeight = rasterPixelSize(placement.height * resolution)
            val texture =
                svgRasterTextures.computeIfAbsent(SvgRasterKey(location, revision, pixelWidth, pixelHeight)) { key ->
                    createSvgRasterTexture(key)
                }
            SvgRasterQuad(
                texture = texture.location,
                width = placement.width,
                height = placement.height,
                transform = transform.translated(placement.x, placement.y),
            )
        }.getOrElse { error ->
            if (brokenSvgSources.add(source)) {
                HollowEngine.LOGGER.error("Error while loading SVG image $source", error)
            }
            null
        }
    }

    private fun rasterPixelSize(size: Float): Int {
        return svgRasterPixelSize(size, layerScale())
    }

    private fun createSvgRasterTexture(key: SvgRasterKey): SvgRasterTexture {
        val image = UiSvgRasterizer.rasterize(key.location, key.revision, key.width, key.height)
        val texture = DynamicTexture(image)
        val location = svgDynamicTextureLocation(key)
        Minecraft.getInstance().textureManager.register(location, texture)
        return SvgRasterTexture(location, texture)
    }

    private fun svgDynamicTextureLocation(key: SvgRasterKey): ResourceLocation {
        val hash = key.hashCode().toString().replace("-", "n")
        return ResourceLocation.fromNamespaceAndPath(HollowEngine.MODID, "generated/ui/svg/$hash")
    }

    private fun flushShapeBatch() {
        shapeBatchBounds.clear()
        if (shapeBatch.isEmpty) return
        setScissor(shapeBatchClip)
        activeProfile?.shapeDraws++
        drawBatchedTriangles(shapeBatch)
        shapeBatch.clear()
    }

    private fun flushAnalyticRectBatch() {
        analyticBatchBounds.clear()
        if (!analyticRectBatch.isEmpty) {
            setScissor(null)
            activeProfile?.analyticRectDraws++
            analyticRectRenderer.draw(analyticRectBatch)
        }
        analyticRectBatch.clear()
    }

    /** The active phase clip as a shader-space rect (or [UiShaderClip.None] when unclipped). */
    private fun phaseShaderClip(): UiShaderClip {
        val clip = phaseClip ?: return UiShaderClip.None
        return UiShaderClip(clip.x, clip.y, clip.x + clip.width, clip.y + clip.height)
    }

    /**
     * The screen-space AABB of a node's local `(0,0)-(w,h)` viewport under [transform], as a shader
     * clip. Used to bound overflow-clipped text to its content box without a GL scissor. For rotated
     * transforms this is the loose axis-aligned bound, matching what the scissor would have clipped.
     */
    private fun localViewportClip(rect: UiRect, transform: UiMatrix4): UiShaderClip {
        val c0 = transform.transform(0f, 0f)
        val c1 = transform.transform(rect.width, 0f)
        val c2 = transform.transform(rect.width, rect.height)
        val c3 = transform.transform(0f, rect.height)
        return UiShaderClip(
            minOf(c0.x, c1.x, c2.x, c3.x),
            minOf(c0.y, c1.y, c2.y, c3.y),
            maxOf(c0.x, c1.x, c2.x, c3.x),
            maxOf(c0.y, c1.y, c2.y, c3.y),
        )
    }

    private fun flushPathTileBatch() {
        pathTileBatchBounds.clear()
        if (!pathTileBatch.isEmpty) {
            setScissor(pathTileBatchClip)
            activeProfile?.pathTileDraws++
            pathTileRenderer.draw(pathTileBatch)
        }
        pathTileBatch.clear()
    }

    private fun flushGeometryBatches() {
        flushAnalyticRectBatch()
        flushPathTileBatch()
        flushShapeBatch()
    }

    private fun flushMsdfTextBatch() {
        if (msdfTextBatch.isEmpty) return
        setScissor(textBatchClip)
        activeProfile?.msdfTextDraws++
        msdfTextBatch.flush()
    }

    private fun flushTextBatch() {
        textBatchBounds.clear()
        flushAnalyticRectBatch()
        flushMsdfTextBatch()
    }

    private fun appendBatchedShapes(command: UiRenderCommand): Boolean {
        if (command is DrawShapeCommand) return appendBatchedShape(command)
        if (command !is DrawBoxCommand) return false
        if (command.rect.width <= 0f || command.rect.height <= 0f) return false
        if (command.renderToFramebuffer) return false
        if (command.paint is UiResolvedPaint.Image || command.paint is UiResolvedPaint.Shader) return false
        val transform = effective(command.transform)
        if (isBackfaceHidden(
                command.rect.width, command.rect.height, transform, command.backfaceVisibility
            )
        ) return true
        computeQuadBounds(command.rect.width, command.rect.height, transform)
        if (quadFullyClipped()) return true
        flushBatchesOverlappingQuad(UiBatchKind.SHAPE)
        if (!shapeBatch.isEmpty && shapeBatchClip != phaseClip) flushShapeBatch()
        shapeBatchClip = phaseClip
        shapeBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        when (val paint = command.paint) {
            UiResolvedPaint.None -> Unit
            is UiResolvedPaint.Image,
            is UiResolvedPaint.Shader,
                -> return false

            is UiResolvedPaint.Color -> shapeBatch.appendLocalPaint(
                width = command.rect.width,
                height = command.rect.height,
                radius = command.border.radius,
                color = paint.color.withOpacity(command.opacity),
                transform = transform,
                filter = command.filter,
            )

            is UiResolvedPaint.LinearGradient -> shapeBatch.appendLocalGradient(
                width = command.rect.width,
                height = command.rect.height,
                radius = command.border.radius,
                angleDegrees = paint.angleDegrees,
                stops = paint.stops,
                opacity = command.opacity,
                transform = transform,
                filter = command.filter,
            )

            is UiResolvedPaint.RadialGradient -> shapeBatch.appendLocalRadialGradient(
                width = command.rect.width,
                height = command.rect.height,
                radius = command.border.radius,
                gradient = paint.gradient,
                opacity = command.opacity,
                transform = transform,
                filter = command.filter,
            )
        }
        appendBorderShapes(command, transform)
        return true
    }

    private fun appendBatchedShape(command: DrawShapeCommand): Boolean {
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return true
        val transform = effective(command.transform)
        if (isBackfaceHidden(
                command.rect.width, command.rect.height, transform, command.backfaceVisibility
            )
        ) return true
        val padding = command.strokeWidth.coerceAtLeast(0f) +
                command.blurRadius.coerceAtLeast(0f) + command.spreadRadius.coerceAtLeast(0f)
        computeQuadBounds(command.rect.width, command.rect.height, transform, padding)
        if (quadFullyClipped()) return true
        flushBatchesOverlappingQuad(UiBatchKind.SHAPE)
        if (!shapeBatch.isEmpty && shapeBatchClip != phaseClip) flushShapeBatch()
        shapeBatchClip = phaseClip
        shapeBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        return shapeBatch.appendLocalShape(
            shape = command.shape,
            width = command.rect.width,
            height = command.rect.height,
            fill = command.fill,
            stroke = command.stroke,
            strokeWidth = command.strokeWidth,
            strokeLineCap = command.strokeLineCap,
            strokeLineJoin = command.strokeLineJoin,
            opacity = command.opacity,
            transform = transform,
            filter = command.filter,
        )
    }

    private fun appendAnalyticRect(command: DrawBoxCommand): Boolean {
        if (!analyticRectBatch.canAppend(command) || !analyticRectRenderer.isAvailable) return false
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return true
        val transform = effective(command.transform)
        if (isBackfaceHidden(
                command.rect.width, command.rect.height, transform, command.backfaceVisibility
            )
        ) return true
        computeQuadBounds(command.rect.width, command.rect.height, transform)
        if (quadFullyClipped()) return true
        flushBatchesOverlappingQuad(UiBatchKind.ANALYTIC_RECT)
        analyticRectBatch.append(command, transform, phaseShaderClip())
        analyticBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        return true
    }

    private fun appendPathTile(command: DrawShapeCommand): Boolean {
        if (!pathTileBatch.canAppend(command) || !pathTileRenderer.isAvailable) return false
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return true
        val transform = effective(command.transform)
        if (isBackfaceHidden(
                command.rect.width, command.rect.height, transform, command.backfaceVisibility
            )
        ) return true
        val padding = command.strokeWidth.coerceAtLeast(0f) +
                command.blurRadius.coerceAtLeast(0f) + command.spreadRadius.coerceAtLeast(0f)
        computeQuadBounds(command.rect.width, command.rect.height, transform, padding)
        if (quadFullyClipped()) return true
        flushBatchesOverlappingQuad(UiBatchKind.PATH_TILE)
        if (!pathTileBatch.isEmpty && pathTileBatchClip != phaseClip) flushPathTileBatch()
        pathTileBatch.append(command, transform)
        pathTileBatchClip = phaseClip
        pathTileBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        return true
    }

    private fun appendBorderShapes(command: DrawBoxCommand, transform: UiMatrix4) {
        val borderWidth = command.border.width.left.resolve(command.rect.width)
        if (borderWidth <= 0f) return
        val width = command.rect.width
        val height = command.rect.height
        val colorAt = command.border.paint.resolve()
            .localColorAt(width, height, command.opacity, command.filter) ?: return
        val thickness = borderWidth.coerceAtLeast(1f).coerceAtMost(minOf(width, height) * 0.5f)
        shapeBatch.appendLocalBorder(width, height, command.border.radius, thickness, colorAt, transform)
    }

    private fun prepareFramebuffers(layout: UiLayoutResult): Boolean {
        val scale = layerScale()
        layerRequests.clear()
        layerPaddings.clear()
        val overflows = layout.layerOverflows()
        for (node in layout.traversalOrder) {
            val layoutNode = layout.nodes[node] ?: continue
            if (!layoutNode.needsFramebuffer) continue
            val padding = layerPadding(node.resolvedSnapshot.filter, overflows[node] ?: 0f)
            layerPaddings[node] = padding
            val layerScale = scale * transformResolutionScale(layoutNode.worldTransform, resolutionScratch)
            val width = ceil((layoutNode.rect.width + padding * 2f) * layerScale).toInt().coerceAtLeast(1)
            val height = ceil((layoutNode.rect.height + padding * 2f) * layerScale).toInt().coerceAtLeast(1)
            layerRequests += UiLayerRequest(width, height)
        }
        framebuffers.beginFrame(
            layerRequests,
            1,
            1,
        )
        return layerRequests.isNotEmpty()
    }

    private fun beginLayer(command: BeginLayerCommand) {
        scissorState = ScissorUnknown
        // Must match prepareFramebuffers, which sized the atlas this layer is allocated from.
        val resolution = transformResolutionScale(command.transform, resolutionScratch)
        val scale = layerScale() * resolution
        val padding = layerPaddings[command.node] ?: layerPadding(command)
        val logicalWidth = command.rect.width + padding * 2f
        val logicalHeight = command.rect.height + padding * 2f
        val width = ceil(logicalWidth * scale).toInt().coerceAtLeast(1)
        val height = ceil(logicalHeight * scale).toInt().coerceAtLeast(1)
        val framebuffer = framebuffers.acquireLayer(width, height)
        val parentClips = clipStack.toList()
        if (!layerProjectionActive) {
            RenderSystem.backupProjectionMatrix()
            RenderSystem.getModelViewStack().pushPose()
            layerProjectionActive = true
        }
        framebuffer.clear()
        framebuffer.bind()
        configureLayerProjection(logicalWidth, logicalHeight)
        uiWriteAlpha(true)
        configureUiBlend()
        RenderSystem.disableDepthTest()
        layerStack.addLast(
            LayerState(
                node = command.node,
                rect = command.rect,
                radius = command.radius,
                clipShape = command.clipShape,
                transform = command.transform,
                framebuffer = framebuffer,
                parentClips = parentClips,
                scale = scale,
                filter = command.filter,
                backfaceVisibility = command.backfaceVisibility,
                padding = padding,
                opacity = command.opacity,
                resolutionScale = resolution,
            )
        )
        clipStack.clear()
    }

    private class RenderSource(
        val texture: Int,
        val width: Int,
        val height: Int,
        val u0: Float, val v0: Float,
        val u1: Float, val v1: Float,
    )

    private class PreparedUiLayer(
        val layer: LayerState,
        val source: RenderSource,
        val ownedSource: UiFramebuffer?,
    )

    private fun finishLayer(deferRootComposite: Boolean = false) {
        scissorState = ScissorUnknown
        val layer = layerStack.removeLast()
        val parentLayer = layerStack.lastOrNull()

        restoreClips(layer.parentClips)

        val copiedSource =
            if (parentLayer != null || layer.filter.blurRadius() > 0f) copyLayerToScratch(layer) else null
        val blurredSource = blurIfNeeded(copiedSource, layer.filter.blurRadius() * layer.resolutionScale)

        val source = resolveRenderSource(layer, copiedSource, blurredSource)
        val compositeFilter = layer.filter.withoutBlur()

        if (deferRootComposite && parentLayer == null) {
            if (blurredSource != null) copiedSource?.let(framebuffers::release)
            preparedLayers[layer.node] = PreparedUiLayer(
                layer = layer,
                source = source,
                ownedSource = blurredSource ?: copiedSource,
            )
            RenderSystem.disableDepthTest()
            GL11.glDepthMask(false)
            return
        }

        val transform = if (parentLayer != null) {
            setupParentLayerContext(parentLayer)
            calculateParentTransform(parentLayer, layer)
        } else {
            setupRootLayerContext()
            calculateRootTransform(layer)
        }

        drawLayerTexture(
            layer,
            source.texture,
            source.width,
            source.height,
            source.u0,
            source.v0,
            source.u1,
            source.v1,
            compositeFilter,
            transform
        )

        if (parentLayer == null) restoreMainProjection()

        blurredSource?.let(framebuffers::release)
        copiedSource?.let(framebuffers::release)
        RenderSystem.disableDepthTest()
    }


    private fun blurIfNeeded(copiedSource: UiFramebuffer?, blurRadius: Float): UiFramebuffer? {
        if (blurRadius <= 0f || copiedSource == null) return null
        return blurTexture(
            framebuffers, copiedSource.texture, copiedSource.width, copiedSource.height, blurRadius, copiedSource
        )
    }

    private fun resolveRenderSource(layer: LayerState, copied: UiFramebuffer?, blurred: UiFramebuffer?): RenderSource {
        val activeBuffer = blurred ?: copied

        return if (activeBuffer != null) {
            RenderSource(
                texture = activeBuffer.texture,
                width = activeBuffer.width,
                height = activeBuffer.height,
                u0 = 0f,
                v0 = 0f,
                u1 = 1f,
                v1 = 1f
            )
        } else {
            RenderSource(
                texture = layer.framebuffer.texture,
                width = layer.framebuffer.atlas.width,
                height = layer.framebuffer.atlas.height,
                u0 = layer.framebuffer.u0(),
                v0 = layer.framebuffer.v0(),
                u1 = layer.framebuffer.u1(),
                v1 = layer.framebuffer.v1()
            )
        }
    }

    private fun setupParentLayerContext(parentLayer: LayerState) {
        parentLayer.framebuffer.bind()
        uiWriteAlpha(true)
        configureLayerProjection(
            parentLayer.rect.width + parentLayer.padding * 2f, parentLayer.rect.height + parentLayer.padding * 2f
        )
        restoreActiveClip()
    }

    private fun setupRootLayerContext() {
        bindRootTarget()
        restoreActiveClip()
    }

    private fun calculateParentTransform(parentLayer: LayerState, layer: LayerState): UiMatrix4 {
        val parentInverse =
            parentLayer.transform.inverse() ?: UiMatrix4.translation(-parentLayer.rect.x, -parentLayer.rect.y, 0f)

        val transform = UiMatrix4.translation(
            parentLayer.padding,
            parentLayer.padding,
            0f
        ) * parentInverse * layer.transform
        return transform.translated(-layer.padding, -layer.padding)
    }

    private fun calculateRootTransform(layer: LayerState): UiMatrix4 {
        return layer.transform.translated(-layer.padding, -layer.padding)
    }

    private fun drawLayerTexture(
        layer: LayerState,
        texture: Int,
        textureWidth: Int,
        textureHeight: Int,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        filter: UiFilterChain,
        transform: UiMatrix4,
    ) {
        val width = layer.rect.width + layer.padding * 2f
        val height = layer.rect.height + layer.padding * 2f
        if (isBackfaceHidden(width, height, transform, layer.backfaceVisibility)) return
        activeProfile?.layerComposites++
        val clipShape = layer.clipShape
        if (clipShape != null) {
            val horizontalPadding = layer.padding / width.coerceAtLeast(0.0001f)
            val verticalPadding = layer.padding / height.coerceAtLeast(0.0001f)
            val clippedTransform = transform.translated(layer.padding, layer.padding)
            UiTextureEffects.drawTexturedShapeRegion(
                texture = texture,
                width = layer.rect.width,
                height = layer.rect.height,
                shape = clipShape,
                transform = clippedTransform,
                opacity = layer.opacity,
                u0 = u0 + (u1 - u0) * horizontalPadding,
                v0 = v0 + (v1 - v0) * verticalPadding,
                u1 = u1 - (u1 - u0) * horizontalPadding,
                v1 = v1 - (v1 - v0) * verticalPadding,
                flipY = true,
                filter = filter,
                textureWidth = textureWidth,
                textureHeight = textureHeight,
            )
        } else {
            UiTextureEffects.drawTexturedRegion(
                texture = texture,
                width = width,
                height = height,
                transform = transform,
                opacity = layer.opacity,
                u0 = u0,
                v0 = v0,
                u1 = u1,
                v1 = v1,
                flipY = true,
                filter = filter,
                textureWidth = textureWidth,
                textureHeight = textureHeight,
                subdivisions = layerTextureSubdivisions(transform),
                maskRadius = layer.radius,
                maskPadding = layer.padding,
                maskScale = layer.scale,
            )
        }
    }

    private fun copyLayerToScratch(layer: LayerState): UiFramebuffer {
        val scratch = framebuffers.acquire(layer.framebuffer.width, layer.framebuffer.height)
        disableScissor()
        scissorState = null
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, layer.framebuffer.framebuffer)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scratch.framebuffer)
        GL30.glBlitFramebuffer(
            layer.framebuffer.region.x,
            layer.framebuffer.region.y,
            layer.framebuffer.region.x + layer.framebuffer.width,
            layer.framebuffer.region.y + layer.framebuffer.height,
            0,
            0,
            scratch.width,
            scratch.height,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST,
        )
        return scratch
    }

    private fun drawBackdropFilter(command: DrawBackdropFilterCommand) {
        scissorState = ScissorUnknown
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        val target = currentTarget()
        if (target.logicalWidth <= 0f || target.logicalHeight <= 0f) return
        val insideLayer = layerStack.isNotEmpty()
        val local = localRect(command.rect)
        val scaleX = target.width / target.logicalWidth
        val scaleY = target.height / target.logicalHeight
        val blurRadius = command.filter.blurRadius()
        val downsampleFactor = if (blurRadius > 0f) gaussianDownsampleFactor(blurRadius) else 1
        val sampleBounds = backdropSampleBounds(
            local,
            target.width,
            target.height,
            scaleX,
            scaleY,
            if (blurRadius > 0f) gaussianSamplePadding(blurRadius) else 0,
        ) ?: return
        val captureWidth = ceil(sampleBounds.width / downsampleFactor.toFloat()).toInt().coerceAtLeast(1)
        val captureHeight = ceil(sampleBounds.height / downsampleFactor.toFloat()).toInt().coerceAtLeast(1)
        val captureScale = ((captureWidth / sampleBounds.width.toFloat()) +
                (captureHeight / sampleBounds.height.toFloat())) * 0.5f
        val restoreProjection = !layerProjectionActive
        if (restoreProjection) {
            RenderSystem.backupProjectionMatrix()
            RenderSystem.getModelViewStack().pushPose()
            layerProjectionActive = true
        }
        val workspace = framebuffers.backdropBlurWorkspace(captureWidth, captureHeight)
        val capture = workspace.capture
        disableScissor()
        scissorState = null
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.framebufferId)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, capture.framebuffer)
        val sourceBottom = target.y + target.height - sampleBounds.y - sampleBounds.height
        val sourceTop = target.y + target.height - sampleBounds.y
        GL30.glBlitFramebuffer(
            target.x + sampleBounds.x,
            sourceBottom,
            target.x + sampleBounds.x + sampleBounds.width,
            sourceTop,
            0,
            0,
            captureWidth,
            captureHeight,
            GL11.GL_COLOR_BUFFER_BIT,
            if (downsampleFactor > 1) GL11.GL_LINEAR else GL11.GL_NEAREST,
        )

        val source = if (blurRadius > 0f) {
            blurBackdropTexture(workspace, captureWidth, captureHeight, blurRadius * captureScale, !insideLayer)
        } else {
            capture
        }
        val compositeFilter = command.filter.withoutBlur()
        bindTarget(target)
        uiWriteAlpha(insideLayer)
        configureLayerProjection(target.logicalWidth, target.logicalHeight)
        restoreActiveClip()
        val sampleBottom = sampleBounds.y + sampleBounds.height
        val textureUScale = captureWidth / source.width.toFloat()
        val textureVScale = captureHeight / source.height.toFloat()
        val u0 = ((local.x * scaleX - sampleBounds.x) / sampleBounds.width).coerceIn(0f, 1f) * textureUScale
        val u1 = (((local.x + local.width) * scaleX - sampleBounds.x) / sampleBounds.width)
            .coerceIn(0f, 1f) * textureUScale
        val v0 = ((sampleBottom - local.y * scaleY) / sampleBounds.height).coerceIn(0f, 1f) * textureVScale
        val v1 = ((sampleBottom - (local.y + local.height) * scaleY) / sampleBounds.height)
            .coerceIn(0f, 1f) * textureVScale
        UiTextureEffects.drawTexturedRegion(
            texture = source.texture,
            width = command.rect.width,
            height = command.rect.height,
            transform = transform,
            opacity = command.opacity,
            u0 = u0,
            v0 = v0,
            u1 = u1,
            v1 = v1,
            flipY = false,
            filter = compositeFilter,
            textureWidth = source.width,
            textureHeight = source.height,
            subdivisions = 1,
            maskRadius = command.radius,
            maskScale = target.scale * captureScale,
            opaqueSource = !insideLayer,
        )
        if (restoreProjection) restoreMainProjection()
    }

    private fun drawShadow(command: DrawShadowCommand) {
        if (command.image != null) {
            appendImageShadows(command)
            flushPhaseImageBatches()
            return
        }
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        command.shadows.forEach { shadow ->
            if (shadow.inset) return@forEach
            drawProjectedShadow(
                command.rect.width,
                command.rect.height,
                command.radius,
                shadow,
                command.opacity,
                transform,
                command.filter,
            )
        }
    }

    private fun appendShapeShadows(command: DrawShadowCommand): Boolean {
        if (command.image != null) return appendImageShadows(command)
        val shape = command.shape ?: return appendAnalyticRectShadows(command)
        if (!pathTileRenderer.isAvailable) return false
        command.shadows.forEach { shadow ->
            val shadowTransform = UiMatrix4.translation(
                shadow.offset.x,
                shadow.offset.y,
                shadow.offset.z,
            ) * command.transform
            val shapeCommand = DrawShapeCommand(
                node = command.node,
                rect = command.rect,
                shape = shape,
                fill = UiResolvedPaint.Color(shadow.color),
                stroke = UiResolvedPaint.None,
                strokeWidth = 0f,
                opacity = command.opacity,
                transform = shadowTransform,
                filter = command.filter,
                backfaceVisibility = command.backfaceVisibility,
                phase = UiRenderPhase.BACKGROUND,
                blurRadius = shadow.blur.coerceAtLeast(0f),
                spreadRadius = shadow.spread,
            )
            check(appendPathTile(shapeCommand)) { "Shape shadow unexpectedly rejected by the path tile batch" }
        }
        return true
    }

    private fun appendImageShadows(command: DrawShadowCommand): Boolean {
        val image = command.image ?: return false
        if (ModShaders.UI_IMAGE_SHADOW == null || ModShaders.UI_EFFECT == null) return true
        if (command.opacity <= 0f || image.tintAlpha <= 0f || image.rect.width <= 0f || image.rect.height <= 0f) return true
        val svg = svgRasterQuad(image.rect.width, image.rect.height, image.source, 1f, UiMatrix4.identity(), image.fit)
        val location = svg?.texture ?: ResourceLocation.tryParse(image.source) ?: return true
        if (svg == null && svgLocation(image.source) != null) return true
        val sourceTexture = Minecraft.getInstance().textureManager.getTexture(location)
        val placement = if (svg != null) {
            ImagePlacement(svg.transform.transformX(0f), svg.transform.transformY(0f), svg.width, svg.height)
        } else imagePlacement(image.rect.width, image.rect.height, image.fit, location, image.uv)
        val sourceBounds = UiRect(image.rect.x + placement.x, image.rect.y + placement.y, placement.width, placement.height)
        val bounds = image.clipRect?.let(sourceBounds::intersect) ?: sourceBounds
        if (bounds.width <= 0f || bounds.height <= 0f) return true
        val cacheImage = image.copy(tintAlpha = 1f)
        for (shadow in command.shadows) {
            if (shadow.color.alpha <= 0f) continue
            val transform = effective(UiMatrix4.translation(shadow.offset.x, shadow.offset.y, shadow.offset.z) * command.transform)
            if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) continue
            val shadowBounds = imageShadowBounds(bounds, shadow.blur, shadow.spread)
            val shadowTransform = transform.translated(shadowBounds.x, shadowBounds.y)
            computeQuadBounds(shadowBounds.width, shadowBounds.height, shadowTransform)
            if (quadFullyClipped()) continue
            flushBatchesOverlappingQuad(UiBatchKind.IMAGE)
            val mask = imageShadows.get(
                UiImageShadowKey(cacheImage, sourceTexture.id, HollowUiResourceAccess.version(location), bounds,
                    command.rect.width, command.rect.height, layerScale(), shadow.blur.coerceAtLeast(0f), shadow.spread),
                cacheAcrossFrames = svg != null || sourceTexture.javaClass == SimpleTexture::class.java,
            ) { capture ->
                RenderSystem.setShaderTexture(0, location)
                UiTextureEffects.drawTexturedQuad(
                    svg?.width ?: image.rect.width, svg?.height ?: image.rect.height,
                    capture.translated(image.rect.x, image.rect.y) * (svg?.transform ?: UiMatrix4.identity()),
                    1f, false, fit = if (svg != null) image.fit.svgRasterDrawFit() else image.fit,
                    texture = location, slice = image.slice, alphaMask = true,
                    uv = if (svg != null) UiImageUv.Full else image.uv,
                )
            }
            val overlapsOtherTextures = phaseImageBatches.keys.any { it != mask.texture } &&
                    imageBatchBounds.overlaps(quadMinX, quadMinY, quadMaxX, quadMaxY)
            if (phaseImageBatches.isNotEmpty() && (imageBatchClip != phaseClip || overlapsOtherTextures)) flushPhaseImageBatches()
            phaseImageBatches.getOrPut(mask.texture) { ArrayList() } += UiTexturedQuad(
                mask.bounds.width, mask.bounds.height, shadowTransform, command.opacity * image.tintAlpha,
                flipY = true, tint = shadow.color.filtered(command.filter), alphaMask = true,
            )
            imageBatchClip = phaseClip
            imageBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        }
        return true
    }

    private fun releaseSvgRasters() {
        svgRasterTextures.values.forEach { Minecraft.getInstance().textureManager.release(it.location) }
        svgRasterTextures.clear()
        brokenSvgSources.clear()
    }

    private fun appendAnalyticRectShadows(command: DrawShadowCommand): Boolean {
        if (!analyticRectRenderer.isAvailable) return false
        for (shadow in command.shadows) {
            val transform = if (shadow.inset) {
                effective(command.transform)
            } else {
                effective(UiMatrix4.translation(shadow.offset.x, shadow.offset.y, shadow.offset.z) * command.transform)
            }
            if (isBackfaceHidden(
                    command.rect.width,
                    command.rect.height,
                    transform,
                    command.backfaceVisibility,
                )
            ) continue
            val padding = if (shadow.inset) 0f else abs(shadow.spread) + shadow.blur.coerceAtLeast(0f) * 3f + 1f
            computeQuadBounds(command.rect.width, command.rect.height, transform, padding)
            flushBatchesOverlappingQuad(UiBatchKind.ANALYTIC_RECT)
            analyticRectBatch.appendShadow(command, shadow, transform, phaseShaderClip())
            analyticBatchBounds.add(quadMinX, quadMinY, quadMaxX, quadMaxY)
        }
        return true
    }

    private fun drawBox(command: DrawBoxCommand) {
        RenderSystem.enableBlend()

        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        when (val paint = command.paint) {
            UiResolvedPaint.None -> Unit
            is UiResolvedPaint.Color -> drawLocalPaint(
                command.rect.width,
                command.rect.height,
                command.border.radius,
                paint.color.withOpacity(command.opacity),
                transform,
                command.filter
            )

            is UiResolvedPaint.LinearGradient -> drawLocalGradient(
                command.rect.width,
                command.rect.height,
                command.border.radius,
                paint.angleDegrees,
                paint.stops,
                command.opacity,
                transform,
                command.filter,
            )

            is UiResolvedPaint.RadialGradient -> drawLocalRadialGradient(
                command.rect.width,
                command.rect.height,
                command.border.radius,
                paint.gradient,
                command.opacity,
                transform,
                command.filter,
            )

            is UiResolvedPaint.Image -> drawImage(
                command.rect.width,
                command.rect.height,
                paint.source,
                command.opacity,
                transform,
                fit = command.fit,
                slice = command.slice,
                filter = command.filter,
                tint = command.tint,
                uv = command.uv,
            )

            is UiResolvedPaint.Shader -> drawLocalPaint(
                command.rect.width,
                command.rect.height,
                command.border.radius,
                UiColor(0.2f, 0.2f, 0.24f, command.opacity),
                transform,
                command.filter,
            )
        }
        val borderWidth = command.border.width.left.resolve(command.rect.width)
        val borderColorAt = command.border.paint.resolve()
            .localColorAt(command.rect.width, command.rect.height, command.opacity, command.filter)
        if (borderWidth > 0f && borderColorAt != null) {
            drawLocalBorder(
                command.rect.width,
                command.rect.height,
                command.border.radius,
                borderWidth.coerceAtLeast(1f),
                borderColorAt,
                transform,
            )
        }
    }

    private fun drawRawTexture(command: DrawRawTextureCommand) {
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return
        val texture = command.texture?.invoke() ?: command.textureId
        if (texture == 0) return
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        UiTextureEffects.drawTexture(
            texture = texture,
            width = command.rect.width,
            height = command.rect.height,
            transform = transform,
            opacity = command.opacity,
            flipY = command.flipY,
            filter = command.filter,
            maskRadius = command.radius,
            opaqueSource = command.opaque,
        )
    }

    private fun drawShape(command: DrawShapeCommand) {
        if (appendBatchedShape(command)) {
            flushShapeBatch()
            return
        }
        val transform = effective(command.transform)
        drawLocalPaint(
            command.rect.width,
            command.rect.height,
            0f,
            UiColor(0.2f, 0.2f, 0.24f, command.opacity),
            transform,
            command.filter,
        )
    }

    private fun drawText(command: DrawTextCommand) {
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        val now = TickHandler.gameTime / 20f
        val clipped = requiresTextClip(
            overflow = command.overflow,
            contentWidth = command.layout.maxNaturalLineWidth,
            contentHeight = command.layout.height,
            viewportWidth = command.rect.width,
            viewportHeight = command.rect.height,
            scrollX = command.scrollOffset.x,
            scrollY = command.scrollOffset.y,
        )
        val useAnalytic = analyticRectRenderer.isAvailable
        emitUnifiedGlyphs = useAnalytic
        glyphClip = when {
            !useAnalytic -> UiShaderClip.None
            clipped -> phaseShaderClip().intersect(localViewportClip(command.rect, transform))
            else -> phaseShaderClip()
        }
        if (clipped) {
            flushPhaseImageBatches()
            flushGeometryBatches()
            flushTextBatch()
            pushClip(UiRect(0f, 0f, command.rect.width, command.rect.height), command.transform)
            textBatchClip = clipStack.lastOrNull()
        }
        val lineOverscan = if (clipped) 0f else DefaultLineOverscan
        command.layout.visibleLineItems(command.scrollOffset.y, command.rect.height, lineOverscan)
            .forEach { (_, line) ->
                val displayLine = if (command.overflow == UiTextOverflow.DOTS) UiTextOverflowResolver.ellipsizeLine(
                    command, line
                ) else line
                drawTextLine(command, displayLine, transform, now)
            }
        if (clipped) {
            flushTextBatch()
            popClip()
            textBatchClip = phaseClip
        }
    }

    private fun drawTextLine(
        command: DrawTextCommand,
        line: UiTextLine,
        transform: UiMatrix4,
        now: Float,
    ) {
        if (line.fragments.isNotEmpty()) {
            line.fragments.forEach { fragment ->
                when (fragment) {
                    is UiInlineImageRun -> drawInlineImage(command, fragment, line, transform)
                    is UiInlineWidgetRun -> Unit
                    is UiTextSpaceRun -> Unit
                    is UiTextRun -> drawTextRun(command, fragment, line, transform, now)
                }
            }
        } else {
            val fragment = UiTextRun(
                text = line.text,
                style = UiInlineStyle.Empty,
                x = line.x,
                y = 0f,
                width = line.naturalWidth,
                height = command.fontSize,
            )
            drawTextRun(command, fragment, line, transform, now)
        }
    }

    private fun drawTextRun(
        command: DrawTextCommand,
        fragment: UiTextRun,
        line: UiTextLine,
        transform: UiMatrix4,
        now: Float,
    ) {
        val fontSize = fragment.style.resolvedFontSize(command.fontSize)
        val inlineEffects = fragment.style.effects
        val commandEffects = command.textEffects
        val fontFamily = fragment.style.fontFamily ?: command.fontFamily
        val localX = line.x + fragment.x - command.scrollOffset.x
        val localY = line.y + fragment.y - command.scrollOffset.y

        drawTextBackground(command, fragment, transform, localX, localY)

        val hasLayer = inlineEffects.hasLayerEffects() || commandEffects.hasLayerEffects()
        val hasAnimated = inlineEffects.hasAnimatedEffects() || commandEffects.hasAnimatedEffects()

        if (!hasLayer && !hasAnimated) {
            drawSingleTextRun(
                command, fragment, transform,
                localX,
                localY,
                fontSize,
                fontFamily,
                fragment.style.color,
                1f,
            )
            return
        }

        val layerEffects = collectTextEffects(inlineEffects, commandEffects, animated = false)
        val animatedEffects = if (hasAnimated) {
            collectTextEffects(inlineEffects, commandEffects, animated = true)
        } else {
            emptyList()
        }

        if (hasAnimated) {
            drawAnimatedTextRun(
                command,
                fragment,
                transform,
                now,
                fontSize,
                fontFamily,
                localX,
                localY,
                animatedEffects,
                layerEffects,
            )
            return
        }

        val effectiveColor = fragment.style.color ?: if (fragment.style.link != null) {
            LinkColor
        } else {
            command.color
        }

        for (layerEffect in layerEffects) {
            val passes = UiTextEffectApplier.getLayerPasses(layerEffect)
            for (pass in passes) {
                val passColor = pass.colorOverride?.textShadowColor(layerEffect, effectiveColor) ?: effectiveColor
                drawSingleTextRun(
                    command,
                    fragment,
                    transform,
                    localX + pass.offsetX,
                    localY + pass.offsetY,
                    fontSize,
                    fontFamily,
                    passColor,
                    pass.alphaMultiplier,
                )
            }
        }

        drawSingleTextRun(
            command, fragment, transform,
            localX, localY, fontSize,
            fontFamily,
            fragment.style.color, 1f,
        )
    }

    private fun drawTextBackground(
        command: DrawTextCommand,
        fragment: UiTextRun,
        transform: UiMatrix4,
        localX: Float,
        localY: Float,
    ) {
        val background = fragment.style.background ?: return
        if (fragment.width <= 0f || fragment.height <= 0f || background.alpha <= 0f) return
        drawTextRect(
            command,
            transform.translated(localX, localY),
            fragment.width,
            fragment.height,
            TextRectRadius,
            background.withOpacity(command.opacity).filtered(command.filter),
        )
    }

    /**
     * A rectangle drawn as part of a text run, a span background, a rule.
     */
    private fun drawTextRect(
        command: DrawTextCommand,
        placement: UiMatrix4,
        width: Float,
        height: Float,
        radius: Float,
        color: UiColor,
    ) {
        if (width <= 0f || height <= 0f || color.alpha <= 0f) return
        if (emitUnifiedGlyphs) {
            analyticRectBatch.appendSolidRect(placement, width, height, radius, color, glyphClip)
            return
        }
        flushTextBatch()
        drawLocalPaint(width, height, radius, color, placement, command.filter)
    }

    private fun drawSingleTextRun(
        command: DrawTextCommand,
        fragment: UiTextRun,
        transform: UiMatrix4,
        localX: Float,
        localY: Float,
        fontSize: Float,
        fontFamily: String?,
        colorOverride: UiColor?,
        alphaMultiplier: Float,
    ) {
        if (fragment.style.code) {
            drawTextRect(
                command,
                transform.translated(localX, localY),
                fragment.width,
                fragment.height,
                TextRectRadius,
                UiColor(0f, 0f, 0f, 0.28f * command.opacity * alphaMultiplier),
            )
        }

        val font = UiGlyphFonts.resolve(fontFamily) ?: return
        val baselineY = localY + font.ascender * fontSize
        val boldWeight = fragment.style.effects.boldWeightOrZero()
            .takeIf { it != 0f } ?: command.textEffects.boldWeightOrZero()
        val skewDegrees = fragment.style.effects.italicSkewOrZero()
            .takeIf { it != 0f } ?: command.textEffects.italicSkewOrZero()
        val glyphTransform = if (skewDegrees == 0f) transform else transform * baselineShear(baselineY, skewDegrees)

        val baseColor = colorOverride ?: fragment.style.color ?: if (fragment.style.link != null) {
            LinkColor
        } else {
            command.color
        }
        val color = baseColor.withOpacity(command.opacity * alphaMultiplier).filtered(command.filter)

        drawGlyphRun(fragment, font, glyphTransform, localX, baselineY, fontSize, color, boldWeight)
        drawTextRules(command, fragment, font, glyphTransform, localX, baselineY, fontSize, color, alphaMultiplier)
    }

    /**
     * Lays a run's glyphs down along the baseline. Every font kind, pre-baked MSDF asset, atlas
     * baked from a `.ttf`, or a vanilla bitmap sheet, reduces to the same quad-per-glyph loop.
     */
    private fun drawGlyphRun(
        fragment: UiTextRun,
        font: UiGlyphFont,
        transform: UiMatrix4,
        localX: Float,
        baselineY: Float,
        fontSize: Float,
        color: UiColor,
        boldWeight: Float,
    ) {
        val boldAdvance = boldWeight * fontSize
        var penX = localX
        fragment.text.forEachCodepoint { codepoint ->
            val glyph = font.glyph(codepoint)
            if (glyph == null || glyph.isBlank) {
                penX += font.advance(codepoint) * fontSize + boldAdvance
                return@forEachCodepoint
            }
            val page = glyph.page
            if (emitUnifiedGlyphs && !analyticRectBatch.acceptsGlyphPage(page)) {
                flushAnalyticRectBatch()
            }
            val x0 = penX + glyph.left * fontSize
            val x1 = penX + glyph.right * fontSize
            val yTop = baselineY + glyph.top * fontSize
            val yBottom = baselineY + glyph.bottom * fontSize
            val bias = page.boldBias(font.emPixels, boldWeight)
            appendGlyphQuad(page, transform, x0, yTop, x1, yBottom, glyph, color, bias)
            val residual = (boldWeight - page.boldBiasWeight(font.emPixels, bias)) * fontSize
            if (residual > MinBoldStampOffset) {
                appendGlyphQuad(page, transform, x0 + residual, yTop, x1 + residual, yBottom, glyph, color, bias)
            }
            penX += glyph.advance * fontSize + boldAdvance
        }
    }

    private fun appendGlyphQuad(
        page: UiGlyphAtlasPage,
        transform: UiMatrix4,
        x0: Float,
        yTop: Float,
        x1: Float,
        yBottom: Float,
        glyph: UiPlacedGlyph,
        color: UiColor,
        sdBias: Float,
    ) {
        if (emitUnifiedGlyphs) {
            analyticRectBatch.appendGlyph(
                transform,
                x0, yTop, x1, yBottom,
                glyph.uMin, glyph.vTop, glyph.uMax, glyph.vBottom,
                color, glyphClip, page, sdBias,
            )
            return
        }
        msdfTextBatch.appendQuad(
            page,
            sdBias,
            transform.transform(x1, yBottom),
            transform.transform(x1, yTop),
            transform.transform(x0, yTop),
            transform.transform(x0, yBottom),
            glyph.uMin, glyph.vBottom, glyph.uMax, glyph.vTop,
            color,
        )
    }

    /** Underline and strikethrough rules, positioned from the font's own metrics. */
    private fun drawTextRules(
        command: DrawTextCommand,
        fragment: UiTextRun,
        font: UiGlyphFont,
        transform: UiMatrix4,
        localX: Float,
        baselineY: Float,
        fontSize: Float,
        color: UiColor,
        alphaMultiplier: Float,
    ) {
        val underline = fragment.style.effects.underlineRule() ?: command.textEffects.underlineRule()
        val strikethrough = fragment.style.effects.strikethroughRule()
            ?: command.textEffects.strikethroughRule()
        if (underline == null && strikethrough == null) return
        underline?.let {
            drawTextRule(
                command, transform, localX, fragment.width,
                centreY = baselineY - (font.underlineY - it.offset) * fontSize,
                thickness = (it.thickness.takeIf { value -> value > 0f } ?: font.underlineThickness) * fontSize,
                color = it.color?.withOpacity(command.opacity * alphaMultiplier)?.filtered(command.filter) ?: color,
            )
        }
        strikethrough?.let {
            drawTextRule(
                command, transform, localX, fragment.width,
                centreY = baselineY - (font.strikethroughY - it.offset) * fontSize,
                thickness = (it.thickness.takeIf { value -> value > 0f } ?: font.strikethroughThickness) * fontSize,
                color = it.color?.withOpacity(command.opacity * alphaMultiplier)?.filtered(command.filter) ?: color,
            )
        }
    }

    private fun drawTextRule(
        command: DrawTextCommand,
        transform: UiMatrix4,
        localX: Float,
        width: Float,
        centreY: Float,
        thickness: Float,
        color: UiColor,
    ) {
        val height = thickness.coerceAtLeast(MinTextRuleThickness)
        drawTextRect(command, transform.translated(localX, centreY - height / 2f), width, height, 0f, color)
    }

    /** Shear about `y = baselineY`: `x' = x + tan(skew) * (baselineY - y)`, leaving y untouched. */
    private fun baselineShear(baselineY: Float, skewDegrees: Float): UiMatrix4 {
        val tangent = tan(skewDegrees * PI.toFloat() / 180f)
        return UiMatrix4(
            floatArrayOf(
                1f, -tangent, 0f, tangent * baselineY,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
        )
    }

    private fun drawAnimatedTextRun(
        command: DrawTextCommand,
        fragment: UiTextRun,
        transform: UiMatrix4,
        now: Float,
        fontSize: Float,
        fontFamily: String?,
        baseLocalX: Float,
        baseLocalY: Float,
        animatedEffects: List<UiTextEffect>,
        layerEffects: List<UiTextEffect>,
    ) {
        val totalChars = fragment.text.length
        var charLocalX = baseLocalX

        for (charIndex in fragment.text.indices) {
            val char = fragment.text[charIndex]
            val charWidth = UiTextLayouter.measureStyledTextWidth(
                char.toString(), fontSize, fontFamily, fragment.style,
            )
            val charPos = charIndex.toFloat() / totalChars.coerceAtLeast(1).toFloat()
            val charFragment = fragment.copy(text = char.toString(), x = charLocalX, width = charWidth)

            val ctx = UiEffectContext(
                time = now,
                charIndex = charIndex,
                totalChars = totalChars,
                charPos = charPos,
                runWidth = fragment.width,
                runHeight = fragment.height,
                random = 0f,
            )

            var charOffsetX = 0f
            var charOffsetY = 0f
            var colorOverride: UiColor? = fragment.style.color
            var alphaMul = 1f

            for (effect in animatedEffects) {
                val result = UiTextEffectApplier.apply(effect, ctx)
                charOffsetX += result.offsetX
                charOffsetY += result.offsetY
                if (result.colorOverride != null) colorOverride = result.colorOverride
                alphaMul *= result.alphaMultiplier
            }

            if (layerEffects.isNotEmpty()) {
                val effectiveColor = colorOverride ?: if (fragment.style.link != null) {
                    LinkColor
                } else {
                    command.color
                }
                for (layerEffect in layerEffects) {
                    val passes = UiTextEffectApplier.getLayerPasses(layerEffect)
                    for (pass in passes) {
                        val passColor =
                            pass.colorOverride?.textShadowColor(layerEffect, effectiveColor) ?: effectiveColor
                        drawSingleTextRun(
                            command,
                            charFragment,
                            transform,
                            charLocalX + charOffsetX + pass.offsetX,
                            baseLocalY + charOffsetY + pass.offsetY,
                            fontSize,
                            fontFamily,
                            passColor,
                            alphaMul * pass.alphaMultiplier,
                        )
                    }
                }
            }

            val finalLocalX = charLocalX + charOffsetX
            val finalLocalY = baseLocalY + charOffsetY

            val finalColor = colorOverride ?: if (fragment.style.link != null) {
                LinkColor
            } else {
                command.color
            }
            drawSingleTextRun(
                command,
                charFragment,
                transform,
                finalLocalX,
                finalLocalY,
                fontSize,
                fontFamily,
                finalColor,
                alphaMul,
            )
            charLocalX += charWidth
        }
    }

    private fun drawInlineImage(
        command: DrawTextCommand,
        fragment: UiInlineImageRun,
        line: UiTextLine,
        transform: UiMatrix4,
    ) {
        flushTextBatch()
        drawImage(
            fragment.width,
            fragment.height,
            fragment.image.source,
            command.opacity,
            transform.translated(
                line.x + fragment.x - command.scrollOffset.x,
                line.y + fragment.y - command.scrollOffset.y,
            ),
            UiImageFit.CONTAIN,
            command.filter,
        )
    }

    private fun drawImage(command: DrawImageCommand) {
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        drawImage(
            command.rect.width,
            command.rect.height,
            command.source,
            command.opacity,
            transform,
            command.fit,
            command.filter,
            command.slice,
            command.tint,
            command.uv,
        )
    }

    private fun drawImage(
        width: Float,
        height: Float,
        source: String,
        opacity: Float,
        transform: UiMatrix4,
        fit: UiImageFit = UiImageFit.STRETCH,
        filter: UiFilterChain = UiFilterChain.Empty,
        slice: UiInsets = UiInsets.Zero,
        tint: UiColor = UiColor.White,
        uv: UiImageUv = UiImageUv.Full,
    ) {
        val svgQuad = svgRasterQuad(width, height, source, opacity, transform, fit)
        if (svgQuad != null) {
            RenderSystem.setShaderTexture(0, svgQuad.texture)
            UiTextureEffects.drawTexturedQuad(
                svgQuad.width,
                svgQuad.height,
                svgQuad.transform,
                opacity,
                flipY = false,
                fit = fit.svgRasterDrawFit(),
                texture = svgQuad.texture,
                filter = filter,
                slice = slice,
                tint = tint,
            )
            return
        }
        if (svgLocation(source) != null) return
        val location = ResourceLocation.tryParse(source) ?: return
        RenderSystem.setShaderTexture(0, location)
        UiTextureEffects.drawTexturedQuad(
            width,
            height,
            transform,
            opacity,
            flipY = false,
            fit = fit,
            texture = location,
            filter = filter,
            slice = slice,
            tint = tint,
            uv = uv,
        )
    }

    private fun svgLocation(source: String): ResourceLocation? {
        val location = ResourceLocation.tryParse(source) ?: return null
        return location.takeIf { it.path.endsWith(".svg", ignoreCase = true) }
    }

    private fun UiImageFit.svgRasterDrawFit(): UiImageFit {
        return when (this) {
            UiImageFit.NINE_SLICE,
            UiImageFit.THREE_SLICE_VERTICAL,
            UiImageFit.THREE_SLICE_HORIZONTAL,
                -> this

            UiImageFit.STRETCH,
            UiImageFit.NONE,
            UiImageFit.CONTAIN,
            UiImageFit.COVER,
                -> UiImageFit.STRETCH
        }
    }

    private fun UiColor.textShadowColor(effect: UiTextEffect, textColor: UiColor): UiColor {
        if (effect !is Shadow) return this
        return UiColor(
            red = red * textColor.red,
            green = green * textColor.green,
            blue = blue * textColor.blue,
            alpha = alpha * textColor.alpha,
        )
    }

    private fun drawItem(command: DrawItemCommand) {
        val transform = effective(command.transform)
        if (isBackfaceHidden(
                command.rect.width, command.rect.height, transform, command.backfaceVisibility
            )
        ) return
        val stack = command.item.stack
        if (stack.isEmpty) return

        val origin = transform.transform(0f, 0f)
        transform.axisScales(itemAxisScales)
        val width = command.rect.width * itemAxisScales[0]
        val height = command.rect.height * itemAxisScales[1]
        if (width <= 0f || height <= 0f) return

        val rect = UiRect(origin.x, origin.y, width, height)
        val depth = nextItemDepth(rect)
        stack.render(rect.x, rect.y, rect.width, rect.height, stack = PoseStack().apply { translate(0f, 0f, depth) })
        drawItemDecorations(stack, rect, depth)
    }

    /**
     * Reserves this item's depth slice and advances the cursor past it.
     *
     * A slice has to be at least as deep as the model is, and a GUI item model is as deep as it is wide.
     * The budget is the projection's near plane; past it items would be clipped away entirely, so the
     * offset stops growing instead and only very crowded screens can see items share a slice again.
     */
    private fun nextItemDepth(rect: UiRect): Float {
        val depth = itemDepthOffset
        val slice = max(rect.width, rect.height).coerceAtLeast(1f)
        itemDepthOffset = min(itemDepthOffset + slice, MaxItemDepth)
        return depth
    }

    /**
     * Draws the count, durability bar and cooldown overlay through vanilla, so an item in our UI carries
     * exactly the badges it does in a vanilla slot, mods that add their own included.
     *
     * Vanilla assumes a 16x16 slot, hence the scale from that onto the item's actual rect.
     */
    private fun drawItemDecorations(stack: ItemStack, rect: UiRect, depth: Float) {
        if (rect.width <= 0f || rect.height <= 0f) return
        val minecraft = Minecraft.getInstance()
        val graphics = GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())
        val pose = graphics.pose()
        pose.pushPose()
        try {
            pose.translate(rect.x, rect.y, depth)
            pose.scale(rect.width / VanillaSlotSize, rect.height / VanillaSlotSize, 1f)
            graphics.renderItemDecorations(minecraft.font, stack, 0, 0)
            graphics.flush()
        } finally {
            pose.popPose()
            RenderSystem.enableBlend()
            configureUiBlend()
        }
    }

    private fun drawEntity(command: DrawEntityCommand) {
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        val entity = command.entity.resolve()
        if (entity == null) {
            drawEntityPlaceholder(command)
            return
        }

        val rect = UiRect(0f, 0f, command.rect.width, command.rect.height)
        clearDepthOf(transformedLocalRect(rect, transform))
        POSE_STACK.pushPose()
        POSE_STACK.mulPose(transform.toMatrix4f())
        val drawn = try {
            renderEntity(entity, rect, command.entity.view)
        } finally {
            POSE_STACK.popPose()
            clearDepthOf(transformedLocalRect(rect, transform))
        }
        if (!drawn) drawEntityPlaceholder(command)
    }

    private fun drawEntityPlaceholder(command: DrawEntityCommand) {
        val transform = effective(command.transform)
        drawLocalPaint(
            command.rect.width,
            command.rect.height,
            0f,
            UiColor(0.09f, 0.12f, 0.16f, command.opacity),
            transform,
            command.filter
        )
        drawLocalBorder(
            command.rect.width,
            command.rect.height,
            0f,
            UiColor(0.28f, 0.42f, 0.58f, command.opacity).filtered(command.filter),
            transform
        )
    }

    private fun drawCanvasGl(command: DrawCanvasGlCommand) {
        if (command.rect.width <= 0f || command.rect.height <= 0f || command.opacity <= 0f) return
        val transform = effective(command.transform)
        if (isBackfaceHidden(command.rect.width, command.rect.height, transform, command.backfaceVisibility)) return
        val rect = UiRect(0f, 0f, command.rect.width, command.rect.height)
        val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        POSE_STACK.pushPose()
        POSE_STACK.mulPose(transform.toMatrix4f())
        RenderSystem.enableDepthTest()
        GL11.glDepthMask(true)
        clearDepthOf(transformedLocalRect(rect, transform))
        try {
            command.block(UiGlDrawScopeImpl(rect, command.opacity, POSE_STACK))
        } catch (e: Throwable) {
            HollowEngine.LOGGER.error("Error in Modifier.drawGl block", e)
        } finally {
            Lighting.setupFor3DItems()
            POSE_STACK.popPose()
            clearDepthOf(transformedLocalRect(rect, transform))
            if (depthEnabled) RenderSystem.enableDepthTest() else RenderSystem.disableDepthTest()
            GL11.glDepthMask(depthMask)
            RenderSystem.enableBlend()
            configureUiBlend()
        }
    }

    /** Returns false when nothing can draw this entity, so the caller shows the placeholder. */
    private fun renderEntity(entity: Entity, rect: UiRect, view: UiEntityView): Boolean {
        val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        POSE_STACK.pushPose()
        try {
            val xOffset = rect.x + rect.width / 2f + view.offsetX
            val yOffset = rect.y + rect.height + view.offsetY
            POSE_STACK.translate(xOffset.toDouble(), yOffset.toDouble(), 0.0)
            val fit = min(rect.width / entity.bbWidth, rect.height / entity.bbHeight) * 0.92f
            val scale = fit * view.zoom.coerceAtLeast(0.01f)
            POSE_STACK.scale(scale, scale, -scale)
            POSE_STACK.mulPose(Axis.ZP.rotationDegrees(-180f))
            if (view.pitch != 0f) POSE_STACK.mulPose(Axis.XP.rotationDegrees(view.pitch))

            Lighting.setupForEntityInInventory()

            val mc = Minecraft.getInstance()
            val dispatcher = mc.entityRenderDispatcher
            val buffers = mc.renderBuffers().bufferSource()
            dispatcher.setRenderShadow(false)
            RenderSystem.enableDepthTest()
            GL11.glDepthMask(true)
            try {
                facingTheViewer(entity, view.yaw) {
                    RenderSystem.runAsFancy {
                        dispatcher.render(entity, 0.0, 0.0, 0.0, 0f, 1f, POSE_STACK, buffers, LightTexture.FULL_BRIGHT)
                    }
                }
                buffers.endBatch()
                return true
            } finally {
                dispatcher.setRenderShadow(true)
            }
        } finally {
            Lighting.setupFor3DItems()
            POSE_STACK.popPose()
            if (!depthEnabled) RenderSystem.disableDepthTest()
            GL11.glDepthMask(depthMask)
        }
    }
    
    private inline fun facingTheViewer(entity: Entity, yaw: Float, block: () -> Unit) {
        val yRot = entity.yRot
        val yRotO = entity.yRotO
        val xRot = entity.xRot
        val xRotO = entity.xRotO
        val living = entity as? LivingEntity
        val bodyRot = living?.yBodyRot
        val bodyRotO = living?.yBodyRotO
        val headRot = living?.yHeadRot
        val headRotO = living?.yHeadRotO
        val nameVisible = entity.isCustomNameVisible
        val oldName = entity.customName
        try {
            val portraitYaw = PORTRAIT_YAW + yaw
            entity.yRot = portraitYaw
            entity.yRotO = portraitYaw
            entity.xRot = 0f
            entity.xRotO = 0f
            living?.let {
                it.yBodyRot = portraitYaw
                it.yBodyRotO = portraitYaw
                it.yHeadRot = portraitYaw
                it.yHeadRotO = portraitYaw
            }
            entity.isCustomNameVisible = false
            entity.customName = null
            block()
        } finally {
            entity.yRot = yRot
            entity.yRotO = yRotO
            entity.xRot = xRot
            entity.xRotO = xRotO
            living?.let {
                it.yBodyRot = bodyRot ?: it.yBodyRot
                it.yBodyRotO = bodyRotO ?: it.yBodyRotO
                it.yHeadRot = headRot ?: it.yHeadRot
                it.yHeadRotO = headRotO ?: it.yHeadRotO
            }
            entity.isCustomNameVisible = nameVisible
            entity.customName = oldName
        }
    }

    private fun pushClip(rect: UiRect, transform: UiMatrix4) {
        val local = transformedLocalRect(rect, effective(transform))
        clipStack.addLast(clipStack.lastOrNull()?.intersect(local) ?: local)
        applyScissor(clipStack.last())
    }

    private fun popClip() {
        if (clipStack.isNotEmpty()) clipStack.removeLast()
        val top = clipStack.lastOrNull()
        if (top != null) {
            applyScissor(top)
        } else {
            disableScissor()
            scissorState = null
        }
    }

    private fun applyScissor(rect: UiRect) {
        scissorState = rect
        val layer = layerStack.lastOrNull()
        if (layer != null) {
            val bounds = rect.toScissorBounds(layer.scale, layer.scale)
            GL11.glEnable(GL11.GL_SCISSOR_TEST)
            GL11.glScissor(
                layer.framebuffer.region.x + bounds.x,
                layer.framebuffer.region.y + layer.framebuffer.height - bounds.y - bounds.height,
                bounds.width,
                bounds.height,
            )
            return
        }
        val target = renderTarget
        if (target != null) {
            val scaleX = target.width / target.logicalWidth.coerceAtLeast(1f)
            val scaleY = target.height / target.logicalHeight.coerceAtLeast(1f)
            val bounds = rect.toScissorBounds(scaleX, scaleY)
            GL11.glEnable(GL11.GL_SCISSOR_TEST)
            GL11.glScissor(
                target.x + bounds.x,
                target.y + target.height - bounds.y - bounds.height,
                bounds.width,
                bounds.height,
            )
            return
        }
        val window = Minecraft.getInstance().window
        val scaleX = window.width / window.guiScaledWidth.toFloat()
        val scaleY = window.height / window.guiScaledHeight.toFloat()
        val bounds = rect.toScissorBounds(scaleX, scaleY)
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(
            bounds.x,
            window.height - bounds.y - bounds.height,
            bounds.width,
            bounds.height,
        )
    }

    private fun UiRect.toScissorBounds(scaleX: Float, scaleY: Float): ScissorBounds {
        val left = floor(x * scaleX).toInt()
        val top = floor(y * scaleY).toInt()
        val right = ceil((x + width) * scaleX).toInt()
        val bottom = ceil((y + height) * scaleY).toInt()
        return ScissorBounds(
            x = left,
            y = top,
            width = (right - left).coerceAtLeast(0),
            height = (bottom - top).coerceAtLeast(0),
        )
    }

    private fun bindRootTarget() {
        uiWriteAlpha(false)
        val target = renderTarget
        if (target == null) {
            Minecraft.getInstance().mainRenderTarget.bindWrite(true)
            val window = Minecraft.getInstance().window
            RenderSystem.viewport(0, 0, window.width, window.height)
            restoreMainProjection()
            return
        }

        bindTarget(target.toState())
        configureLayerProjection(target.logicalWidth, target.logicalHeight)
    }

    private fun effective(transform: UiMatrix4): UiMatrix4 {
        val layer = layerStack.lastOrNull() ?: return transform
        return UiMatrix4.translation(layer.padding, layer.padding, 0f) * (layer.transform.inverse()
            ?: UiMatrix4.translation(-layer.rect.x, -layer.rect.y, 0f)) * transform
    }

    /**
     * Clears the depth buffer inside [rect].
     */
    private fun clearDepthOf(rect: UiRect) {
        if (rect.width <= 0f || rect.height <= 0f) return

        val previous = scissorState
        setScissor(rect)
        val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        if (!depthMask) GL11.glDepthMask(true)
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT)
        if (!depthMask) GL11.glDepthMask(false)
        scissorState = ScissorUnknown
        setScissor(previous as? UiRect)
    }

    private fun localRect(rect: UiRect): UiRect = layerStack.lastOrNull()?.let {
        rect.copy(
            x = rect.x - it.rect.x + it.padding,
            y = rect.y - it.rect.y + it.padding,
        )
    } ?: rect

    private fun transformedLocalRect(rect: UiRect, transform: UiMatrix4): UiRect {
        val topLeft = transform.transform(rect.x, rect.y)
        val bottomLeft = transform.transform(rect.x, rect.y + rect.height)
        val bottomRight = transform.transform(rect.x + rect.width, rect.y + rect.height)
        val topRight = transform.transform(rect.x + rect.width, rect.y)
        val left = minOf(topLeft.x, bottomLeft.x, bottomRight.x, topRight.x)
        val top = minOf(topLeft.y, bottomLeft.y, bottomRight.y, topRight.y)
        val right = maxOf(topLeft.x, bottomLeft.x, bottomRight.x, topRight.x)
        val bottom = maxOf(topLeft.y, bottomLeft.y, bottomRight.y, topRight.y)
        return UiRect(left, top, (right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
    }

    private fun layerScale(): Float {
        return (renderTarget?.scale ?: Minecraft.getInstance().window.guiScale.toFloat()) * LayerSupersampling
    }

    private fun restoreClips(clips: List<UiRect>) {
        clipStack.clear()
        clipStack.addAll(clips)
        restoreActiveClip()
    }

    private fun restoreActiveClip() {
        val top = clipStack.lastOrNull()
        if (top != null) {
            applyScissor(top)
        } else {
            disableScissor()
            scissorState = null
        }
    }

    private fun bindTarget(target: RenderTargetState) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebufferId)
        RenderSystem.viewport(target.x, target.y, target.width, target.height)
    }

    private fun configureLayerProjection(width: Float, height: Float) {
        RenderSystem.setProjectionMatrix(
            Matrix4f().setOrtho(0f, width, height, 0f, -1000f, 1000f),
            VertexSorting.ORTHOGRAPHIC_Z,
        )
        val stack = RenderSystem.getModelViewStack()
        stack.setIdentity()
        RenderSystem.applyModelViewMatrix()
    }

    private fun restoreMainProjection() {
        if (!layerProjectionActive || layerStack.isNotEmpty()) return
        RenderSystem.restoreProjectionMatrix()
        val stack = RenderSystem.getModelViewStack()
        stack.popPose()
        RenderSystem.applyModelViewMatrix()
        layerProjectionActive = false
    }

    private fun currentTarget(): RenderTargetState {
        val layer = layerStack.lastOrNull()
        if (layer != null) {
            return RenderTargetState(
                framebufferId = layer.framebuffer.framebuffer,
                framebuffer = layer.framebuffer.atlas,
                x = layer.framebuffer.region.x,
                y = layer.framebuffer.region.y,
                width = layer.framebuffer.width,
                height = layer.framebuffer.height,
                logicalWidth = layer.rect.width + layer.padding * 2f,
                logicalHeight = layer.rect.height + layer.padding * 2f,
                scale = layer.scale,
            )
        }
        renderTarget?.let { return it.toState() }
        val window = Minecraft.getInstance().window
        val target = Minecraft.getInstance().mainRenderTarget
        return RenderTargetState(
            framebufferId = target.frameBufferId,
            framebuffer = null,
            x = 0,
            y = 0,
            width = window.width,
            height = window.height,
            logicalWidth = window.guiScaledWidth.toFloat(),
            logicalHeight = window.guiScaledHeight.toFloat(),
            scale = window.width / window.guiScaledWidth.toFloat(),
        )
    }

    private fun UiRenderTarget.toState(): RenderTargetState {
        return RenderTargetState(
            framebufferId = framebufferId,
            framebuffer = null,
            x = x,
            y = y,
            width = width,
            height = height,
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            scale = scale,
        )
    }

}

private data object ScissorUnknown

/** Accumulated screen-space AABB of a batch's pending, not-yet-flushed pixels. */
private class UiBatchBounds {
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    fun clear() {
        minX = Float.POSITIVE_INFINITY
        minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY
        maxY = Float.NEGATIVE_INFINITY
    }

    fun add(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (minX < this.minX) this.minX = minX
        if (minY < this.minY) this.minY = minY
        if (maxX > this.maxX) this.maxX = maxX
        if (maxY > this.maxY) this.maxY = maxY
    }

    fun overlaps(minX: Float, minY: Float, maxX: Float, maxY: Float): Boolean {
        if (this.minX > this.maxX) return false
        return this.minX < maxX && minX < this.maxX && this.minY < maxY && minY < this.maxY
    }
}

// Чтобы он каждый кадр не выделял на это память
private fun collectTextEffects(
    inlineEffects: List<UiTextEffect>,
    commandEffects: List<UiTextEffect>,
    animated: Boolean,
): List<UiTextEffect> {
    var result: ArrayList<UiTextEffect>? = null
    for (effect in inlineEffects) {
        if (if (animated) effect.isAnimated else effect.isLayer) {
            if (result == null) result = ArrayList()
            result += effect
        }
    }
    for (effect in commandEffects) {
        if (if (animated) effect.isAnimated else effect.isLayer) {
            if (result == null) result = ArrayList()
            result += effect
        }
    }
    return result ?: emptyList()
}

private val POSE_STACK = PoseStack()

private class UiGlDrawScopeImpl(
    override val rect: UiRect,
    override val opacity: Float,
    override val poseStack: PoseStack,
) : UiGlDrawScope
