package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

internal fun SceneObject.applyRigPose(pose: RigPose) {
    if (!acceptsPose || pose.isIdentity) return
    val local = localTransform
    local.setCompositionOf(
        Vec3f(local.translation) + pose.position,
        MutableQuatF(local.rotation).mul(pose.rotation).norm(),
        Vec3f(local.scale) * pose.scale,
    )
}
