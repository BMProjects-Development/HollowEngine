package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayout
import java.util.*
import java.util.concurrent.atomic.AtomicLong

data class UiProfileTiming(
    val currentMs: Double,
    val averageMs: Double,
    val maxMs: Double,
)

data class UiProfileTimings(
    val ui: UiProfileTiming,
    val compose: UiProfileTiming,
    val style: UiProfileTiming,
    val measure: UiProfileTiming,
    val placement: UiProfileTiming,
    val render: UiProfileTiming,
    val input: UiProfileTiming,
)

data class UiStyleProfile(
    val passes: Int,
    val visitedNodes: Int,
    val recomputedNodes: Int,
    val cacheHits: Int,
    val selectorChecks: Int,
    val matchedRules: Int,
    val animatedNodes: Int,
    val inheritedInvalidations: Int,
    val misses: UiStyleMissProfile,
)

data class UiStyleMissProfile(
    val uncachedNode: Int,
    val nodeRevisionChanged: Int,
    val parentInheritedChanged: Int,
    val scopeChanged: Int,
    val ancestorChanged: Int,
    val stylesheetChanged: Int,
)

data class UiLayoutProfile(
    val measurePasses: Int,
    val placementPasses: Int,
    val layoutReuses: Int,
    val incrementalLayouts: Int,
    val measureCalls: Int,
    val uniqueMeasuredNodes: Int,
    val measureCacheHits: Int,
    val textNodeMeasurements: Int,
    val maxMeasureDepth: Int,
    val placedNodes: Int,
    val matrixCalculations: Int,
    val framebufferNodes: Int,
    val textLayouts: Int,
    val reusedTextLayouts: Int,
    val textGlyphs: Int,
    val textLines: Int,
)

data class UiRenderCommandProfile(
    val collections: Int,
    val total: Int,
    val rectangles: Int,
    val shapes: Int,
    val text: Int,
    val images: Int,
    val itemEntities: Int,
    val clipChanges: Int,
    val framebufferLayers: Int,
    val other: Int,
)

/** GPU submissions the renderer actually issued for the frame (draw calls / batch flushes). */
data class UiRenderDrawProfile(
    val analyticRectDraws: Int,
    val pathTileDraws: Int,
    val shapeDraws: Int,
    val imageDraws: Int,
    val vanillaTextFlushes: Int,
    val msdfTextDraws: Int,
    val scissorChanges: Int,
    val layerComposites: Int,
) {
    val total: Int
        get() = analyticRectDraws + pathTileDraws + shapeDraws + imageDraws +
                vanillaTextFlushes + msdfTextDraws + layerComposites
}

data class UiProfileSnapshot(
    val sampledFrames: Int,
    val timings: UiProfileTimings,
    val composePasses: Int,
    val recomposedFrames: Int,
    val style: UiStyleProfile,
    val layout: UiLayoutProfile,
    val commands: UiRenderCommandProfile,
    val draws: UiRenderDrawProfile,
    val report: String,
) {
    companion object {
        private val ZeroTiming = UiProfileTiming(0.0, 0.0, 0.0)

        val Empty = UiProfileSnapshot(
            sampledFrames = 0,
            timings = UiProfileTimings(
                ZeroTiming,
                ZeroTiming,
                ZeroTiming,
                ZeroTiming,
                ZeroTiming,
                ZeroTiming,
                ZeroTiming,
            ),
            composePasses = 0,
            recomposedFrames = 0,
            style = UiStyleProfile(0, 0, 0, 0, 0, 0, 0, 0, UiStyleMissProfile(0, 0, 0, 0, 0, 0)),
            layout = UiLayoutProfile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            commands = UiRenderCommandProfile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            draws = UiRenderDrawProfile(0, 0, 0, 0, 0, 0, 0, 0),
            report = "UI profiler is waiting for a completed frame.",
        )
    }
}

