package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.scene.ClientSceneContext
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.rigId
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scene.accepts
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Scene-owned spatial state for a rendered model and its rig-added bones. */
internal class ModelRenderScene private constructor(
    private val ownedScene: Scene?,
    parent: SceneObject,
    name: String,
    model: Model,
) : AutoCloseable {
    val instance = ModelSceneInstance.create(parent, name, model)
    val root: SceneObject get() = instance.root
    val clientContext: ClientSceneContext? get() = ownedScene?.context as? ClientSceneContext
    private var imported: Map<SceneObject, Map<String, UUID>> = emptyMap()

    /** Imports migrated rig entries into the same component store scripts and editors use. */
    fun bindComponents(rig: ModelRig) {
        val retained = buildMap {
            for ((node, specs) in sequenceOf(root to rig.attachments) + instance.poseNodesByIndex.values.asSequence().map {
                it to rig.bone(it.poseName)?.attachments.orEmpty()
            }) {
                if (specs.isEmpty()) continue
                val ids = buildMap {
                    for (spec in specs) {
                        if (!node.accepts(spec)) continue
                        val name = spec.rigId
                        val id = imported[node]?.get(name)
                            ?: UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8))
                        val previous = node.component(id)
                        if (previous != null && imported[node]?.get(name) != id) continue
                        if (previous == null) node.attach(spec, id)
                        else if (previous::class == spec::class) node.setComponent(id, spec)
                        else {
                            node.detach(id)
                            node.attach(spec, id)
                        }
                        put(name, id)
                    }
                }
                put(node, ids)
            }
        }
        for ((node, ids) in imported) {
            if (!node.isRemoved) for ((name, id) in ids) {
                if (name !in retained[node].orEmpty()) node.detach(id)
            }
        }
        imported = retained
    }

    fun respecComponents(rig: ModelRig) {
        for ((node, ids) in imported) {
            if (node.isRemoved) continue
            val specs = if (node === root) rig.attachments else rig.bone(node.poseName)?.attachments.orEmpty()
            for (spec in specs) {
                val id = ids[spec.rigId] ?: continue
                val previous = node.component(id)
                if (previous != null && previous::class == spec::class) node.setComponent(id, spec)
            }
        }
    }

    fun prepare(model: Model, rig: ModelRig) {
        if (instance.model !== model) {
            instance.reload(model).forEach { HollowEngine.LOGGER.warn("Model reload removed scene node {}", it) }
        }
        instance.setRigBones(rig).forEach { HollowEngine.LOGGER.warn("Rig rebuild removed scene subtree '{}'", it) }
    }

    fun bindNodes(owner: ModelAttachment, materials: (Material) -> Material): List<RenderModelNode> = buildList {
        for (node in instance.poseNodesByIndex.values) {
            val definition = node.source as NodeDefinition
            val parent = node.poseParent
            node.setModelVisibility(definition.visible && parent?.modelVisible != false, includeChildren = false)
            val binding = RenderModelNode(node, definition, owner, materials)
            if (parent == null) add(binding)
        }
    }

    override fun close() {
        if (ownedScene != null) ownedScene.close()
        else if (!root.isRemoved && !root.scene.isClosed) instance.close()
    }

    companion object {
        fun create(parent: Attachment?, model: Model): ModelRenderScene {
            val nested = parent as? NestedModelAttachment
            val holder = nested?.sceneHolder
            val scene = if (holder == null) Scene(
                ClientSceneContext(),
                parent?.let(::AttachmentSceneTransform),
            ) else null
            try {
                val owner = holder ?: checkNotNull(scene).create("owner")
                return ModelRenderScene(scene, owner, holder?.name ?: "model", model)
            } catch (exception: Exception) {
                try {
                    scene?.close()
                } catch (cleanup: Exception) {
                    if (cleanup !== exception) exception.addSuppressed(cleanup)
                }
                throw exception
            }
        }
    }
}
