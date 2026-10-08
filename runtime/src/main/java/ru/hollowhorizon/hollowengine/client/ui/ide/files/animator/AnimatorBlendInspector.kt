package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.common.models.*
import kotlin.math.roundToInt

private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val PlanarHeight = 196f
private const val LinearHeight = 36f
private const val PointSize = 10f
private const val ProbeSize = 7f

private const val HaloReach = 18f
private const val TickWidth = 26f
private const val RangeFieldWidth = 48f
private const val WeightWidth = 30f
private const val LabelWidth = 120f
private data class BlendPoint(val x: Float, val y: Float)

/** The fields of a blend state that sit with its name and kind. */
@Composable
internal fun BlendStateFields(document: HollowIdeAnimatorDocument, layerId: String, state: BlendStateSpec) {
    fun update(next: BlendStateSpec) = document.edit { it.withState(layerId, next) }

    Label(animatorText("blend_by"))
    Pills(listOf(false, true), state.isPlanar, { animatorText(if (it) "blend_planar" else "blend_linear") }) { planar ->
        update(state.withPlanar(planar))
    }
    ExpressionField(animatorText(if (state.isPlanar) "blend_x" else "blend_parameter"), state.x.source) { value ->
        update(state.copy(x = AnimationExpression(value)))
    }
    state.y?.let { y ->
        ExpressionField(
            animatorText("blend_y"),
            y.source
        ) { value -> update(state.copy(y = AnimationExpression(value))) }
    }
    PlayModeRow(state.playMode) { mode -> update(state.copy(playMode = mode)) }
    ExpressionField(animatorText("speed"), state.speed.source) { value ->
        update(
            state.copy(
                speed = AnimationExpression(
                    value
                )
            )
        )
    }
}

/**
 * The clips of a blend state: a diagram to place them on and to check weights at any point, its range,
 * and a folding section per clip.
 */
@Composable
internal fun BlendMotionsSection(document: HollowIdeAnimatorDocument, layerId: String, state: BlendStateSpec) {
    fun update(next: BlendStateSpec, mergeKey: String? = null) =
        document.edit(mergeKey = mergeKey) { it.withState(layerId, next) }

    var probe by remember(layerId, state.id) { mutableStateOf(BlendPoint(0f, 0f)) }
    val expanded = remember(layerId, state.id) { mutableStateListOf<Int>() }
    val planar = state.isPlanar
    val checked = BlendPoint(state.xRange.clamp(probe.x), if (planar) state.yRange.clamp(probe.y) else 0f)
    val weights = BlendWeights.of(state, checked.x, checked.y)
    val prefix = "blend-$layerId-${state.id}"

    Column(modifier = Modifier.size(100.percent).style(AnimatorStylesheet)) {
        Section(animatorText("section_blend_motions")) {
            if (state.motions.isEmpty()) {
                Hint(animatorText("no_motions"))
            } else {
                ProbeReadout(state, checked)
                BlendDiagram(
                    prefix, state, checked, weights,
                    onProbe = { probe = it },
                    onPick = { index -> if (index !in expanded) expanded += index },
                ) { index, point ->
                    val motion = state.motions[index]
                    update(
                        state.withMotion(index, motion.copy(x = point.x, y = point.y)),
                        mergeKey = "$prefix-$index-move"
                    )
                }
                if (planar) PlaneRange(prefix, state) { update(it) } else LineRange(prefix, state) { update(it) }
            }

            state.motions.forEachIndexed { index, motion ->
                MotionSection(
                    prefix, index, motion, planar, weights.getOrElse(index) { 0f },
                    expanded = index in expanded,
                    onToggle = { if (!expanded.remove(index)) expanded += index },
                ) { next ->
                    if (next != null) {
                        update(state.withMotion(index, next))
                    } else {
                        update(state.withoutMotion(index))
                        expanded.forgetRemoved(index)
                    }
                }
            }

            InspectorButton(animatorText("add_motion")) {
                val middle = BlendMotion(
                    animation = "",
                    x = snap((state.xRange.min + state.xRange.max) / 2f),
                    y = if (planar) snap((state.yRange.min + state.yRange.max) / 2f) else 0f,
                )
                update(state.copy(motions = state.motions + middle))
                expanded += state.motions.size
            }
        }
    }
}

