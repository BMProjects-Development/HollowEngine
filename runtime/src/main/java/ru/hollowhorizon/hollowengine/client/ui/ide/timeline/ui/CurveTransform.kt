package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyResult
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import kotlin.math.*

internal data class CurveView(val pxPerSec: Float, val center: Float, val span: Float, val height: Float) {
    val pxPerValue: Float get() = curvePixelsPerValue(span, height)

    fun x(time: Float): Float = TimelineLeftPadding + time * pxPerSec

    fun y(value: Float): Float = curveValueToY(value, center, span, height)

    fun time(x: Float): Float = (x - TimelineLeftPadding) / pxPerSec

    fun value(y: Float): Float = curveYToValue(y, center, span, height)
}

internal class CurveTransform private constructor(
    private val controller: TimelineController,
    val mode: GizmoEditMode,
    private val view: CurveView,
    private val startX: Float,
    private val startY: Float,
    private val keys: List<KeyStart>,
    private val handles: List<HandleStart>,
) {
    /** 0 holds it to the time, 1 to the value; null while it is free. */
    var axis: Int? = null
        private set

    /** The middle of the picked keys and the keys of the picked handles, in the graph's pixels. */
    val pivotX: Float
    val pivotY: Float

    /** Where the pointer drives it to now, for the line drawn from the middle while turning or stretching. */
    var pointerX = startX
        private set
    var pointerY = startY
        private set

    init {
        val points = keys.map { it.x to it.y } + handles.map { it.keyX to it.keyY }
        pivotX = points.map { it.first }.average().toFloat()
        pivotY = points.map { it.second }.average().toFloat()
        controller.beginHistoryTransaction(
            when (mode) {
                GizmoEditMode.TRANSLATE -> TimelineEdits.MOVE_KEYS
                GizmoEditMode.ROTATE -> TimelineEdits.ROTATE_KEYS
                GizmoEditMode.SCALE -> TimelineEdits.SCALE_KEYS
            },
        )
    }

    /** Puts what is picked where the pointer at ([x], [y]) takes it. */
    fun update(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        when (mode) {
            GizmoEditMode.TRANSLATE -> {
                val dx = if (axis == 1) 0f else slide(x - startX)
                val dy = if (axis == 0) 0f else y - startY
                // Keys carry their handles along as they are; a handle picked alone moves with the pointer.
                place(
                    { px, py -> (px + dx) to (py + dy) },
                    keyHandles = { ox, oy -> ox to oy },
                    handles = { ox, oy -> (ox + dx) to (oy + dy) },
                    snapTime = true,
                )
            }

            GizmoEditMode.SCALE -> {
                val from = hypot(startX - pivotX, startY - pivotY)
                val factor = if (from < MIN_REACH) 1f else hypot(x - pivotX, y - pivotY) / from
                val sx = if (axis == 1) 1f else factor
                val sy = if (axis == 0) 1f else factor
                val stretch = { ox: Float, oy: Float -> ox * sx to oy * sy }
                place(
                    { px, py -> pivotX + (px - pivotX) * sx to pivotY + (py - pivotY) * sy },
                    keyHandles = stretch,
                    handles = stretch,
                    snapTime = false,
                )
            }

            GizmoEditMode.ROTATE -> {
                val angle = atan2(y - pivotY, x - pivotX) - atan2(startY - pivotY, startX - pivotX)
                val cos = cos(angle)
                val sin = sin(angle)
                fun turn(ox: Float, oy: Float) = (ox * cos - oy * sin) to (ox * sin + oy * cos)
                place(
                    { px, py -> turn(px - pivotX, py - pivotY).let { (tx, ty) -> pivotX + tx to pivotY + ty } },
                    keyHandles = ::turn,
                    handles = ::turn,
                    snapTime = false,
                )
            }
        }
    }

    /** How far the pointer went past where the keys stop at the ends of the work area, dropped from its travel. */
    private var overshootX = 0f

    /**
     * The pointer's [travel] along the time the keys follow: held where the first or last key meets an end of the
     * work area, so turning back moves them at once instead of first winding back the overshoot.
     */
    private fun slide(travel: Float): Float {
        if (keys.isEmpty()) return travel
        val least = view.x(0f) - keys.minOf { it.x }
        val most = view.x(controller.workAreaEnd) - keys.maxOf { it.x }
        val slide = travel - overshootX
        if (most < least) return slide
        val held = slide.coerceIn(least, most)
        overshootX += slide - held
        return held
    }

    fun key(key: Int, modifiers: Int): GizmoKeyResult = when {
        HollowKeys.AXIS_X.matches(key, modifiers) -> constrain(0)
        HollowKeys.AXIS_Y.matches(key, modifiers) -> constrain(1)
        key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER -> finish(confirm = true)
        key == GLFW.GLFW_KEY_ESCAPE -> finish(confirm = false)
        else -> GizmoKeyResult.IGNORED
    }

    fun click(button: Int): GizmoKeyResult = finish(confirm = button != GLFW.GLFW_MOUSE_BUTTON_RIGHT)

    private fun constrain(index: Int): GizmoKeyResult {
        axis = if (axis == index) null else index
        update(pointerX, pointerY)
        return GizmoKeyResult.CHANGED
    }

    private fun finish(confirm: Boolean): GizmoKeyResult {
        if (!confirm) restore()
        controller.commitHistoryTransaction()
        return if (confirm) GizmoKeyResult.CONFIRMED else GizmoKeyResult.CANCELLED
    }

    private fun place(
        point: (Float, Float) -> Pair<Float, Float>,
        keyHandles: (Float, Float) -> Pair<Float, Float>,
        handles: (Float, Float) -> Pair<Float, Float>,
        snapTime: Boolean,
    ) {
        val targets = keys.map { start ->
            val (x, y) = point(start.x, start.y)
            val time =
                view.time(x).let { if (snapTime) snapTimelineTime(it) else it }.coerceIn(0f, controller.workAreaEnd)
            start to (time to controller.boundsOf(start.curve).clamp(view.value(y)))
        }
        if (!collides(targets)) {
            targets.forEach { (start, target) ->
                start.keyframe.time = target.first
                start.keyframe.value = target.second
                if (start.keyframe.handleMode != HandleMode.AUTO) {
                    start.keyframe.incoming = start.incoming.moved(TangentSide.INCOMING, keyHandles)
                    start.keyframe.outgoing = start.outgoing.moved(TangentSide.OUTGOING, keyHandles)
                }
            }
            keys.map { it.curve }.distinct().forEach { it.sort() }
        }
        this.handles.forEach { start ->
            val (ox, oy) = handles(start.offsetX, start.offsetY)
            controller.setTangent(
                keyframe = start.pick.keyframe,
                side = start.pick.side,
                tangent = KeyTangent(ox / view.pxPerSec, -oy / view.pxPerValue),
                mode = start.partnerMode,
                timeScale = view.pxPerSec,
                valueScale = view.pxPerValue,
            )
        }
        controller.notifyKeysChanged()
    }

    /** A tangent moved by [offset] in the graph's pixels, kept on its own side of the key. */
    private fun KeyTangent.moved(side: TangentSide, offset: (Float, Float) -> Pair<Float, Float>): KeyTangent {
        val (ox, oy) = offset(time * view.pxPerSec, -value * view.pxPerValue)
        val time = ox / view.pxPerSec
        return KeyTangent(
            if (side == TangentSide.OUTGOING) time.coerceAtLeast(0f) else time.coerceAtMost(0f),
            -oy / view.pxPerValue,
        )
    }

    /** Whether two keys of one curve would share a time, which a curve cannot hold. */
    private fun collides(targets: List<Pair<KeyStart, Pair<Float, Float>>>): Boolean {
        val moved = targets.groupBy({ it.first.curve }, { it.second.first })
        return moved.any { (curve, times) ->
            val still = curve.keyframes.filter { key -> keys.none { it.keyframe === key } }.map { it.time }
            val all = (times + still).sorted()
            all.zipWithNext().any { (a, b) -> abs(b - a) <= ChannelCurve.KEY_TIME_EPSILON }
        }
    }

    private fun restore() {
        keys.forEach { start ->
            start.keyframe.time = start.time
            start.keyframe.value = start.value
            start.keyframe.handleMode = start.handleMode
            start.keyframe.incoming = start.incoming
            start.keyframe.outgoing = start.outgoing
        }
        keys.map { it.curve }.distinct().forEach { it.sort() }
        handles.forEach { start ->
            start.pick.keyframe.handleMode = start.handleMode
            start.pick.keyframe.incoming = start.incoming
            start.pick.keyframe.outgoing = start.outgoing
        }
        controller.notifyKeysChanged()
    }

    /** A picked key as it was, and where it was in the graph's pixels. */
    private class KeyStart(val keyframe: Keyframe, val curve: ChannelCurve, view: CurveView) {
        val time = keyframe.time
        val value = keyframe.value
        val handleMode = keyframe.handleMode
        val incoming = keyframe.incoming
        val outgoing = keyframe.outgoing
        val x = view.x(time)
        val y = view.y(value)
    }

    /** A handle picked on its own as it was: its key's place, its own offset from it and both tangents of the key. */
    private class HandleStart(val pick: HandlePick, curve: ChannelCurve, view: CurveView, bothPicked: Boolean) {
        val handleMode = pick.keyframe.handleMode
        val incoming = pick.keyframe.incoming
        val outgoing = pick.keyframe.outgoing
        val keyX = view.x(pick.keyframe.time)
        val keyY = view.y(pick.keyframe.value)
        private val tangent = curve.effectiveTangents(pick.keyframe).tangent(pick.side)
        val offsetX = tangent.time * view.pxPerSec
        val offsetY = -tangent.value * view.pxPerValue

        /** How the other handle of the key follows: not at all when it is picked too or the key is broken. */
        val partnerMode = when {
            bothPicked || handleMode == HandleMode.FREE -> HandleMode.FREE
            handleMode == HandleMode.MIRRORED -> HandleMode.MIRRORED
            else -> HandleMode.ALIGNED
        }
    }

    companion object {
        /** Under this many pixels from the middle a stretch has nothing to measure by. */
        private const val MIN_REACH = 2f

        /**
         * Starts [mode] on what is picked of the curves the graph can edit, with the pointer at ([x], [y]) in the
         * graph's pixels; null when nothing there is picked.
         */
        fun start(
            controller: TimelineController,
            mode: GizmoEditMode,
            view: CurveView,
            x: Float,
            y: Float,
        ): CurveTransform? {
            fun editable(curve: ChannelCurve?): Boolean {
                val property = curve?.let(controller::propertyOf) ?: return false
                return curve.spec.supportsCurveEditor && curve.isVisible && !controller.isLocked(property)
            }

            val keys = controller.selectedKeyframes.mapNotNull { key ->
                controller.curveOf(key)?.takeIf(::editable)?.let { KeyStart(key, it, view) }
            }
            val handles = controller.selectedHandles.mapNotNull { pick ->
                if (keys.any { it.keyframe === pick.keyframe }) return@mapNotNull null
                val curve = controller.curveOf(pick.keyframe)?.takeIf(::editable) ?: return@mapNotNull null
                val both = controller.selectedHandles.count { it.keyframe === pick.keyframe } > 1
                HandleStart(pick, curve, view, both)
            }
            if (keys.isEmpty() && handles.isEmpty()) return null
            return CurveTransform(controller, mode, view, x, y, keys, handles)
        }

        /** The transform a key starts (G, R or S unless rebound), or null for any other key. */
        fun modeFor(key: Int, modifiers: Int): GizmoEditMode? = when {
            HollowKeys.GRAB.matches(key, modifiers) -> GizmoEditMode.TRANSLATE
            HollowKeys.ROTATE.matches(key, modifiers) -> GizmoEditMode.ROTATE
            HollowKeys.SCALE.matches(key, modifiers) -> GizmoEditMode.SCALE
            else -> null
        }
    }
}