class UiProfiler(
    private val historySize: Int = DefaultHistorySize,
) {
    init {
        require(historySize > 0) { "UI profiler history must contain at least one frame" }
    }

    var enabled by mutableStateOf(false)
    var snapshot by mutableStateOf(UiProfileSnapshot.Empty)
        private set

    private val pendingInputNanos = AtomicLong()
    private val history = Array(TimingCount) { LongArray(historySize) }
    private var historyCursor = 0
    private var sampledFrames = 0
    private var lastPublishNanos = 0L

    /** Starts an opaque metrics session, or returns null while collection is disabled. */
    fun beginFrame(): UiProfileFrame? {
        if (!enabled) return null
        return UiProfileFrame(this, pendingInputNanos.getAndSet(0L))
    }

    internal fun recordInput(durationNanos: Long) {
        if (enabled) pendingInputNanos.addAndGet(durationNanos)
    }

    @Synchronized
    internal fun complete(frame: UiProfileFrame, forcePublish: Boolean = false) {
        val values = frame.timingValues()
        for (index in 0 until TimingCount) history[index][historyCursor] = values[index]
        historyCursor = (historyCursor + 1) % historySize
        sampledFrames = (sampledFrames + 1).coerceAtMost(historySize)

        val now = System.nanoTime()
        if (!forcePublish && snapshot !== UiProfileSnapshot.Empty && now - lastPublishNanos < PublishIntervalNanos) return
        lastPublishNanos = now
        snapshot = createSnapshot(frame, values)
    }

    @Synchronized
    fun clear() {
        history.forEach { it.fill(0L) }
        historyCursor = 0
        sampledFrames = 0
        pendingInputNanos.set(0L)
        lastPublishNanos = 0L
        snapshot = UiProfileSnapshot.Empty
    }

    private fun createSnapshot(frame: UiProfileFrame, current: LongArray): UiProfileSnapshot {
        val timing = Array(TimingCount) { index -> timing(index, current[index]) }
        val styleMisses = UiStyleMissProfile(
            frame.styleMissUncached,
            frame.styleMissNodeRevision,
            frame.styleMissParent,
            frame.styleMissScope,
            frame.styleMissAncestor,
            frame.styleMissStylesheet,
        )
        val style = UiStyleProfile(
            frame.stylePasses,
            frame.styleVisitedNodes,
            frame.styleRecomputedNodes,
            frame.styleCacheHits,
            frame.selectorChecks,
            frame.matchedRules,
            frame.animatedNodes,
            frame.styleMissParent,
            styleMisses,
        )
        val layout = UiLayoutProfile(
            frame.measurePasses,
            frame.placementPasses,
            frame.layoutReuses,
            frame.incrementalLayouts,
            frame.measureCalls,
            frame.uniqueMeasuredNodes,
            frame.measureCacheHits,
            frame.textNodeMeasurements,
            frame.maxMeasureDepth,
            frame.placedNodes,
            frame.matrixCalculations,
            frame.framebufferNodes,
            frame.textLayouts,
            frame.reusedTextLayouts,
            frame.textGlyphs,
            frame.textLines,
        )
        val commands = UiRenderCommandProfile(
            frame.commandCollections,
            frame.commandTotal,
            frame.rectangleCommands,
            frame.shapeCommands,
            frame.textCommands,
            frame.imageCommands,
            frame.itemEntityCommands,
            frame.clipChanges,
            frame.framebufferLayers,
            frame.otherCommands,
        )
        val draws = UiRenderDrawProfile(
            frame.analyticRectDraws,
            frame.pathTileDraws,
            frame.shapeDraws,
            frame.imageDraws,
            frame.vanillaTextFlushes,
            frame.msdfTextDraws,
            frame.scissorChanges,
            frame.layerComposites,
        )
        val timings = UiProfileTimings(
            timing[UiTiming],
            timing[ComposeTiming],
            timing[StyleTiming],
            timing[MeasureTiming],
            timing[PlacementTiming],
            timing[RenderTiming],
            timing[InputTiming],
        )
        return UiProfileSnapshot(
            sampledFrames,
            timings,
            frame.composePasses,
            frame.recomposedFrames,
            style,
            layout,
            commands,
            draws,
            report = formatReport(
                sampledFrames, timings, frame.composePasses, frame.recomposedFrames, style, layout, commands, draws,
            ),
        )
    }

    private fun timing(index: Int, current: Long): UiProfileTiming {
        var total = 0L
        var maximum = 0L
        for (sample in 0 until sampledFrames) {
            val value = history[index][sample]
            total += value
            if (value > maximum) maximum = value
        }
        return UiProfileTiming(
            current.toMilliseconds(),
            if (sampledFrames == 0) 0.0 else total.toDouble() / sampledFrames / NanosPerMillisecond,
            maximum.toMilliseconds(),
        )
    }

    private companion object {
        const val DefaultHistorySize = 120
        const val PublishIntervalNanos = 250_000_000L
        const val UiTiming = 0
        const val ComposeTiming = 1
        const val StyleTiming = 2
        const val MeasureTiming = 3
        const val PlacementTiming = 4
        const val RenderTiming = 5
        const val InputTiming = 6
        const val TimingCount = 7
        const val NanosPerMillisecond = 1_000_000.0
    }
}