/** Drops [removed] from the open sections and moves the ones after it up, as the clips below it did. */
private fun SnapshotStateList<Int>.forgetRemoved(removed: Int) {
    val kept = filter { it != removed }.map { if (it > removed) it - 1 else it }
    clear()
    addAll(kept)
}

/** What the crosshair on the diagram is and where it stands, with how the diagram works behind the icon. */
@Composable
private fun ProbeReadout(state: BlendStateSpec, probe: BlendPoint) {
    Row(tags = listOf("animator-blend-readout"), modifier = Modifier.size(100.percent)) {
        Box(tags = listOf("animator-blend-readout-mark"))
        Text(animatorText("blend_probe"), tags = listOf("animator-blend-readout-title"), modifier = Modifier.grow(1f))
        val position = if (state.isPlanar) "X ${format(probe.x)}  Y ${format(probe.y)}" else format(probe.x)
        Text(position, tags = listOf("animator-blend-readout-value"))
        FieldHelp(animatorText("blend_diagram_help"))
    }
}

/** The ends of the line, typed into fields that sit right under them. */
@Composable
private fun LineRange(prefix: String, state: BlendStateSpec, onChange: (BlendStateSpec) -> Unit) {
    Row(tags = listOf("animator-blend-line-range"), modifier = Modifier.size(100.percent)) {
        RangeField("$prefix-x-min", state.xRange.min) { value ->
            if (value < state.xRange.max) onChange(state.copy(xRange = state.xRange.copy(min = value)))
        }
        Box(modifier = Modifier.size(0.px).grow(1f))
        RangeField("$prefix-x-max", state.xRange.max) { value ->
            if (value > state.xRange.min) onChange(state.copy(xRange = state.xRange.copy(max = value)))
        }
    }
}

/** How far the plane reaches along each parameter, as a small table. */
@Composable
private fun PlaneRange(prefix: String, state: BlendStateSpec, onChange: (BlendStateSpec) -> Unit) {
    Column(tags = listOf("animator-blend-plane-range"), modifier = Modifier.size(100.percent)) {
        Label(animatorText("blend_range"))
        RangeRow(prefix, "X", state.xRange) { onChange(state.copy(xRange = it)) }
        RangeRow(prefix, "Y", state.yRange) { onChange(state.copy(yRange = it)) }
    }
}

@Composable
private fun RangeRow(prefix: String, axis: String, range: BlendRange, onChange: (BlendRange) -> Unit) {
    Row(tags = listOf("animator-blend-range-row"), modifier = Modifier.size(100.percent)) {
        Text(axis, tags = listOf("animator-blend-range-axis"))
        Text(animatorText("blend_from"), tags = listOf("animator-blend-range-word"))
        Column(modifier = Modifier.size(0.px).grow(1f)) {
            NumberInput("$prefix-$axis-min", range.min.toDouble(), whole = false) { value ->
                if (value < range.max) onChange(range.copy(min = value.toFloat()))
            }
        }
        Text(animatorText("blend_to"), tags = listOf("animator-blend-range-word"))
        Column(modifier = Modifier.size(0.px).grow(1f)) {
            NumberInput("$prefix-$axis-max", range.max.toDouble(), whole = false) { value ->
                if (value > range.min) onChange(range.copy(max = value.toFloat()))
            }
        }
    }
}

@Composable
private fun RangeField(path: String, value: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.size(RangeFieldWidth.px)) {
        NumberInput(path, value.toDouble(), whole = false) { onChange(it.toFloat()) }
    }
}

