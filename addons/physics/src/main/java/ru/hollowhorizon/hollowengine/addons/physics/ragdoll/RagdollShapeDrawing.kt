package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import ru.hollowhorizon.hollowengine.addons.physics.collider.JoltColliderShapes
import ru.hollowhorizon.hollowengine.addons.physics.rotated
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Draws one body where it currently is.
 */
fun DebugLines.Batch.shape(shape: RagdollShape, origin: Vec3f, orientation: QuatF, color: Int) {
    shape.parts.forEach { part ->
        val centre = origin + part.center.rotated(orientation)
        val rotation = MutableQuatF(orientation).mul(part.rotation).norm()
        JoltColliderShapes.outline(part.shape, part.halfExtents).forEach { (start, end) ->
            line(centre + start.rotated(rotation), centre + end.rotated(rotation), color)
        }
    }
}