class UiProfileFrame internal constructor(
    internal val owner: UiProfiler,
    pendingInputNanos: Long,
) {
    internal var composeNanos = 0L
    internal var styleNanos = 0L
    internal var measureNanos = 0L
    internal var placementNanos = 0L
    internal var renderNanos = 0L
    internal var inputNanos = pendingInputNanos

    internal var composePasses = 0
    internal var recomposedFrames = 0
    internal var stylePasses = 0
    internal var styleVisitedNodes = 0
    internal var styleRecomputedNodes = 0
    internal var styleCacheHits = 0
    internal var selectorChecks = 0
    internal var matchedRules = 0
    internal var animatedNodes = 0
    internal var styleMissUncached = 0
    internal var styleMissNodeRevision = 0
    internal var styleMissParent = 0
    internal var styleMissScope = 0
    internal var styleMissAncestor = 0
    internal var styleMissStylesheet = 0

    internal var measurePasses = 0
    internal var placementPasses = 0
    internal var layoutReuses = 0
    internal var incrementalLayouts = 0
    internal var measureCalls = 0
    internal var measureCacheHits = 0
    internal var textNodeMeasurements = 0
    internal var maxMeasureDepth = 0
    internal var placedNodes = 0
    internal var matrixCalculations = 0
    internal var framebufferNodes = 0
    internal var textLayouts = 0
    internal var reusedTextLayouts = 0
    internal var textGlyphs = 0
    internal var textLines = 0

    internal var commandCollections = 0
    internal var commandTotal = 0
    internal var rectangleCommands = 0
    internal var shapeCommands = 0
    internal var textCommands = 0
    internal var imageCommands = 0
    internal var itemEntityCommands = 0
    internal var clipChanges = 0
    internal var framebufferLayers = 0
    internal var otherCommands = 0

    internal var analyticRectDraws = 0
    internal var pathTileDraws = 0
    internal var shapeDraws = 0
    internal var imageDraws = 0
    internal var vanillaTextFlushes = 0
    internal var msdfTextDraws = 0
    internal var scissorChanges = 0
    internal var layerComposites = 0

    private val measuredNodes = IdentityHashMap<UiNode, Unit>()
    private val placedTextLayouts = IdentityHashMap<UiTextLayout, Unit>()

    internal val uniqueMeasuredNodes: Int get() = measuredNodes.size

    internal fun recordMeasuredNode(node: UiNode) {
        measuredNodes[node] = Unit
    }

    internal fun recordTextLayout(layout: UiTextLayout) {
        if (placedTextLayouts.put(layout, Unit) != null) {
            reusedTextLayouts++
            return
        }
        textLayouts++
        textLines += layout.lines.size
        for (line in layout.lines) textGlyphs += line.text.length
    }

    internal fun recordCommand(command: UiRenderCommand) {
        commandTotal++
        when (command) {
            is DrawBoxCommand -> rectangleCommands++
            is DrawShapeCommand -> shapeCommands++
            is DrawTextCommand -> textCommands++
            is DrawImageCommand, is DrawParticlesCommand -> imageCommands++
            is DrawItemCommand, is DrawEntityCommand -> itemEntityCommands++
            is PushClipCommand, is PopClipCommand -> clipChanges++
            is BeginLayerCommand -> framebufferLayers++
            else -> otherCommands++
        }
    }

    internal fun timingValues(): LongArray {
        val ui = composeNanos + styleNanos + measureNanos + placementNanos + renderNanos + inputNanos
        return longArrayOf(ui, composeNanos, styleNanos, measureNanos, placementNanos, renderNanos, inputNanos)
    }
}

