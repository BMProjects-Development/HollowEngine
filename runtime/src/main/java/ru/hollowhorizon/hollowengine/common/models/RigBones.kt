package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz

@Serializable
data class RigBoneOrigin(
    val parent: String? = null,
    val offset: Vec3f = Vec3f.ZERO,
    val rotation: Vec3f = Vec3f.ZERO,
) {
    fun localTransform(): TrsTransformF = TrsTransformF().setCompositionOf(offset, eulerRotationXyz(rotation), Vec3f.ONES)
}

/** The bones the rig adds, by name. */
val ModelRig.addedBones: Map<String, RigBoneOrigin>
    get() = bones.mapNotNull { (name, bone) -> bone.origin?.let { name to it } }.toMap()

/** A name for a new bone that neither the model's [existing] bones nor the rig's own take. */
fun ModelRig.freeBoneName(existing: Collection<String>, base: String = "bone"): String {
    val taken = existing.toHashSet() + bones.keys
    var index = 1
    while ("${base}_$index" in taken) index++
    return "${base}_$index"
}

/** This rig with a new bone [name] added under [parent], null being the model itself. */
fun ModelRig.withAddedBone(name: String, parent: String?): ModelRig =
    withBone(name, RigBone(origin = RigBoneOrigin(parent = parent)))

/** This rig without the added bone [name] and the added bones under it, with what hangs on them. */
fun ModelRig.withoutAddedBone(name: String): ModelRig {
    val added = addedBones
    val gone = HashSet<String>()
    fun collect(bone: String) {
        if (!gone.add(bone)) return
        added.filterValues { it.parent == bone }.keys.forEach(::collect)
    }
    collect(name)
    return copy(bones = bones - gone)
}

/** This rig with the added bone [from] called [to], the bones added under it following it. */
fun ModelRig.withAddedBoneRenamed(from: String, to: String): ModelRig {
    if (to.isBlank() || to in bones || from !in bones) return this
    val renamed = LinkedHashMap<String, RigBone>()
    bones.forEach { (name, bone) ->
        val moved = bone.origin?.takeIf { it.parent == from }?.let { bone.copy(origin = it.copy(parent = to)) } ?: bone
        renamed[if (name == from) to else name] = moved
    }
    return copy(bones = renamed)
}