/**
 * The graph's side of a [CurveTransform]: where the pointer is and how the graph lies, so a key can start one, and
 * the one under way, which the graph covers with a layer that takes the pointer until it ends.
 */
internal class CurveTransformHost {
    var active by mutableStateOf<CurveTransform?>(null)
        private set

    /** The pointer in root coordinates, as last seen, and where the graph's content and its view are. */
    var pointerX = Float.NaN
    var pointerY = Float.NaN
    var content = UiRect.Zero
    var viewport = UiRect.Zero
    var view: CurveView? = null

    /** How far the pointer was wrapped around the graph since the transform began, added back to it. */
    private var shiftX = 0f
    private var shiftY = 0f
    val onWrap: (Float, Float) -> Unit = { dx, dy ->
        shiftX += dx
        shiftY += dy
    }

    /** A key on the timeline: the keys of the transform under way, or G, R or S starting one over the graph. */
    fun key(controller: TimelineController, key: Int, modifiers: Int): Boolean {
        active?.let { transform ->
            // Nothing else on the timeline acts while a transform holds the keys.
            if (transform.key(key, modifiers)
                    .let { it == GizmoKeyResult.CONFIRMED || it == GizmoKeyResult.CANCELLED }
            ) {
                active = null
            }
            return true
        }
        if (controller.viewMode != TimelineViewMode.CURVES) return false
        val mode = CurveTransform.modeFor(key, modifiers) ?: return false
        val view = view ?: return false
        if (!viewport.contains(pointerX, pointerY)) return false
        shiftX = 0f
        shiftY = 0f
        active =
            CurveTransform.start(controller, mode, view, pointerX - content.x, pointerY - content.y) ?: return false
        return true
    }

    /** The pointer moved to ([x], [y]) on the graph's content. */
    fun move(x: Float, y: Float) {
        active?.update(x + shiftX, y + shiftY)
    }

    fun click(button: Int) {
        val transform = active ?: return
        transform.click(button)
        active = null
    }
}
