package ru.hollowhorizon.hollowengine.client.ui

import ru.hollowhorizon.hollowengine.client.ui.layout.InlineGroupDecoration
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutNode
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutResult
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.ScrollbarNode
import ru.hollowhorizon.hollowengine.client.ui.scroll.ScrollbarThumbNode
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollOffset
import ru.hollowhorizon.hollowengine.client.ui.shape.*
import ru.hollowhorizon.hollowengine.client.ui.style.*
import ru.hollowhorizon.hollowengine.client.ui.text.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiInlineStyle
import java.util.*

sealed interface UiRenderCommand {
    val node: UiNode
}

enum class UiRenderPhase {
    BACKGROUND, CONTENT, OVERLAY,
}

data class BeginLayerCommand(
    override val node: UiNode,
    val rect: UiRect,
    val radius: Float,
    val clipShape: Shape?,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val backdropFilter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val opacity: Float,
) : UiRenderCommand

data class EndLayerCommand(
    override val node: UiNode,
) : UiRenderCommand

/**
 * A no-op that forces the renderer to flush the current batch. Inserted between overlapping visual
 * subtrees of an overlap-capable container so phase batching can't reorder one over the other.
 */
data class FlushBarrierCommand(
    override val node: UiNode,
) : UiRenderCommand

data class DrawBackdropFilterCommand(
    override val node: UiNode,
    val rect: UiRect,
    val radius: Float,
    val filter: UiFilterChain,
    val opacity: Float,
    val transform: UiMatrix4,
    val backfaceVisibility: UiBackfaceVisibility,
) : UiRenderCommand

data class DrawShadowCommand(
    override val node: UiNode,
    val rect: UiRect,
    val radius: Float,
    val shape: Shape?,
    val shadows: List<UiShadow>,
    val opacity: Float,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val image: UiImageShadow? = null,
) : UiRenderCommand

data class PushClipCommand(
    override val node: UiNode,
    val rect: UiRect,
    val transform: UiMatrix4,
) : UiRenderCommand

data class PopClipCommand(
    override val node: UiNode,
) : UiRenderCommand

data class DrawBoxCommand(
    override val node: UiNode,
    val rect: UiRect,
    val paint: UiResolvedPaint,
    val border: UiBorder,
    val shadows: List<UiShadow>,
    val opacity: Float,
    val tint: UiColor,
    val transform: UiMatrix4,
    val renderToFramebuffer: Boolean,
    val fit: UiImageFit,
    val slice: UiInsets,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val phase: UiRenderPhase = UiRenderPhase.CONTENT,
    val uv: UiImageUv = UiImageUv.Full,
) : UiRenderCommand

data class DrawShapeCommand(
    override val node: UiNode,
    val rect: UiRect,
    val shape: Shape,
    val fill: UiResolvedPaint,
    val stroke: UiResolvedPaint,
    val strokeWidth: Float,
    val opacity: Float,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val phase: UiRenderPhase = UiRenderPhase.CONTENT,
    val strokeLineCap: UiPathStrokeLineCap = UiPathStrokeLineCap.Round,
    val strokeLineJoin: UiPathStrokeLineJoin = UiPathStrokeLineJoin.Round,
    val blurRadius: Float = 0f,
    val spreadRadius: Float = 0f,
) : UiRenderCommand

data class DrawTextCommand(
    override val node: UiNode,
    val rect: UiRect,
    val text: String,
    val color: UiColor,
    val opacity: Float,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val wrap: Boolean,
    val overflow: UiTextOverflow,
    val align: UiTextAlign,
    val fontSize: Float,
    val fontFamily: String?,
    val textEffects: List<UiTextEffect>,
    val layout: UiTextLayout,
    val scrollOffset: UiScrollOffset,
    val backfaceVisibility: UiBackfaceVisibility,
    val phase: UiRenderPhase = UiRenderPhase.CONTENT,
) : UiRenderCommand

data class DrawImageCommand(
    override val node: UiNode,
    val rect: UiRect,
    val source: String,
    val opacity: Float,
    val tint: UiColor,
    val transform: UiMatrix4,
    val renderToFramebuffer: Boolean,
    val fit: UiImageFit,
    val slice: UiInsets,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val phase: UiRenderPhase = UiRenderPhase.CONTENT,
    val uv: UiImageUv = UiImageUv.Full,
) : UiRenderCommand

