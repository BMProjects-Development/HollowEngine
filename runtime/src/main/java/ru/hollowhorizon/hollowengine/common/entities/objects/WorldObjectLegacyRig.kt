package ru.hollowhorizon.hollowengine.common.entities.objects

import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.models.legacyPlacementNode
import ru.hollowhorizon.hollowengine.common.models.rigId
import ru.hollowhorizon.hollowengine.common.scene.SceneComponentData
import ru.hollowhorizon.hollowengine.common.scene.accepts

/** A saved blank model used to be required for root attachments. Lift it when its transform is redundant. */
internal fun WorldObjectEntity.migrateStandaloneRig(components: List<Any>): List<Any> {
    val model = components.filterIsInstance<Model>().singleOrNull() ?: return components
    if (!model.isEmpty || model.rig.materials.isNotEmpty()) return components
    val transform = components.filterIsInstance<TransformComponent>().firstOrNull()
    if (transform != null && transform != TransformComponent()) return components
    val rig = model.rig
    val placed = rig.bones.map { (name, bone) ->
        val origin = bone.origin ?: return components
        val spec = bone.attachments.singleOrNull() ?: return components
        if (origin.parent != null || name != legacyPlacementNode(null, spec.rigId) || bone.hidden ||
            bone.pose != null || bone.material != null
        ) return components
        Triple(name, origin, spec)
    }
    val specs = rig.attachments + placed.map { it.third }
    if (specs.isEmpty() || specs.any { !SceneComponentData.synced(it) }) return components
    val owner = sceneObject
    if (specs.any { !owner.accepts(it) }) return components
    rig.attachments.forEach { owner.attach(it) }
    for ((name, origin, spec) in placed) {
        val child = owner.create(name)
        child.localTransform.set(origin.localTransform())
        child.attach(spec)
    }
    return components.filterNot { it === model }
}
