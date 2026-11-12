package ru.hollowhorizon.hollowengine.client.camera

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.PI
import kotlin.math.tan

/** Where the camera stands and looks: [rotation] is pitch, yaw and roll in degrees, as F3 shows them. */
data class CameraPose(
    val position: Vec3f = Vec3f.ZERO,
    val rotation: Vec3f = Vec3f.ZERO,
    val fov: Float = 70f,
) {
    val pitch: Float get() = rotation.x
    val yaw: Float get() = rotation.y
    val roll: Float get() = rotation.z

    fun orientation(): Quaternionf =
        Quaternionf().rotationYXZ(PI.toFloat() - yaw * RADIANS, -pitch * RADIANS, roll * RADIANS)

    fun direction(x: Float, y: Float, z: Float): Vec3f {
        val vector = Vector3f(x, y, z).rotate(orientation())
        return Vec3f(vector.x, vector.y, vector.z)
    }

    val halfVerticalExtent: Float get() = tan(fov.coerceIn(1f, 179f) * RADIANS * 0.5f)

    /** World-space ray through normalized viewport coordinates, with Y increasing down the screen. */
    fun viewportRay(x: Float, y: Float, aspect: Float): Vec3f =
        direction((2f * x - 1f) * aspect * halfVerticalExtent, (1f - 2f * y) * halfVerticalExtent, -1f).normed()
}

private const val RADIANS = (PI / 180).toFloat()