/** Draws an existing OpenGL texture without registering it as a Minecraft resource. */
data class DrawRawTextureCommand(
    override val node: UiNode,
    val rect: UiRect,
    val textureId: Int,
    val opacity: Float,
    val flipY: Boolean,
    val opaque: Boolean,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val phase: UiRenderPhase = UiRenderPhase.CONTENT,
) : UiRenderCommand

/**
 * Runs a caller-supplied OpenGL [block] for a node, emitted by `Modifier.draw`/`drawBehind`'s `drawGl`.
 */
data class DrawCanvasGlCommand(
    override val node: UiNode,
    val rect: UiRect,
    val opacity: Float,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
    val block: UiGlDrawScope.() -> Unit,
    val phase: UiRenderPhase = UiRenderPhase.CONTENT,
) : UiRenderCommand

data class DrawItemCommand(
    override val node: UiNode,
    val rect: UiRect,
    val item: UiItem,
    val opacity: Float,
    val transform: UiMatrix4,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
) : UiRenderCommand

data class DrawEntityCommand(
    override val node: UiNode,
    val rect: UiRect,
    val entity: UiEntityRef,
    val opacity: Float,
    val transform: UiMatrix4,
    val renderToFramebuffer: Boolean,
    val filter: UiFilterChain,
    val backfaceVisibility: UiBackfaceVisibility,
) : UiRenderCommand

/**
 * Receives draw commands as the node tree is traversed. Rendering is streaming and
 * recursive: commands exist only as per-node parameter objects on their way to the
 * renderer, not as a retained frame-wide list.
 */
fun interface UiRenderSink {
    fun submit(command: UiRenderCommand)
}

operator fun UiRenderSink.plusAssign(command: UiRenderCommand) = submit(command)

private class OverlapOrderingSink(
    private val delegate: UiRenderSink,
    private val parent: UiNode,
) : UiRenderSink {
    private val siblingBounds = ArrayList<UiRect>()
    private val siblingMaxPhase = ArrayList<Int>()
    private var pendingStructuralCommands: ArrayList<UiRenderCommand>? = null
    private var childBounds = UiRect.Zero
    private var childMaxPhase = -1
    private var childStartedDrawing = false

    /**
     * The answer of [maxOverlappingSiblingPhase] for the current child. It only depends on the
     * child's bounds and the siblings behind it, so it is found once per child rather than
     * rescanned for each of the several commands the child emits.
     */
    private var overlappingSiblingPhase = -1

    fun beginChild(bounds: UiRect) {
        commitCurrentChild()
        childBounds = bounds
        childMaxPhase = -1
        childStartedDrawing = false
        pendingStructuralCommands?.clear()
        overlappingSiblingPhase = maxOverlappingSiblingPhase()
    }

    private fun commitCurrentChild() {
        if (childMaxPhase >= 0) {
            siblingBounds += childBounds
            siblingMaxPhase += childMaxPhase
        }
    }

    override fun submit(command: UiRenderCommand) {
        if (!command.drawsPixels()) {
            if (childStartedDrawing) {
                delegate.submit(command)
            } else {
                val pending = pendingStructuralCommands ?: ArrayList<UiRenderCommand>(4).also {
                    pendingStructuralCommands = it
                }
                pending += command
            }
            return
        }
        val phase = command.renderPhaseOrdinal()
        if (phase < overlappingSiblingPhase) {
            delegate.submit(FlushBarrierCommand(parent))
            siblingBounds.clear()
            siblingMaxPhase.clear()
            overlappingSiblingPhase = -1
        }
        childMaxPhase = maxOf(childMaxPhase, phase)
        if (!childStartedDrawing) {
            pendingStructuralCommands?.let { pending ->
                for (structuralCommand in pending) delegate.submit(structuralCommand)
                pending.clear()
            }
            childStartedDrawing = true
        }
        delegate.submit(command)
    }

    /** The highest phase any earlier sibling overlapping the current child has drawn (-1 if none). */
    private fun maxOverlappingSiblingPhase(): Int {
        var max = -1
        for (index in siblingBounds.indices) {
            if (siblingMaxPhase[index] > max && siblingBounds[index].overlaps(childBounds)) {
                max = siblingMaxPhase[index]
            }
        }
        return max
    }
}

