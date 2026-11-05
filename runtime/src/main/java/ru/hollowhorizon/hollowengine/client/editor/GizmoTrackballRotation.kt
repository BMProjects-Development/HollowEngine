package ru.hollowhorizon.hollowengine.client.editor

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import kotlin.math.hypot
import kotlin.math.round

/** Free rotation about the camera's right and up axes, measured from the original pointer position. */
internal class GizmoTrackballRotation(
    projector: GizmoProjector,
    private val start: GizmoTransformValues,
    private val startX: Float,
    private val startY: Float,
    private val snapping: GizmoSnapping,
) {
    private val axes = projector.viewAxes()
    private val degreesPerPixel = 360f / minOf(projector.width, projector.height)

    fun update(x: Float, y: Float, modifiers: Int): GizmoTransformValues? {
        val (right, up) = axes ?: return null
        val dx = x - startX
        val dy = y - startY
        val distance = hypot(dx, dy)
        if (distance <= 1e-6f) return start
        val axis = up.scale(dx.toDouble()).add(right.scale(dy.toDouble())).normalize()
        val speed = if (modifiers and GLFW.GLFW_MOD_SHIFT != 0) 0.1f else 1f
        var angle = distance * degreesPerPixel * speed
        GizmoStep.of(modifiers)?.let { step ->
            val tick = snapping.rotation(step).toFloat()
            angle = round(angle / tick) * tick
        }
        val rotation = QuatF(angle.deg, Vec3f(axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat()))
        return start.copy(rotation = (rotation * start.rotation).normed())
    }
}
