package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoColors
import ru.hollowhorizon.hollowengine.client.editor.ModelPreviewAxes
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeyAction
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.*

/**
 * A camera that circles what it looks at, the way the previews of the editor do.
 * Each camera keeps its own conventions for [yaw] and [pitch]; the rest is said in world directions.
 */
interface OrbitCamera {
    var yaw: Float
    var pitch: Float

    /** How far [pitch] goes either way. */
    val pitchLimit: Float get() = 90f

    /** How the view names the world's axes, turned from the plain X, Y and Z; see [ModelPreviewAxes]. */
    val axesFrame: QuatF get() = QuatF.IDENTITY

    /** [world] in the camera's frame: x to the right of the view, y up it, z toward the viewer. */
    fun toView(world: Vec3f): Vec3f

    /** The yaw and pitch that look at the target from [back], a world direction pointing at the camera. */
    fun anglesFor(back: Vec3f): Pair<Float, Float>

    /** Where what is selected in the view is, in the camera's world; null when nothing is. */
    val selectionCenter: Vec3f? get() = null

    /** Turns to [yaw] and [pitch]; about [around], a world point that stays where the view shows it, when given. */
    fun turnTo(yaw: Float, pitch: Float, around: Vec3f? = null)

    /** Moves the view, without turning it, so [point] is in its middle and the camera circles it from then on. */
    fun focus(point: Vec3f)
}

/** Turns the camera about its current view center. */
fun OrbitCamera.orbitTo(yaw: Float, pitch: Float) = turnTo(yaw, pitch)

/**
 * Yaw and pitch for [back] on a camera whose back direction is (sin y, sin p, cos y) scaled by cos p, with y the yaw
 * times [yawSign] plus [yawOffset]: the shape both previews' cameras have.
 */
fun OrbitCamera.anglesAround(back: Vec3f, yawSign: Float, yawOffset: Float): Pair<Float, Float> {
    val pitch = Math.toDegrees(asin(back.y.coerceIn(-1f, 1f).toDouble())).toFloat().coerceIn(-pitchLimit, pitchLimit)
    if (abs(back.y) > 0.999f) return yaw to pitch
    val yaw = Math.toDegrees(atan2(yawSign * back.x.toDouble(), back.z.toDouble())).toFloat() - yawOffset
    return yaw to pitch
}

/** Turns an [OrbitCamera] smoothly toward where it is sent. Runs while [ViewAxes] is shown. */
class OrbitCameraTurn(val camera: OrbitCamera) {
    private var target by mutableStateOf<Pair<Float, Float>?>(null)

    /** Looks at the target from [back]; from the opposite side when it already does. */
    fun lookFrom(back: Vec3f) {
        val view = camera.toView(back)
        val facing = view.z > 0.999f
        target = camera.anglesFor(if (facing) Vec3f(-back.x, -back.y, -back.z) else back)
    }

    fun orbitBy(yaw: Float, pitch: Float) {
        val (fromYaw, fromPitch) = target ?: (camera.yaw to camera.pitch)
        target = fromYaw + yaw to (fromPitch + pitch).coerceIn(-camera.pitchLimit, camera.pitchLimit)
    }

    /** The views and orbits of the numpad; true when [key] was one of them. */
    fun handleKey(key: Int, modifiers: Int): Boolean {
        Views.firstOrNull { (action, _) -> action.matches(key, modifiers) }?.let { (_, back) ->
            lookFrom(back.rotateBy(camera.axesFrame))
            return true
        }
        when {
            HollowKeys.VIEW_SELECTED.matches(key, modifiers) -> camera.focus(camera.selectionCenter ?: return false)
            HollowKeys.ORBIT_LEFT.matches(key, modifiers) -> orbitBy(-OrbitStep, 0f)
            HollowKeys.ORBIT_RIGHT.matches(key, modifiers) -> orbitBy(OrbitStep, 0f)
            HollowKeys.ORBIT_UP.matches(key, modifiers) -> orbitBy(0f, OrbitStep)
            HollowKeys.ORBIT_DOWN.matches(key, modifiers) -> orbitBy(0f, -OrbitStep)
            else -> return false
        }
        return true
    }

    internal suspend fun run() {
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                val (yaw, pitch) = target ?: run {
                    last = now
                    return@withFrameNanos
                }
                val dt = if (last < 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                val share = 1f - exp(-dt * GlideRate)
                val yawLeft = shortest(yaw - camera.yaw)
                val pitchLeft = pitch - camera.pitch
                if (abs(yawLeft) < SnapDegrees && abs(pitchLeft) < SnapDegrees) {
                    camera.orbitTo(camera.yaw + yawLeft, pitch)
                    camera.yaw %= 360f
                    target = null
                } else {
                    camera.orbitTo(camera.yaw + yawLeft * share, camera.pitch + pitchLeft * share)
                }
            }
        }
    }

    private fun shortest(degrees: Float): Float {
        var value = degrees % 360f
        if (value > 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }

    private companion object {
        const val OrbitStep = 15f
        const val GlideRate = 14f
        const val SnapDegrees = 0.05f

        /**
         * In the axes the view shows: front is the model's face, which looks along +Z there, and its right side is
         * then toward -X, the way it is for an entity in the world.
         */
        val Views: List<Pair<HollowKeyAction, Vec3f>> = listOf(
            HollowKeys.VIEW_FRONT to Vec3f(0f, 0f, 1f),
            HollowKeys.VIEW_BACK to Vec3f(0f, 0f, -1f),
            HollowKeys.VIEW_RIGHT to Vec3f(-1f, 0f, 0f),
            HollowKeys.VIEW_LEFT to Vec3f(1f, 0f, 0f),
            HollowKeys.VIEW_TOP to Vec3f(0f, 1f, 0f),
            HollowKeys.VIEW_BOTTOM to Vec3f(0f, -1f, 0f),
        )
    }
}

