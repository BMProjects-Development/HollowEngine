package ru.hollowhorizon.hollowengine.common.entities.objects

import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.abs

/**
 * Where an object is: a position, a rotation and a scale, either in the world or relative to its parent.
 */
class ObjectPose(
    val position: Vector3d = Vector3d(),
    val rotation: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f),
) {
    /** [local], given relative to this pose, in the space this pose is in. */
    fun compose(local: ObjectPose): ObjectPose {
        val offset = Vector3d(local.position).mul(scale.x.toDouble(), scale.y.toDouble(), scale.z.toDouble())
        rotation.transform(offset)
        return ObjectPose(
            position = offset.add(position),
            rotation = Quaternionf(rotation).mul(local.rotation).normalize(),
            scale = Vector3f(scale).mul(local.scale),
        )
    }

    /** The pose that [compose] would turn into [world]: [world] seen from this pose. */
    fun relativize(world: ObjectPose): ObjectPose {
        val inverse = Quaternionf(rotation).conjugate()
        val offset = Vector3d(world.position).sub(position)
        inverse.transform(offset)
        offset.div(safe(scale.x).toDouble(), safe(scale.y).toDouble(), safe(scale.z).toDouble())
        return ObjectPose(
            position = offset,
            rotation = inverse.mul(world.rotation).normalize(),
            scale = Vector3f(world.scale).div(safe(scale.x), safe(scale.y), safe(scale.z)),
        )
    }

    /** The pose [weight] of the way from [previous] to this one. */
    fun interpolated(previous: ObjectPose, weight: Float): ObjectPose = ObjectPose(
        position = Vector3d(previous.position).lerp(position, weight.toDouble()),
        rotation = Quaternionf(previous.rotation).slerp(rotation, weight),
        scale = Vector3f(previous.scale).lerp(scale, weight),
    )

    private fun safe(value: Float): Float = if (abs(value) < MIN_SCALE) MIN_SCALE else value

    companion object {
        /** No axis is scaled closer to zero than this, or the pose could not be undone. */
        const val MIN_SCALE = 0.001f
    }
}