private fun UiRenderCommand.renderPhaseOrdinal(): Int = when (this) {
    is DrawBoxCommand -> phase.ordinal
    is DrawShapeCommand -> phase.ordinal
    is DrawTextCommand -> phase.ordinal
    is DrawImageCommand -> phase.ordinal
    is DrawRawTextureCommand -> phase.ordinal
    is DrawParticlesCommand -> phase.ordinal
    is DrawCanvasGlCommand -> phase.ordinal
    is DrawShadowCommand, is DrawBackdropFilterCommand -> UiRenderPhase.BACKGROUND.ordinal
    else -> UiRenderPhase.CONTENT.ordinal
}

private fun UiRenderCommand.drawsPixels(): Boolean = when (this) {
    is DrawBackdropFilterCommand,
    is DrawShadowCommand,
    is DrawBoxCommand,
    is DrawShapeCommand,
    is DrawTextCommand,
    is DrawImageCommand,
    is DrawRawTextureCommand,
    is DrawParticlesCommand,
    is DrawItemCommand,
    is DrawEntityCommand,
    is DrawCanvasGlCommand,
        -> true

    is BeginLayerCommand,
    is EndLayerCommand,
    is FlushBarrierCommand,
    is PushClipCommand,
    is PopClipCommand,
        -> false
}

class UiCommandRenderer {
    /** Recursively walks the resolved tree, streaming each node's commands into [sink]. */
    fun render(
        resolved: UiNode,
        layout: UiLayoutResult,
        sink: UiRenderSink,
        profile: UiProfileFrame? = null,
    ) {
        if (profile != null) profile.commandCollections++
        val profiledSink = if (profile == null) {
            sink
        } else {
            UiRenderSink { command ->
                profile.recordCommand(command)
                sink.submit(command)
            }
        }
        collectNode(
            resolved.root,
            resolved,
            layout,
            profiledSink,
            activeClip = null,
            layoutBoundsMatchVisualBounds = true,
        )
    }

    fun collect(
        resolved: UiNode,
        layout: UiLayoutResult,
        profile: UiProfileFrame? = null,
    ): List<UiRenderCommand> {
        val commands = mutableListOf<UiRenderCommand>()
        render(resolved, layout, { commands += it }, profile)
        return commands
    }

    private fun collectNode(
        node: UiNode,
        resolved: UiNode,
        layout: UiLayoutResult,
        commands: UiRenderSink,
        activeClip: UiRect?,
        layoutBoundsMatchVisualBounds: Boolean,
    ) {
        val style = resolved[node]
        val layoutNode = layout[node]
        val isFramebuffer = layoutNode.needsFramebuffer
        val baseFilter = if (isFramebuffer) UiFilterChain.Empty else style.filter
        val localOpacity = if (isFramebuffer) 1f else style.opacity
        val visibleShadows = if (style.shadows.isEmpty()) emptyList() else style.shadows.filterNot { it.inset }
        val nodeLayoutBoundsMatchVisualBounds =
            layoutBoundsMatchVisualBounds && style.transform == DirectLayoutTransform
        val canCullNode =
            activeClip != null && nodeLayoutBoundsMatchVisualBounds && !isFramebuffer && visibleShadows.isEmpty() && style.backdropFilter.effects.isEmpty() && style.filter == UiFilterChain.Empty
        val cullNodeCommands = activeClip?.let { canCullNode && !layoutNode.rect.touches(it) } == true
        val pushedClip = (style.clip && style.clipShape == null) || style.scrollable

        if (cullNodeCommands && pushedClip) return

        if (!cullNodeCommands && style.backdropFilter.effects.isNotEmpty()) {
            commands += DrawBackdropFilterCommand(
                node = node,
                rect = layoutNode.rect,
                radius = style.border.radius,
                filter = style.backdropFilter,
                opacity = style.opacity,
                transform = layoutNode.worldTransform,
                backfaceVisibility = style.backfaceVisibility
            )
        }

        if (!cullNodeCommands && visibleShadows.isNotEmpty()) {
            commands += DrawShadowCommand(
                node = node,
                rect = layoutNode.rect,
                radius = style.border.radius,
                shape = style.shape ?: style.clipShape.takeIf { style.clip },
                shadows = visibleShadows,
                opacity = style.opacity,
                transform = layoutNode.worldTransform,
                filter = baseFilter,
                backfaceVisibility = style.backfaceVisibility,
                image = imageShadow(style, layoutNode),
            )
        }

        if (!cullNodeCommands && isFramebuffer) {
            commands += BeginLayerCommand(
                node = node, rect = layoutNode.rect, radius = style.border.radius,
                clipShape = style.clipShape.takeIf { style.clip },
                transform = layoutNode.worldTransform, filter = style.filter,
                backdropFilter = style.backdropFilter, backfaceVisibility = style.backfaceVisibility,
                opacity = style.opacity,
            )
        }

        if (!cullNodeCommands) {
            drawNodeBody(
                node,
                resolved,
                style,
                layoutNode,
                layout,
                commands,
                activeClip,
                localOpacity,
                baseFilter,
                pushedClip,
                nodeLayoutBoundsMatchVisualBounds,
            )
            if (isFramebuffer) commands += EndLayerCommand(node)
        }
    }

