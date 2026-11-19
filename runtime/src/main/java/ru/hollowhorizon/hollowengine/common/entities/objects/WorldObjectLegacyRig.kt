package ru.hollowhorizon.hollowengine.common.entities.objects

import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.models.legacyPlacementNode
import ru.hollowhorizon.hollowengine.common.models.rigId
import ru.hollowhorizon.hollowengine.common.scene.SceneComponentData
import ru.hollowhorizon.hollowengine.common.scene.hasIdentityPose
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.scene.accepts

/** A saved blank model used to be required for root attachments. Lift it when its transform is redundant. */
internal fun WorldObjectEntity.migrateStandaloneRig(components: List<Any>, modelPose: TrsTransformF?): List<Any> {
    val model = components.filterIsInstance<Model>().singleOrNull() ?: return components
    if (!model.isEmpty || model.rig.materials.isNotEmpty()) return components
    if (modelPose != null && !modelPose.hasIdentityPose()) return components
    val rig = model.rig
    val placed = rig.bones.map { (name, bone) ->
        val origin = bone.origin ?: return components
        val spec = bone.attachments.singleOrNull() ?: return components
        val generatedPlacement = name == legacyPlacementNode(null, spec.rigId)
        val droppedDecal = spec is Decal && name == spec.id
        if (origin.parent != null || !(generatedPlacement || droppedDecal) || bone.hidden ||
            bone.alias != null || bone.pose != null || bone.material != null
        ) return components
        Triple(name, origin, spec)
    }
    val specs = rig.attachments + placed.map { it.third }
    if (specs.isEmpty() || specs.any { !SceneComponentData.synced(it) }) return components
    val owner = sceneObject
    if (specs.any { !owner.accepts(it) }) return components
    if (placed.any { (name) -> owner.children.any { it.name == name } }) return components
    rig.attachments.forEach { owner.attach(it) }
    for ((name, origin, spec) in placed) {
        val child = owner.create(name)
        child.localTransform.set(origin.localTransform())
        child.attach(spec)
    }
    return components.filterNot { it === model }
}