@Composable
private fun MotionSection(
    prefix: String,
    index: Int,
    motion: BlendMotion,
    planar: Boolean,
    weight: Float,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (BlendMotion?) -> Unit,
) {
    CollapsibleSection(
        title = motion.animation.ifBlank { "#${index + 1}" },
        expanded = expanded,
        id = "$prefix-motion-$index",
        trailing = {
            Text(pointText(motion, planar), tags = listOf("animator-blend-heading-position"))
            Text(
                "${(weight * 100f).roundToInt()}%",
                tags = listOf("animator-blend-weight"),
                modifier = Modifier.size(WeightWidth.px).tooltipOnHover(animatorText("blend_weight_hint")),
            )
            InspectorIconButton(RemoveIcon, animatorText("remove_motion")) { onChange(null) }
        },
        onToggle = onToggle,
    ) {
        TextRow(animatorText("animation"), motion.animation, id = "$prefix-$index-animation") { value ->
            onChange(motion.copy(animation = value))
        }
        Row(tags = listOf("animator-blend-coordinates"), modifier = Modifier.size(100.percent)) {
            Column(modifier = Modifier.size(0.px).grow(1f)) {
                FloatRow(
                    animatorText(if (planar) "blend_point_x" else "blend_position"),
                    motion.x,
                    id = "$prefix-$index-x"
                ) { value ->
                    onChange(motion.copy(x = value))
                }
            }
            if (planar) {
                Column(modifier = Modifier.size(0.px).grow(1f)) {
                    FloatRow(animatorText("blend_point_y"), motion.y, id = "$prefix-$index-y") { value ->
                        onChange(motion.copy(y = value))
                    }
                }
            }
            Column(modifier = Modifier.size(0.px).grow(1f)) {
                FloatRow(animatorText("speed"), motion.speed, id = "$prefix-$index-speed") { value ->
                    onChange(motion.copy(speed = value))
                }
            }
        }
    }
}

@Composable
private fun BlendDiagram(
    prefix: String,
    state: BlendStateSpec,
    probe: BlendPoint,
    weights: FloatArray,
    onProbe: (BlendPoint) -> Unit,
    onPick: (index: Int) -> Unit,
    onMove: (index: Int, BlendPoint) -> Unit,
) {
    var width by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    val pressedAt = remember { floatArrayOf(0f, 0f) }

    val planar = state.isPlanar
    val view = BlendView(planar, state.xRange, state.yRange, width, if (planar) PlanarHeight else LinearHeight)

    fun dragged(event: UiEvent): BlendPoint {
        val start = view.toScreen(BlendPoint(pressedAt[0], pressedAt[1]))
        return view.clamp(view.toValue(start.x + event.dragTotalX, start.y + event.dragTotalY))
    }

    val moving = dragging?.let(state.motions::getOrNull)?.let { BlendPoint(it.x, if (planar) it.y else 0f) }

    Box(
        id = "$prefix-diagram",
        mode = UiBoxMode.STACK,
        tags = listOf("animator-blend-diagram"),
        modifier = Modifier.size(100.percent, view.height.px).onPlaced { rect -> width = rect.width }
            .drawBehind(key = Triple(view, probe, moving)) { drawFrame(view, probe, moving) }
            .input(hoverable = true, clickable = true, draggable = true).cursor(UiCursorShape.CROSSHAIR)
            .onPress { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                val point = view.clamp(view.toValue(event.localX, event.localY))
                pressedAt[0] = point.x
                pressedAt[1] = point.y
                onProbe(point)
                event.consume()
            }.onDrag { event ->
                onProbe(dragged(event))
                event.consume()
            },
    ) {
        if (width <= 0f) return@Box

        if (planar) PlaneTicks(view)

        val marker = view.toScreen(probe)
        Box(
            tags = listOf("animator-blend-probe"),
            modifier = Modifier.position((marker.x - ProbeSize / 2f).px, (marker.y - ProbeSize / 2f).px)
                .size(ProbeSize.px, ProbeSize.px).inputTransparent(),
        )

        state.motions.forEachIndexed { index, motion ->
            val point = view.clamp(BlendPoint(motion.x, motion.y))
            val screen = view.toScreen(point)
            val weight = weights.getOrElse(index) { 0f }
            val active = weight > 0.001f

            if (active) {
                val halo = PointSize + HaloReach * weight
                Box(
                    tags = listOf("animator-blend-halo"),
                    modifier = Modifier.position((screen.x - halo / 2f).px, (screen.y - halo / 2f).px)
                        .size(halo.px, halo.px).inputTransparent(),
                )
            }
            Box(
                id = "$prefix-point-$index",
                tags = listOfNotNull("animator-blend-point", "active".takeIf { active }),
                modifier = Modifier.position((screen.x - PointSize / 2f).px, (screen.y - PointSize / 2f).px)
                    .size(PointSize.px, PointSize.px).input(hoverable = true, clickable = true, draggable = true)
                    .cursor(UiCursorShape.MOVE).onPress { event ->
                        if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                        dragging = index
                        pressedAt[0] = point.x
                        pressedAt[1] = point.y
                        onPick(index)
                        event.consume()
                    }.onDrag { event ->
                        val moved = dragged(event)
                        onMove(index, BlendPoint(snap(moved.x), if (planar) snap(moved.y) else motion.y))
                        event.consume()
                    }.onRelease { event ->
                        dragging = null
                        event.consume()
                    },
            )
            PointLabel(view, screen, motion, index, active)
        }
    }
}

