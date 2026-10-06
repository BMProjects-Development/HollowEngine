package ru.hollowhorizon.hollowengine.addons.physics.collider

import ru.hollowhorizon.hollowengine.common.colliders.ColliderBox
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeFactory
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderVolume
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** Places the addon's collider shapes, built in Jolt at the size of the box they fill. */
internal object JoltColliderFactory : ColliderShapeFactory {
    override fun place(spec: ColliderShapeSpec, frame: ColliderBox): ColliderVolume? {
        val half = Vec3f(frame.axisX.length().toFloat(), frame.axisY.length().toFloat(), frame.axisZ.length().toFloat())
        return JoltColliderShapes.of(spec, half)?.let { JoltVolume(frame, it) }
    }
}
