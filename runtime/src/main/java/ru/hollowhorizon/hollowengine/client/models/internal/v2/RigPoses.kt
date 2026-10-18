package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Moves every bone of [roots] that [rig] poses by its pose, over whatever the animator left in it. Run
 * after the animator and before the matrices are worked out, the same way wherever a model is posed.
 */
fun applyRigPose(roots: List<RuntimeNode>, rig: ModelRig) {
    if (rig.bones.values.none { it.pose != null }) return
    roots.forEach { root ->
        root.walk().forEach { node ->
            val pose = rig.bone(node.name)?.pose?.takeUnless { it.isIdentity } ?: return@forEach
            val local = node.transform
            local.setCompositionOf(
                Vec3f(local.translation) + pose.position,
                MutableQuatF(local.rotation).mul(pose.rotation).norm(),
                Vec3f(local.scale) * pose.scale,
            )
        }
    }
}