@Composable
private fun PointLabel(view: BlendView, screen: BlendPoint, motion: BlendMotion, index: Int, active: Boolean) {
    val leftward = screen.x > view.plot.x + view.plot.width * 0.6f
    val x = if (leftward) screen.x - PointSize / 2f - 3f - LabelWidth else screen.x + PointSize / 2f + 3f
    Text(
        tags = listOfNotNull("animator-blend-label", "active".takeIf { active }, "leftward".takeIf { leftward }),
        modifier = Modifier.position(x.px, (screen.y - PointSize - 4f).px)
            .let { if (leftward) it.size(LabelWidth.px) else it }.inputTransparent(),
    ) {
        Span(motion.animation.ifBlank { "#${index + 1}" })
        Span("  ${pointText(motion, view.planar)}", tags = listOf("animator-blend-label-position"))
    }
}

@Composable
private fun PlaneTicks(view: BlendView) {
    val plot = view.plot
    val bottom = plot.y + plot.height
    val left = plot.x - TickWidth - 3f
    val origin = view.toScreen(BlendPoint(0f, 0f))

    Tick(format(view.xRange.min), plot.x - TickWidth / 2f, bottom + 2f, "center")
    Tick(format(view.xRange.max), plot.x + plot.width - TickWidth / 2f, bottom + 2f, "center")
    Tick(format(view.yRange.max), left, plot.y - 5f, "right")
    Tick(format(view.yRange.min), left, bottom - 5f, "right")
    if (view.xRange.min < 0f && view.xRange.max > 0f) Tick("0", origin.x - TickWidth / 2f, bottom + 2f, "center")
    if (view.yRange.min < 0f && view.yRange.max > 0f) Tick("0", left, origin.y - 5f, "right")
}

@Composable
private fun Tick(text: String, x: Float, y: Float, align: String) {
    Text(
        text,
        tags = listOf("animator-blend-tick", align),
        modifier = Modifier.position(x.px, y.px).size(TickWidth.px).inputTransparent(),
    )
}

