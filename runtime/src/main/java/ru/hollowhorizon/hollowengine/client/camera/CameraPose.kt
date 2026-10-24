package ru.hollowhorizon.hollowengine.client.camera

import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** Where the camera stands and looks: [rotation] is pitch, yaw and roll in degrees, as F3 shows them. */
data class CameraPose(
    val position: Vec3f = Vec3f.ZERO,
    val rotation: Vec3f = Vec3f.ZERO,
    val fov: Float = 70f,
) {
    val pitch: Float get() = rotation.x
    val yaw: Float get() = rotation.y
    val roll: Float get() = rotation.z
}