    private fun drawNodeBody(
        node: UiNode,
        resolved: UiNode,
        style: UiComputedStyle,
        layoutNode: UiLayoutNode,
        layout: UiLayoutResult,
        commands: UiRenderSink,
        activeClip: UiRect?,
        localOpacity: Float,
        baseFilter: UiFilterChain,
        pushedClip: Boolean,
        layoutBoundsMatchVisualBounds: Boolean,
    ) {
        appendBackgroundCommand(node, style, layoutNode, localOpacity, baseFilter, commands)
        appendCanvasModifiers(
            node,
            layoutNode,
            localOpacity,
            baseFilter,
            UiCanvasDrawLayer.BEHIND,
            UiRenderPhase.BACKGROUND,
            commands,
        )

        if (pushedClip) {
            commands += PushClipCommand(
                node,
                layoutNode.content.localTo(layoutNode.rect),
                layoutNode.worldTransform,
            )
        }

        collectNodeContent(
            node,
            style,
            localOpacity,
            layoutNode,
            baseFilter,
            commands,
        )

        val childClip = when {
            !pushedClip -> activeClip
            activeClip == null -> layoutNode.content.takeIf { it.hasVisibleArea() }
            else -> activeClip.visibleIntersection(layoutNode.content)
        }
        if (!pushedClip || childClip != null) {
            val sorted = layout.childrenOf(node)
            var start = 0
            while (start < sorted.size) {
                val groupLayer = sorted[start].resolvedSnapshot.layer
                var end = start + 1
                while (end < sorted.size && sorted[end].resolvedSnapshot.layer == groupLayer) end++
                if (start > 0) commands += FlushBarrierCommand(node)
                val groupSink = if (end - start > 1 && node.childrenCanOverlap()) {
                    OverlapOrderingSink(commands, node)
                } else {
                    null
                }
                for (index in start until end) {
                    val child = sorted[index]
                    groupSink?.beginChild(layout[child].rect)
                    collectNode(
                        child,
                        resolved,
                        layout,
                        groupSink ?: commands,
                        activeClip = childClip,
                        layoutBoundsMatchVisualBounds = layoutBoundsMatchVisualBounds,
                    )
                }
                start = end
            }
        }

        appendCanvasModifiers(
            node,
            layoutNode,
            localOpacity,
            baseFilter,
            UiCanvasDrawLayer.OVERLAY,
            UiRenderPhase.OVERLAY,
            commands,
        )

        if (pushedClip) commands += PopClipCommand(node)

        // Framework-synthesized scrollbars render after the content clip is popped so they sit
        // in the gutter, on top of content, clipped only by the container's ancestors.
        layout.scrollbars[node]?.forEach { scrollbar ->
            if (scrollbar in layout.nodes) {
                collectNode(
                    scrollbar,
                    resolved,
                    layout,
                    commands,
                    activeClip = activeClip,
                    layoutBoundsMatchVisualBounds = layoutBoundsMatchVisualBounds,
                )
            }
        }
    }