private fun formatReport(
    sampledFrames: Int,
    timings: UiProfileTimings,
    composePasses: Int,
    recomposedFrames: Int,
    style: UiStyleProfile,
    layout: UiLayoutProfile,
    commands: UiRenderCommandProfile,
    draws: UiRenderDrawProfile,
): String = buildString(2100) {
    appendLine("UI profile ($sampledFrames-frame window)")
    appendTiming("UI", timings.ui)
    appendTiming("Compose", timings.compose)
    appendTiming("Style", timings.style)
    appendTiming("Measure", timings.measure)
    appendTiming("Placement", timings.placement)
    appendTiming("Render", timings.render)
    appendTiming("Input", timings.input)
    appendLine()
    appendLine("Passes:")
    appendLine("  Compose:             $composePasses (changed: $recomposedFrames)")
    appendLine("  Style:               ${style.passes}")
    appendLine("  Measure:             ${layout.measurePasses}")
    appendLine("  Placement:           ${layout.placementPasses}")
    appendLine("  Command collections: ${commands.collections}")
    appendLine("  Layout reuses:       ${layout.layoutReuses}")
    appendLine("  Scroll-only layouts: ${layout.incrementalLayouts}")
    appendLine()
    appendLine("Style:")
    appendLine("  visited nodes:          ${style.visitedNodes}")
    appendLine("  recomputed nodes:       ${style.recomputedNodes}")
    append("  cache hits:             ${style.cacheHits}  ")
    appendLine(styleHitRate(style).formatPercent())
    appendLine("  selector checks:        ${style.selectorChecks}")
    appendLine("  matched rules:          ${style.matchedRules}")
    appendLine("  animated nodes:         ${style.animatedNodes}")
    appendLine("  inherited invalidations:${style.inheritedInvalidations}")
    appendLine()
    appendLine("Style misses: ${style.recomputedNodes}")
    appendLine("  uncached node:             ${style.misses.uncachedNode}")
    appendLine("  node revision changed:     ${style.misses.nodeRevisionChanged}")
    appendLine("  parent inherited changed:  ${style.misses.parentInheritedChanged}")
    appendLine("  scope changed:             ${style.misses.scopeChanged}")
    appendLine("  ancestor changed:          ${style.misses.ancestorChanged}")
    appendLine("  stylesheet changed:        ${style.misses.stylesheetChanged}")
    appendLine()
    appendLine("Measure:")
    appendLine("  calls:              ${layout.measureCalls}")
    appendLine("  unique nodes:       ${layout.uniqueMeasuredNodes}")
    appendLine("  repeated calls:     ${(layout.measureCalls - layout.uniqueMeasuredNodes).coerceAtLeast(0)}")
    appendLine("  cache hits:         ${layout.measureCacheHits}")
    appendLine("  text node measures: ${layout.textNodeMeasurements}")
    appendLine("  max measure depth:  ${layout.maxMeasureDepth}")
    appendLine()
    appendLine("Placement:")
    appendLine("  placed nodes:        ${layout.placedNodes}")
    appendLine("  matrix calculations: ${layout.matrixCalculations}")
    appendLine("  framebuffer nodes:   ${layout.framebufferNodes}")
    appendLine()
    appendLine("Text placement:")
    appendLine("  unique layouts: ${layout.textLayouts}")
    appendLine("  reused layouts: ${layout.reusedTextLayouts}")
    appendLine("  glyphs:         ${layout.textGlyphs}")
    appendLine("  lines:          ${layout.textLines}")
    appendLine()
    appendLine("Render commands:")
    appendLine("  total:              ${commands.total}")
    appendLine("  rectangles:         ${commands.rectangles}")
    appendLine("  shapes:             ${commands.shapes}")
    appendLine("  text:               ${commands.text}")
    appendLine("  images:             ${commands.images}")
    appendLine("  item/entity:        ${commands.itemEntities}")
    appendLine("  clip changes:       ${commands.clipChanges}")
    appendLine("  framebuffer layers: ${commands.framebufferLayers}")
    appendLine("  other:              ${commands.other}")
    appendLine()
    appendLine("GPU submissions (draw calls / batch flushes):")
    appendLine("  total draw calls:   ${draws.total}")
    appendLine("  analytic rect:      ${draws.analyticRectDraws}")
    appendLine("  path tile:          ${draws.pathTileDraws}")
    appendLine("  triangle shape:     ${draws.shapeDraws}")
    appendLine("  image batches:      ${draws.imageDraws}")
    appendLine("  vanilla text flush: ${draws.vanillaTextFlushes}")
    appendLine("  msdf text:          ${draws.msdfTextDraws}")
    appendLine("  layer composites:   ${draws.layerComposites}")
    appendLine("  scissor changes:    ${draws.scissorChanges}")
}

private fun StringBuilder.appendTiming(label: String, timing: UiProfileTiming) {
    append(label.padEnd(10))
    append(timing.currentMs.formatMilliseconds())
    append(" ms | avg ")
    append(timing.averageMs.formatMilliseconds())
    append(" | max ")
    appendLine(timing.maxMs.formatMilliseconds())
}

private fun styleHitRate(style: UiStyleProfile): Double {
    val total = style.cacheHits + style.recomputedNodes
    return if (total == 0) 100.0 else style.cacheHits * 100.0 / total
}

private fun Double.formatMilliseconds(): String = String.format(Locale.ROOT, "%.3f", this)
private fun Double.formatPercent(): String = String.format(Locale.ROOT, "%.1f%%", this)
private fun Long.toMilliseconds(): Double = this / 1_000_000.0
