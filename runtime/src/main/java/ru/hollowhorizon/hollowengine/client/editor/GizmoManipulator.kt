package ru.hollowhorizon.hollowengine.client.editor

import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

/** A world transform value passed between the manipulator and the editor. */
data class GizmoTransformValues(
    val translation: Vec3f,
    val rotation: QuatF,
    val scale: Vec3f,
)

/**
 * How far a drag snaps, by the keys held: Ctrl snaps to the step, Shift with it to the fine step and Alt
 * to the coarse one. Shift also slows the drag down, snapping or not.
 */
enum class GizmoStep {
    FINE,
    NORMAL,
    COARSE;

    companion object {
        fun of(modifiers: Int): GizmoStep? = when {
            modifiers and GLFW.GLFW_MOD_ALT != 0 -> COARSE
            modifiers and GLFW.GLFW_MOD_CONTROL == 0 -> null
            modifiers and GLFW.GLFW_MOD_SHIFT != 0 -> FINE
            else -> NORMAL
        }
    }
}

/**
 * The steps of each [GizmoStep], fine to coarse: [translation] in blocks, [rotation] in degrees and
 * [scale] as a factor of the size the drag started at.
 */
class GizmoSnapping(
    private val translation: DoubleArray,
    private val rotation: DoubleArray,
    private val scale: DoubleArray,
) {
    fun translation(step: GizmoStep): Double = translation[step.ordinal]
    fun rotation(step: GizmoStep): Double = rotation[step.ordinal]
    fun scale(step: GizmoStep): Double = scale[step.ordinal]

    companion object {
        /** Blocks in the world. */
        val WORLD = GizmoSnapping(doubleArrayOf(0.1, 1.0, 4.0), doubleArrayOf(1.0, 5.0, 45.0), doubleArrayOf(0.01, 0.1, 0.5))

        /** Pixels of a model: a sixteenth of a block, a quarter of one for the coarse step. */
        val MODEL = GizmoSnapping(
            doubleArrayOf(1.0 / 64.0, 1.0 / 16.0, 0.25),
            doubleArrayOf(1.0, 15.0, 45.0),
            doubleArrayOf(0.01, 0.1, 0.5),
        )
    }
}

/**
 * A single in-progress drag. Created by [GizmoManipulator.begin] on grab and advanced by
 * [GizmoManipulator.update] on pointer motion. Holds the reference captured at grab time so motion is
 * measured relative to the initial contact point.
 */
class GizmoDrag internal constructor(
    val handleId: GizmoHandleId,
    internal val origin: Vec3,
    internal val axis: Vec3?,
    internal val start: GizmoTransformValues,
) {
    internal var referenceParam = 0.0
    internal var referenceHit: Vec3? = null
    internal var planeNormal: Vec3? = null
    internal var referenceAngle = 0.0
    internal var currentAngle = 0.0
    internal var referenceDistance = 0f
    internal var valid = false

    /** Rotation drag start/current angle (radians) in the handle plane, for the angle-sector readout. */
    val startAngle: Double get() = referenceAngle
    val angle: Double get() = currentAngle

    /** Magnitude of the current change, for the on-screen value label (meters / degrees / factor). */
    var labelValue: Double = 0.0
        internal set
}

/**
 * Screen-to-world manipulation math for the gizmo.
 */