    private fun appendCanvasModifiers(
        node: UiNode,
        layoutNode: UiLayoutNode,
        opacity: Float,
        filter: UiFilterChain,
        layer: UiCanvasDrawLayer,
        phase: UiRenderPhase,
        commands: UiRenderSink,
    ) {
        var scope: UiCommandCanvasScope? = null
        val modifiers = node.resolvedModifiers
        for (index in modifiers.indices) {
            val modifier = modifiers[index]
            if (modifier !is UiCanvasModifier || modifier.layer != layer) continue
            val activeScope = scope ?: canvasScope(node, layoutNode, opacity, filter, phase, commands).also {
                scope = it
            }
            activeScope.run(modifier.block)
        }
    }

    /**
     * Whether a container positions its children so they can overlap. Flow layouts (rows/columns/inline)
     * never overlap; a box (free/stack) or a custom policy (the overlay/popup host) can.
     */
    private fun UiNode.childrenCanOverlap(): Boolean = when ((measurePolicy as? UiBuiltInMeasurePolicy)?.kind) {
        UiBuiltInMeasurePolicyKind.COLUMN,
        UiBuiltInMeasurePolicyKind.ROW,
        UiBuiltInMeasurePolicyKind.INLINE_FLOW,
            -> false

        UiBuiltInMeasurePolicyKind.BOX, null -> true
    }

    private fun collectNodeContent(
        node: UiNode,
        style: UiComputedStyle,
        opacity: Float,
        layoutNode: UiLayoutNode,
        filter: UiFilterChain,
        commands: UiRenderSink,
    ) {
        val contentTransform = layoutNode.worldTransform.translated(
            layoutNode.content.x - layoutNode.rect.x, layoutNode.content.y - layoutNode.rect.y, 0f
        )
        val backface = style.backfaceVisibility

        when (node) {
            is SpanNode -> {
                val textLayout = layoutNode.textLayout ?: singleRunSpanLayout(node, style, layoutNode)
                commands += DrawTextCommand(
                    node,
                    layoutNode.content,
                    node.text,
                    style.foreground,
                    opacity,
                    contentTransform,
                    filter,
                    wrap = false,
                    style.textOverflow,
                    style.textAlign,
                    style.fontSize,
                    style.fontFamily,
                    emptyList(),
                    textLayout,
                    layoutNode.scrollOffset,
                    backface
                )
            }

            else -> {
                style.image?.let { source ->
                    val svg = svgImageGeometry(source)
                    if (svg != null) {
                        val contentLocal = UiRect(
                            layoutNode.content.x - layoutNode.rect.x,
                            layoutNode.content.y - layoutNode.rect.y,
                            layoutNode.content.width,
                            layoutNode.content.height,
                        )
                        canvasScope(node, layoutNode, opacity, filter, UiRenderPhase.CONTENT, commands).drawSvg(
                                svg,
                                contentLocal,
                                style.tint
                            )
                    } else {
                        commands += DrawImageCommand(
                            node, layoutNode.content, source, opacity, style.tint, contentTransform,
                            false, style.imageFit, style.imageSlice, filter, backface,
                            uv = style.imageUv,
                        )
                    }
                }
                style.item?.let {
                    commands += DrawItemCommand(
                        node, layoutNode.content, it, opacity, contentTransform, filter, backface,
                    )
                }
                style.entity?.let {
                    commands += DrawEntityCommand(
                        node, layoutNode.content, it, opacity, contentTransform, false, filter, backface,
                    )
                }
            }
        }
    }

    /** The parsed (cached) vector document for an `.svg` image source, or null for raster images. */
    private fun svgImageGeometry(source: String): UiSvgPathDocument? {
        if (!source.endsWith(".svg", ignoreCase = true)) return null
        return runCatching { svgResourceDocument(source) }.getOrNull()
    }

