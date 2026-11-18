package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scene.SceneObject

/** Scene-owned spatial state for a rendered model and its rig-added bones. */
internal class ModelRenderScene private constructor(
    private val ownedScene: Scene?,
    parent: SceneObject,
    name: String,
    model: Model,
) : AutoCloseable {
    val instance = ModelSceneInstance.create(parent, name, model)
    val root: SceneObject get() = instance.root

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
                object : SceneContext { override val side = LogicalSide.CLIENT },
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