class GizmoManipulator(
    private val projector: GizmoProjector,
    private val snapping: GizmoSnapping = GizmoSnapping.WORLD,
) {
    fun begin(handle: GizmoHandle, values: GizmoTransformValues, pointerX: Float, pointerY: Float): GizmoDrag {
        val axis = handle.worldAxis?.normalize()
        val drag = GizmoDrag(handle.id, handle.worldOrigin, axis, values)
        val ray = projector.screenRay(pointerX, pointerY)
        when (handle.id) {
            GizmoHandleId.AXIS_X, GizmoHandleId.AXIS_Y, GizmoHandleId.AXIS_Z,
            GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z -> {
                if (ray != null && axis != null) {
                    val t = closestParamOnAxis(ray, handle.worldOrigin, axis)
                    if (t != null) {
                        drag.referenceParam = t
                        drag.valid = true
                    }
                }
            }

            GizmoHandleId.PLANE_X, GizmoHandleId.PLANE_Y, GizmoHandleId.PLANE_Z -> {
                if (ray != null && axis != null) {
                    val hit = rayPlane(ray, handle.worldOrigin, axis)
                    if (hit != null) {
                        drag.referenceHit = hit
                        drag.valid = true
                    }
                }
            }

            GizmoHandleId.ROTATE_X, GizmoHandleId.ROTATE_Y, GizmoHandleId.ROTATE_Z -> {
                if (ray != null && axis != null) {
                    val angle = anglePlane(ray, handle.worldOrigin, axis)
                    if (angle != null) {
                        drag.referenceAngle = angle
                        drag.currentAngle = angle
                        drag.valid = true
                    }
                }
            }

            GizmoHandleId.CENTER -> {
                val toCamera = projector.cameraPosition.subtract(handle.worldOrigin)
                val length = toCamera.length()
                if (ray != null && length > 1e-6) {
                    val normal = toCamera.scale(1.0 / length)
                    drag.planeNormal = normal
                    val hit = rayPlane(ray, handle.worldOrigin, normal)
                    if (hit != null) {
                        drag.referenceHit = hit
                        drag.valid = true
                    }
                }
            }

            GizmoHandleId.SCALE_UNIFORM -> {
                val originScreen = projector.project(handle.worldOrigin)
                if (originScreen != null) {
                    drag.referenceDistance = distance(originScreen.x, originScreen.y, pointerX, pointerY)
                    drag.valid = drag.referenceDistance > 1e-3f
                }
            }
        }
        return drag
    }

    /** Advances [drag] to the pointer and returns the new transform, or null if nothing changed. */
    fun update(
        drag: GizmoDrag,
        pointerX: Float,
        pointerY: Float,
        modifiers: Int,
    ): GizmoTransformValues? {
        if (!drag.valid) return null
        val step = GizmoStep.of(modifiers)
        val speed = if (modifiers and GLFW.GLFW_MOD_SHIFT != 0) 0.1 else 1.0

        return when (drag.handleId) {
            GizmoHandleId.AXIS_X, GizmoHandleId.AXIS_Y, GizmoHandleId.AXIS_Z ->
                updateAxis(drag, pointerX, pointerY, speed, step)

            GizmoHandleId.PLANE_X, GizmoHandleId.PLANE_Y, GizmoHandleId.PLANE_Z ->
                updatePlane(drag, pointerX, pointerY, speed, step)

            GizmoHandleId.ROTATE_X, GizmoHandleId.ROTATE_Y, GizmoHandleId.ROTATE_Z ->
                updateRotate(drag, pointerX, pointerY, speed, step)

            GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z ->
                updateScaleAxis(drag, pointerX, pointerY, speed, step)

            GizmoHandleId.CENTER ->
                updateViewPlane(drag, pointerX, pointerY, speed, step)

            GizmoHandleId.SCALE_UNIFORM ->
                updateScale(drag, pointerX, pointerY, speed, step)
        }
    }

    private fun updateViewPlane(drag: GizmoDrag, x: Float, y: Float, speed: Double, step: GizmoStep?): GizmoTransformValues? {
        val normal = drag.planeNormal ?: return null
        val reference = drag.referenceHit ?: return null
        val ray = projector.screenRay(x, y) ?: return null
        val hit = rayPlane(ray, drag.origin, normal) ?: return null
        var dx = (hit.x - reference.x) * speed
        var dy = (hit.y - reference.y) * speed
        var dz = (hit.z - reference.z) * speed
        if (step != null) {
            val tick = snapping.translation(step)
            dx = round(dx / tick) * tick
            dy = round(dy / tick) * tick
            dz = round(dz / tick) * tick
        }
        drag.labelValue = sqrt(dx * dx + dy * dy + dz * dz)
        return drag.start.copy(translation = drag.start.translation + Vec3f(dx.toFloat(), dy.toFloat(), dz.toFloat()))
    }

    private fun updateAxis(drag: GizmoDrag, x: Float, y: Float, speed: Double, step: GizmoStep?): GizmoTransformValues? {
        val axis = drag.axis ?: return null
        val ray = projector.screenRay(x, y) ?: return null
        val t = closestParamOnAxis(ray, drag.origin, axis) ?: return null
        var delta = (t - drag.referenceParam) * speed
        if (step != null) delta = round(delta / snapping.translation(step)) * snapping.translation(step)
        drag.labelValue = delta
        val offset = Vec3f((axis.x * delta).toFloat(), (axis.y * delta).toFloat(), (axis.z * delta).toFloat())
        return drag.start.copy(translation = drag.start.translation + offset)
    }

    private fun updatePlane(drag: GizmoDrag, x: Float, y: Float, speed: Double, step: GizmoStep?): GizmoTransformValues? {
        val axis = drag.axis ?: return null
        val reference = drag.referenceHit ?: return null
        val ray = projector.screenRay(x, y) ?: return null
        val hit = rayPlane(ray, drag.origin, axis) ?: return null
        var dx = (hit.x - reference.x) * speed
        var dy = (hit.y - reference.y) * speed
        var dz = (hit.z - reference.z) * speed
        if (step != null) {
            val tick = snapping.translation(step)
            dx = round(dx / tick) * tick
            dy = round(dy / tick) * tick
            dz = round(dz / tick) * tick
        }
        drag.labelValue = sqrt(dx * dx + dy * dy + dz * dz)
        val offset = Vec3f(dx.toFloat(), dy.toFloat(), dz.toFloat())
        return drag.start.copy(translation = drag.start.translation + offset)
    }

    private fun updateRotate(drag: GizmoDrag, x: Float, y: Float, speed: Double, step: GizmoStep?): GizmoTransformValues? {
        val axis = drag.axis ?: return null
        val ray = projector.screenRay(x, y) ?: return null
        val angle = anglePlane(ray, drag.origin, axis) ?: return null
        drag.currentAngle = drag.referenceAngle + shortestAngle(angle - drag.referenceAngle)
        var deltaDeg = Math.toDegrees(shortestAngle(angle - drag.referenceAngle)) * speed
        if (step != null) deltaDeg = round(deltaDeg / snapping.rotation(step)) * snapping.rotation(step)
        drag.labelValue = deltaDeg
        val axisF = Vec3f(axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat())
        val delta = QuatF(deltaDeg.toFloat().deg, axisF)
        val rotation = (delta * drag.start.rotation).normed()
        return drag.start.copy(rotation = rotation)
    }

    private fun updateScale(drag: GizmoDrag, x: Float, y: Float, speed: Double, step: GizmoStep?): GizmoTransformValues? {
        val originScreen = projector.project(drag.origin) ?: return null
        val current = distance(originScreen.x, originScreen.y, x, y)
        if (drag.referenceDistance <= 1e-3f) return null
        var factor = (current / drag.referenceDistance).toDouble()
        factor = 1.0 + (factor - 1.0) * speed
        if (step != null) factor = (round(factor / snapping.scale(step)) * snapping.scale(step)).coerceAtLeast(snapping.scale(step))
        factor = factor.coerceIn(0.01, 100.0)
        drag.labelValue = factor
        val scale = Vec3f(
            (drag.start.scale.x * factor).toFloat(),
            (drag.start.scale.y * factor).toFloat(),
            (drag.start.scale.z * factor).toFloat(),
        )
        return drag.start.copy(scale = scale)
    }

    private fun updateScaleAxis(drag: GizmoDrag, x: Float, y: Float, speed: Double, step: GizmoStep?): GizmoTransformValues? {
        val axis = drag.axis ?: return null
        if (abs(drag.referenceParam) < 1e-4) return null
        val ray = projector.screenRay(x, y) ?: return null
        val t = closestParamOnAxis(ray, drag.origin, axis) ?: return null
        var factor = t / drag.referenceParam
        factor = 1.0 + (factor - 1.0) * speed
        if (step != null) factor = round(factor / snapping.scale(step)) * snapping.scale(step)
        factor = factor.coerceIn(0.01, 100.0)
        drag.labelValue = factor
        val s = drag.start.scale
        val scale = when (drag.handleId) {
            GizmoHandleId.SCALE_X -> Vec3f((s.x * factor).toFloat(), s.y, s.z)
            GizmoHandleId.SCALE_Y -> Vec3f(s.x, (s.y * factor).toFloat(), s.z)
            GizmoHandleId.SCALE_Z -> Vec3f(s.x, s.y, (s.z * factor).toFloat())
            else -> return null
        }
        return drag.start.copy(scale = scale)
    }

    private fun closestParamOnAxis(ray: WorldRay, origin: Vec3, axis: Vec3): Double? {
        val d1 = ray.direction
        val r = ray.origin.subtract(origin)
        val b = d1.dot(axis)
        val denom = 1.0 - b * b
        if (abs(denom) < 1e-6) return null
        val d = d1.dot(r)
        val e = axis.dot(r)
        return (e - b * d) / denom
    }

    private fun rayPlane(ray: WorldRay, origin: Vec3, normal: Vec3): Vec3? {
        val denom = ray.direction.dot(normal)
        if (abs(denom) < 1e-6) return null
        val t = origin.subtract(ray.origin).dot(normal) / denom
        if (t < 0) return null
        return ray.origin.add(ray.direction.scale(t))
    }

    private fun anglePlane(ray: WorldRay, origin: Vec3, normal: Vec3): Double? {
        val hit = rayPlane(ray, origin, normal) ?: return null
        val radial = hit.subtract(origin)
        val u = perpendicular(normal)
        val v = normal.cross(u)
        val cu = radial.dot(u)
        val cv = radial.dot(v)
        if (abs(cu) < 1e-9 && abs(cv) < 1e-9) return null
        return atan2(cv, cu)
    }

    private fun perpendicular(n: Vec3): Vec3 {
        val reference = if (abs(n.y) < 0.99) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        return n.cross(reference).normalize()
    }

    private fun shortestAngle(angle: Double): Double {
        var a = angle
        while (a > Math.PI) a -= Math.PI * 2
        while (a < -Math.PI) a += Math.PI * 2
        return a
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }


    companion object {
        /** The one the world overlay uses. */
        val World = GizmoManipulator(WorldToScreenProjector)
    }
}