    private fun appendBackgroundCommand(
        node: UiNode,
        style: UiComputedStyle,
        layoutNode: UiLayoutNode,
        opacity: Float,
        filter: UiFilterChain,
        commands: UiRenderSink,
    ) {
        layoutNode.inlineDecoration?.let { decoration ->
            appendInlineDecoration(node, style, layoutNode, decoration, opacity, filter, commands)
            return
        }
        val rect = layoutNode.rect
        if (!rect.isDrawable() || opacity <= 0f) return
        val shape = style.shape
        if (shape != null) {
            val fill = (style.shapeFill ?: style.background).resolve()
            val strokePaint =
                style.shapeStroke ?: style.border.takeIf { it.width != UiInsets.Zero }?.let { UiPaint.Color(it.color) }
            val strokeWidth = (style.shapeStrokeWidth ?: style.border.width.left).resolve(layoutNode.rect.width)
            val stroke = strokePaint?.resolve() ?: UiResolvedPaint.None
            val hasFill = fill.hasVisiblePixels()
            val hasStroke = stroke.hasVisiblePixels() && strokeWidth > 0f
            if (!hasFill && !hasStroke) return
            commands += DrawShapeCommand(
                node = node,
                rect = rect,
                shape = shape,
                fill = fill.takeIf { hasFill } ?: UiResolvedPaint.None,
                stroke = stroke.takeIf { hasStroke } ?: UiResolvedPaint.None,
                strokeWidth = strokeWidth.takeIf { hasStroke } ?: 0f,
                opacity = opacity,
                transform = layoutNode.worldTransform,
                filter = filter,
                backfaceVisibility = node.resolvedSnapshot.backfaceVisibility,
                phase = UiRenderPhase.BACKGROUND,
            )
            return
        }
        if (style.background == UiPaint.None && style.border.width == UiInsets.Zero) return
        val paint = style.background.resolve()
        val border = style.border.withNormalizedRadius()
        if (!paint.hasVisiblePixels() && !border.hasVisiblePixels()) return
        commands += DrawBoxCommand(
            node = node,
            rect = rect,
            paint = paint,
            border = border,
            shadows = emptyList(),
            opacity = opacity,
            tint = style.tint,
            transform = layoutNode.worldTransform,
            renderToFramebuffer = false,
            fit = style.imageFit,
            slice = style.imageSlice,
            filter = filter,
            backfaceVisibility = node.resolvedSnapshot.backfaceVisibility,
            phase = UiRenderPhase.BACKGROUND,
            uv = style.imageUv,
        )
    }

    private fun canvasScope(
        node: UiNode,
        layoutNode: UiLayoutNode,
        opacity: Float,
        filter: UiFilterChain,
        phase: UiRenderPhase,
        commands: UiRenderSink,
    ) = UiCommandCanvasScope(
        node = node,
        layoutNode = layoutNode,
        opacity = opacity,
        filter = filter,
        backfaceVisibility = node.resolvedSnapshot.backfaceVisibility,
        phase = phase,
        sink = commands,
    )

    /**
     * An inline group (a span or a nested inline flow) draws its background/border once PER LINE.
     * Each line box already runs continuously from the group's first to its last piece on that line
     * (so internal spaces are painted, never skipped). `box-decoration-break` decides how the border
     * and rounding are sliced: [UiBoxDecorationBreak.CLONE] gives every line the full border+radius;
     * [UiBoxDecorationBreak.SLICE] rounds/borders only the outer line ends for one continuous shape.
     */
    private fun appendInlineDecoration(
        node: UiNode,
        style: UiComputedStyle,
        layoutNode: UiLayoutNode,
        decoration: InlineGroupDecoration,
        opacity: Float,
        filter: UiFilterChain,
        commands: UiRenderSink,
    ) {
        val hasBorder = style.border.width != UiInsets.Zero
        if (style.background == UiPaint.None && !hasBorder) return
        if (opacity <= 0f) return
        val paint = style.background.resolve()
        val lines = decoration.lines
        val clone = decoration.decorationBreak == UiBoxDecorationBreak.CLONE
        lines.forEachIndexed { index, box ->
            if (!box.width.isFinite() || !box.height.isFinite() || box.width <= 0f || box.height <= 0f) {
                return@forEachIndexed
            }
            val outerEnd = clone || index == 0 || index == lines.lastIndex
            val border = if (hasBorder && outerEnd) {
                style.border
            } else {
                // Middle slice lines keep the background continuous but drop rounding/borders.
                UiBorder(radius = if (outerEnd) style.border.radius else 0f)
            }.withNormalizedRadius()
            if (!paint.hasVisiblePixels() && !border.hasVisiblePixels()) return@forEachIndexed
            commands += DrawBoxCommand(
                node = node,
                rect = UiRect(
                    layoutNode.rect.x + box.x,
                    layoutNode.rect.y + box.y,
                    box.width,
                    box.height,
                ),
                paint = paint,
                border = border,
                shadows = emptyList(),
                opacity = opacity,
                tint = style.tint,
                transform = layoutNode.worldTransform.translated(box.x, box.y),
                renderToFramebuffer = false,
                fit = style.imageFit,
                slice = style.imageSlice,
                filter = filter,
                backfaceVisibility = node.resolvedSnapshot.backfaceVisibility,
                phase = UiRenderPhase.BACKGROUND,
                uv = style.imageUv,
            )
        }
    }

