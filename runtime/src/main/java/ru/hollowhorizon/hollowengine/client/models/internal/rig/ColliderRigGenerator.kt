package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Hangs a collider around the geometry of every bone that has some, named after the bone. A bone that
 * already has a collider is left as it was, so running it again only fills in what is missing.
 */
object ColliderRigGenerator : RigGenerator {
    const val ID = "hollowengine:rig/colliders_from_geometry"

    /** Bones smaller than this hold a finger or an eye, not something worth aiming at. */
    private const val MIN_SIZE = 0.04f

    override fun generate(model: ModelAttachment, current: ModelRig): ModelRig {
        val taken = current.allAttachments().filter { it.second is ColliderAttachmentSpec }.mapTo(HashSet()) { it.second.id }
        var rig = current
        BoneGeometry.boundsPerBone(model).forEach { (bone, box) ->
            if (box.largestSide < MIN_SIZE) return@forEach
            val existing = rig.bone(bone.name) ?: RigBone.EMPTY
            if (existing.attachments.any { it is ColliderAttachmentSpec }) return@forEach

            val id = freeName(bone.name, taken).also(taken::add)
            val size = box.size
            val collider = ColliderAttachmentSpec(
                id = id,
                offset = box.center,
                size = Vec3f(size.x.coerceAtLeast(MIN_SIZE), size.y.coerceAtLeast(MIN_SIZE), size.z.coerceAtLeast(MIN_SIZE)),
            )
            rig = rig.withBone(bone.name, existing.withAttachment(collider))
        }
        return rig
    }

    private fun freeName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var index = 2
        while ("$base$index" in taken) index++
        return "$base$index"
    }
}