private data class BlendView(
    val planar: Boolean,
    val xRange: BlendRange,
    val yRange: BlendRange,
    val width: Float,
    val height: Float,
) {
    val plot: UiRect = if (planar) {
        val side = minOf(width - LEFT - RIGHT, height - TOP - BOTTOM).coerceAtLeast(1f)
        UiRect(LEFT + (width - LEFT - RIGHT - side) / 2f, TOP + (height - TOP - BOTTOM - side) / 2f, side, side)
    } else {
        val inset = RangeFieldWidth / 2f
        UiRect(inset, height - 8f, (width - inset * 2f).coerceAtLeast(1f), 0f)
    }

    private val xSpan: Float = (xRange.max - xRange.min).coerceAtLeast(MIN_SPAN)
    private val ySpan: Float = (yRange.max - yRange.min).coerceAtLeast(MIN_SPAN)

    fun toScreen(point: BlendPoint): BlendPoint = BlendPoint(
        plot.x + (point.x - xRange.min) / xSpan * plot.width,
        if (planar) plot.y + (yRange.max - point.y) / ySpan * plot.height else plot.y,
    )

    fun toValue(screenX: Float, screenY: Float): BlendPoint = BlendPoint(
        xRange.min + (screenX - plot.x) / plot.width * xSpan,
        if (planar) yRange.max - (screenY - plot.y) / plot.height * ySpan else 0f,
    )

    fun clamp(point: BlendPoint): BlendPoint =
        BlendPoint(xRange.clamp(point.x), if (planar) yRange.clamp(point.y) else 0f)

    companion object {
        const val LEFT = TickWidth + 6f
        const val RIGHT = 8f
        const val TOP = 8f
        const val BOTTOM = 14f
        const val MIN_SPAN = 0.001f
    }
}

private fun UiCanvasDrawScope.drawFrame(view: BlendView, probe: BlendPoint, moving: BlendPoint?) {
    if (view.width <= 0f) return
    val plot = view.plot
    val grid = UiPaint.Color(AnimatorColors.Grid)
    val axis = UiPaint.Color(AnimatorColors.Border)
    val crosshair = UiPaint.Color(AnimatorColors.Accent.copy(alpha = 0.55f))
    val guide = UiPaint.Color(AnimatorColors.NodeEntry.copy(alpha = 0.5f))
    val marker = view.toScreen(probe)

    fun vertical(x: Float, top: Float, length: Float, paint: UiPaint) =
        drawRect(UiRect(x - 0.5f, top, 1f, length), paint)

    fun horizontal(y: Float, left: Float, length: Float, paint: UiPaint) =
        drawRect(UiRect(left, y - 0.5f, length, 1f), paint)

    if (!view.planar) {
        horizontal(plot.y, plot.x, plot.width, axis)
        vertical(plot.x, plot.y - 4f, 8f, axis)
        vertical(plot.x + plot.width, plot.y - 4f, 8f, axis)
        moving?.let { vertical(view.toScreen(it).x, plot.y - 8f, 16f, guide) }
        vertical(marker.x, plot.y - 8f, 16f, crosshair)
        return
    }

    drawRect(plot, UiPaint.Color(AnimatorColors.Canvas), radius = 3f)
    for (step in 1..3) {
        vertical(plot.x + plot.width * step / 4f, plot.y, plot.height, grid)
        horizontal(plot.y + plot.height * step / 4f, plot.x, plot.width, grid)
    }
    val origin = view.toScreen(BlendPoint(0f, 0f))
    if (view.xRange.min < 0f && view.xRange.max > 0f) vertical(origin.x, plot.y, plot.height, axis)
    if (view.yRange.min < 0f && view.yRange.max > 0f) horizontal(origin.y, plot.x, plot.width, axis)

    moving?.let { point ->
        val at = view.toScreen(point)
        vertical(at.x, plot.y, plot.height, guide)
        horizontal(at.y, plot.x, plot.width, guide)
    }
    vertical(marker.x, plot.y, plot.height, crosshair)
    horizontal(marker.y, plot.x, plot.width, crosshair)
}

private fun pointText(motion: BlendMotion, planar: Boolean): String =
    if (planar) "${format(motion.x)}, ${format(motion.y)}" else format(motion.x)

private fun format(value: Float): String = formatNumber((value * 100f).roundToInt() / 100.0, whole = false)

private fun snap(value: Float): Float = (value * 20f).roundToInt() / 20f