    private fun UiRect.isDrawable(): Boolean = width.isFinite() && height.isFinite() && width > 0f && height > 0f

    private fun UiBorder.withNormalizedRadius(): UiBorder = if (radius >= 0f) this else copy(radius = 0f)

    private fun singleRunSpanLayout(node: SpanNode, style: UiComputedStyle, layoutNode: UiLayoutNode): UiTextLayout {
        val text = node.text
        val width = UiTextLayouter.measureTextWidth(text, style.fontSize, style.fontFamily)
        val height = style.fontSize
        val line = UiTextLine(
            text = text,
            x = 0f,
            y = 0f,
            width = width,
            naturalWidth = width,
            height = height,
            fragments = listOf(UiTextRun(text, UiInlineStyle(effects = style.textEffects), 0f, 0f, width, height)),
        )
        return UiTextLayout(listOf(line), maxOf(width, layoutNode.content.width), height)
    }

}

sealed interface UiResolvedPaint {
    data object None : UiResolvedPaint
    data class Color(val color: UiColor) : UiResolvedPaint
    data class LinearGradient(val angleDegrees: Float, val stops: List<UiGradientStop>) : UiResolvedPaint
    data class RadialGradient(val gradient: UiRadialGradient) : UiResolvedPaint
    data class Image(val source: String) : UiResolvedPaint
    data class Shader(val name: String) : UiResolvedPaint
}

internal fun UiResolvedPaint.hasVisiblePixels(): Boolean = when (this) {
    UiResolvedPaint.None -> false
    is UiResolvedPaint.Color -> color.alpha > 0f
    is UiResolvedPaint.LinearGradient -> stops.any { it.color.alpha > 0f }
    is UiResolvedPaint.RadialGradient -> gradient.stops.any { it.color.alpha > 0f }
    is UiResolvedPaint.Image,
    is UiResolvedPaint.Shader,
        -> true
}

internal fun UiBorder.hasVisiblePixels(): Boolean = color.alpha > 0f && width != UiInsets.Zero

internal fun UiPaint.resolve(): UiResolvedPaint = when (this) {
    UiPaint.None -> UiResolvedPaint.None
    is UiPaint.Color -> UiResolvedPaint.Color(color)
    is UiPaint.LinearGradient -> UiResolvedPaint.LinearGradient(angleDegrees, stops)
    is UiPaint.RadialGradient -> UiResolvedPaint.RadialGradient(gradient)
    is UiPaint.Image -> UiResolvedPaint.Image(source)
    is UiPaint.Shader -> UiResolvedPaint.Shader(name)
}

data class UiHit(
    val node: UiNode,
    val localX: Float,
    val localY: Float,
)

private sealed interface HitTestTask {
    data class Enter(
        val node: UiNode,
        val ancestorClip: UiRect?,
    ) : HitTestTask

    data class Test(
        val node: UiNode,
        val ancestorClip: UiRect?,
    ) : HitTestTask
}

class UiHitTester {
    fun hitTest(resolved: UiNode, layout: UiLayoutResult, x: Float, y: Float): UiHit? {
        return hitNode(resolved.root, resolved, layout, x, y)
    }