private class AxisEnd(
    val axis: Vec3f,
    val color: UiColor,
    val label: String?,
    val x: Float,
    val y: Float,
    val depth: Float,
) {
    val positive: Boolean get() = label != null
}

/** The world's axes as camera sees them, in a corner of a preview. */
@Composable
fun ViewAxes(turn: OrbitCameraTurn, modifier: Modifier = Modifier) {
    LaunchedEffect(turn) { turn.run() }
    var hovered by remember { mutableStateOf<Vec3f?>(null) }
    val camera = turn.camera
    camera.yaw
    camera.pitch
    val ends = axisEnds(camera)
    Box(
        id = "view-axes",
        mode = UiBoxMode.STACK,
        modifier = modifier.size(Size.px, Size.px).input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .tooltipOnHover(viewHint).onHover { event -> hovered = endAt(ends, event.localX, event.localY)?.axis }
            .onExit { hovered = null }.onClick { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
                endAt(ends, event.localX, event.localY)?.let { turn.lookFrom(it.axis) }
                event.consume()
            }.drawBehind(key = listOf(ends.map { it.x to it.y }, hovered)) {
                drawRect(UiRect(0f, 0f, Size, Size), UiPaint.Color(Backdrop), radius = Size / 2f)
                ends.forEach { end ->
                    val color = if (end.axis == hovered) GizmoColors.highlighted(end.color) else end.color
                    val line = if (end.positive) solidLine(end.x, end.y) else dashedLine(end.x, end.y)
                    drawShape(line, bounds, UiPaint.Color(color), UiDrawStyle.Stroke(width = LineWidth))
                    val radius = if (end.positive) PositiveRadius else NegativeRadius
                    val rect = UiRect(end.x - radius, end.y - radius, radius * 2f, radius * 2f)
                    if (end.positive) {
                        drawRect(rect, UiPaint.Color(color), radius = radius)
                    } else {
                        val ring = UiBorder(UiInsets.all(RingWidth.px), color, radius)
                        drawRect(rect, UiPaint.Color(color.darkened()), radius = radius, border = ring)
                    }
                }
            },
    ) {
        ends.forEach { end ->
            val label = end.label ?: return@forEach
            Box(
                mode = UiBoxMode.STACK,
                modifier = Modifier.position((end.x - LabelHalf).px, (end.y - LabelHalf).px)
                    .size((LabelHalf * 2f).px, (LabelHalf * 2f).px).inputTransparent(),
            ) {
                Text(label, tags = listOf("view-axes-label"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
            }
        }
    }
}

private val viewHint: String
    get() = listOf(
        HollowKeys.VIEW_FRONT, HollowKeys.VIEW_RIGHT, HollowKeys.VIEW_TOP
    ).joinToString(" | ") { it.hint(it.label) }

private fun axisEnds(camera: OrbitCamera): List<AxisEnd> = Axes.flatMap { (axis, color, label) ->
    listOf(1f, -1f).map { sign ->
        val world = Vec3f(axis.x * sign, axis.y * sign, axis.z * sign).rotateBy(camera.axesFrame)
        val view = camera.toView(world)
        AxisEnd(
            axis = world,
            color = color,
            label = label.takeIf { sign > 0f },
            x = Center + view.x * Reach,
            y = Center - view.y * Reach,
            depth = view.z,
        )
    }
}.sortedBy { it.depth }

private fun solidLine(x: Float, y: Float) = GenericShape {
    moveTo(Center, Center)
    lineTo(x, y)
}

private fun dashedLine(x: Float, y: Float) = GenericShape {
    val length = hypot(x - Center, y - Center)
    if (length < 1e-3f) return@GenericShape
    val dx = (x - Center) / length
    val dy = (y - Center) / length
    var from = 0f
    while (from < length) {
        val to = (from + Dash).coerceAtMost(length)
        moveTo(Center + dx * from, Center + dy * from)
        lineTo(Center + dx * to, Center + dy * to)
        from = to + Gap
    }
}

/** The color of a hollow end's middle: the axis color sunk most of the way toward the backdrop. */
private fun UiColor.darkened() = UiColor(red * 0.35f, green * 0.35f, blue * 0.35f, alpha)

/** The end under ([x], [y]), the one nearest the viewer where two overlap. */
private fun endAt(ends: List<AxisEnd>, x: Float, y: Float): AxisEnd? =
    ends.filter { hypot(it.x - x, it.y - y) <= PickRadius }.maxByOrNull { it.depth }

private val Axes = listOf(
    Triple(Vec3f.X_AXIS, GizmoColors.AXIS_X, "X"),
    Triple(Vec3f.Y_AXIS, GizmoColors.AXIS_Y, "Y"),
    Triple(Vec3f.Z_AXIS, GizmoColors.AXIS_Z, "Z"),
)

private const val Size = 64f
private const val Center = Size / 2f
private const val Reach = 22f
private const val PositiveRadius = 6.5f
private const val NegativeRadius = 5.5f
private const val RingWidth = 1.5f
private const val LineWidth = 1.5f
private const val Dash = 3f
private const val Gap = 2f
private const val PickRadius = 8f
private const val LabelHalf = 6.5f
private val Backdrop = UiColor(0f, 0f, 0f, 0.18f)
