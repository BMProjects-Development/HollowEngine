package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.colliders.BoxShapeSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * Picks a shape for geometry a collider is fitted around: [corners] are corners of every piece of it, in the
 * bone's space, and [box] the surrounding box all, which collider fills. Null leaves choice to others.
 */
fun interface ColliderFitter {
    fun fit(corners: List<Vec3f>, box: BoneBounds): ColliderShapeSpec?
}

/** Who picks collider shapes for geometry; with none that has an opinion, a collider is its whole box. */
object ColliderFitters {
    val point = ExtensionPoints.create<ColliderFitter>("hollowengine:rig/collider_fitters".rl)

    fun register(id: String, fitter: ColliderFitter): ExtensionHandle = point.register(id.rl, fitter)

    fun fit(corners: List<Vec3f>, box: BoneBounds): ColliderShapeSpec =
        point.extensions.firstNotNullOfOrNull { it.fit(corners, box) } ?: BoxShapeSpec
}

/**
 * Hangs a collider around the geometry of every bone that has some, named after the bone, in the shape that fits
 * it best of those this game knows.
 */
object ColliderRigGenerator : RigGenerator {
    const val ID = "hollowengine:rig/colliders_from_geometry"

    /** Bones smaller than this hold a finger or an eye, not something worth aiming at. */
    private const val MIN_SIZE = 0.04f

    override fun generate(model: ModelAttachment, current: ModelRig): ModelRig {
        val taken = current.allAttachments().filter { it.second is ColliderAttachmentSpec }.mapTo(HashSet()) { it.second.id }
        var rig = current
        BoneGeometry.cornersPerBone(model).forEach { (bone, corners) ->
            val existing = rig.bone(bone.name) ?: RigBone.EMPTY
            if (existing.attachments.any { it is ColliderAttachmentSpec }) return@forEach
            val collider = fit(freeName(bone.name, taken), corners) ?: return@forEach
            taken += collider.id
            rig = rig.withBone(bone.name, existing.withAttachment(collider))
        }
        return rig
    }

    /** A collider named [id] around [corners], in the shape that fits them best; null for geometry too small to aim at. */
    fun fit(id: String, corners: List<Vec3f>): ColliderAttachmentSpec? {
        if (corners.isEmpty()) return null
        val box = BoneBounds.around(corners)
        if (box.largestSide < MIN_SIZE) return null
        val size = box.size
        return ColliderAttachmentSpec(
            id = id,
            offset = box.center,
            size = Vec3f(size.x.coerceAtLeast(MIN_SIZE), size.y.coerceAtLeast(MIN_SIZE), size.z.coerceAtLeast(MIN_SIZE)),
            shape = ColliderFitters.fit(corners, box),
        )
    }

    fun freeName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var index = 2
        while ("$base$index" in taken) index++
        return "$base$index"
    }
}