    private fun hitNode(
        node: UiNode,
        resolved: UiNode,
        layout: UiLayoutResult,
        x: Float,
        y: Float,
    ): UiHit? {
        val stack = ArrayDeque<HitTestTask>()
        stack.add(HitTestTask.Enter(node, null))
        while (stack.isNotEmpty()) {
            when (val task = stack.removeLast()) {
                is HitTestTask.Enter -> {
                    val current = task.node
                    val layoutNode = layout.nodes[current] ?: continue
                    val effectiveClip = task.ancestorClip.intersect(layoutNode.clip)
                    stack.add(HitTestTask.Test(current, task.ancestorClip))
                    for (child in layout.childrenOf(current)) {
                        stack.add(HitTestTask.Enter(child, effectiveClip))
                    }
                    layout.scrollbars[current]?.forEach { scrollbar ->
                        if (scrollbar in layout.nodes) stack.add(HitTestTask.Enter(scrollbar, task.ancestorClip))
                    }
                }

                is HitTestTask.Test -> {
                    val current = task.node
                    val style = resolved[current]
                    if (style.inputTransparent) continue
                    if (!style.hoverable && !style.clickable && !style.focusable && !style.draggable && !style.scrollable) {
                        continue
                    }
                    if (current.hasEffectiveState(UiState.DISABLED)) continue
                    task.ancestorClip?.let { clip ->
                        if (!clip.contains(x, y)) continue
                    }
                    val layoutNode = layout[current]
                    if (!layoutNode.inputContains(x, y)) continue
                    val inverse = layoutNode.inputTransform.inverse() ?: continue
                    val local = inverse.transform(x, y, 0f)
                    return UiHit(current, local.x, local.y)
                }
            }
        }
        return null
    }

    fun hitsVisible(resolved: UiNode, layout: UiLayoutResult, x: Float, y: Float): Boolean {
        return visibleNode(resolved.root, resolved, layout, x, y)
    }

    private fun visibleNode(node: UiNode, resolved: UiNode, layout: UiLayoutResult, x: Float, y: Float): Boolean {
        val stack = ArrayDeque<HitTestTask>()
        stack.add(HitTestTask.Enter(node, null))
        while (stack.isNotEmpty()) {
            when (val task = stack.removeLast()) {
                is HitTestTask.Enter -> {
                    val current = task.node
                    val layoutNode = layout.nodes[current] ?: continue
                    val effectiveClip = task.ancestorClip.intersect(layoutNode.clip)
                    stack.add(HitTestTask.Test(current, task.ancestorClip))
                    for (child in layout.childrenOf(current)) {
                        stack.add(HitTestTask.Enter(child, effectiveClip))
                    }
                    layout.scrollbars[current]?.forEach {
                        if (it in layout.nodes) stack.add(
                            HitTestTask.Enter(
                                it, task.ancestorClip
                            )
                        )
                    }
                }

                is HitTestTask.Test -> {
                    val current = task.node
                    val style = resolved[current]
                    if (style.inputTransparent || !current.paintsGeometry(style)) continue
                    task.ancestorClip?.let { if (!it.contains(x, y)) continue }
                    val layoutNode = layout[current]
                    if (!layoutNode.inputContains(x, y)) continue
                    return true
                }
            }
        }
        return false
    }

    private fun UiNode.paintsGeometry(style: UiComputedStyle): Boolean = when (this) {
        is SpanNode, is ScrollbarNode, is ScrollbarThumbNode,
            -> true

        else -> style.image != null || style.item != null || style.entity != null || style.background != UiPaint.None || style.border.width != UiInsets.Zero || style.shape != null
    }
}

private val DirectLayoutTransform = UiTransform()

private fun UiRect.hasVisibleArea(): Boolean {
    return width > 0f && height > 0f
}

private fun UiRect.visibleIntersection(other: UiRect): UiRect? {
    val left = maxOf(x, other.x)
    val top = maxOf(y, other.y)
    val right = minOf(x + width, other.x + other.width)
    val bottom = minOf(y + height, other.y + other.height)
    if (right <= left || bottom <= top) return null
    return UiRect(left, top, right - left, bottom - top)
}

private fun UiRect.touches(other: UiRect): Boolean {
    return x <= other.x + other.width && other.x <= x + width && y <= other.y + other.height && other.y <= y + height
}

private fun UiRect.localTo(parent: UiRect): UiRect {
    return copy(x = x - parent.x, y = y - parent.y)
}

