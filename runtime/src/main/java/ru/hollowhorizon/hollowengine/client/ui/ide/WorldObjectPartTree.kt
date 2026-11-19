package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.NestedModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RenderModelNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.RigModelInfo
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.rigText
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.title
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.treeLabel
import ru.hollowhorizon.hollowengine.client.vfx.played
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.rigId
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeTypes
import java.util.*

/** A model hung as [attachment] on [bone] of the model before it, null [bone] being that model itself. */
internal data class NestStep(val bone: String?, val attachment: String)

/**
 * Where a part of an object is: through the models nested on the way to it, then a bone of the last one,
 * an attachment on that bone, or with neither, the model itself.
 */
internal data class PartPath(
    val steps: List<NestStep> = emptyList(),
    val bone: String? = null,
    val attachment: String? = null,
    val material: String? = null,
    val effectNode: String? = null,
    val componentId: UUID? = null,
    val sceneNodeId: UUID? = null,
) {
    fun encode(): String = buildString {
        sceneNodeId?.let { append('n').append(it).append(SEPARATOR) }
        componentId?.let { append('c').append(it).append(SEPARATOR) }
        steps.forEach { step ->
            append('s').append(step.bone.orEmpty()).append(FIELD).append(step.attachment).append(SEPARATOR)
        }
        bone?.let { append('b').append(it).append(SEPARATOR) }
        attachment?.let { append('a').append(it).append(SEPARATOR) }
        material?.let { append('m').append(it).append(SEPARATOR) }
        effectNode?.let { append('v').append(it).append(SEPARATOR) }
    }

    companion object {
        private const val SEPARATOR = '\u001F'
        private const val FIELD = '\u001E'

        fun decode(text: String): PartPath? {
            val steps = ArrayList<NestStep>()
            var bone: String? = null
            var attachment: String? = null
            var material: String? = null
            var effectNode: String? = null
            var componentId: UUID? = null
            var sceneNodeId: UUID? = null
            text.split(SEPARATOR).filter { it.isNotEmpty() }.forEach { token ->
                val value = token.substring(1)
                when (token[0]) {
                    's' -> {
                        val (holder, id) = value.split(FIELD).takeIf { it.size == 2 } ?: return null
                        steps += NestStep(holder.ifEmpty { null }, id)
                    }
                    'b' -> bone = value
                    'a' -> attachment = value
                    'm' -> material = value
                    'v' -> effectNode = value
                    'c' -> componentId = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
                    'n' -> sceneNodeId = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
                    else -> return null
                }
            }
            return PartPath(steps, bone, attachment, material, effectNode, componentId, sceneNodeId)
        }
    }
}

internal enum class PartKind { MODEL, BONE, ATTACHMENT, MATERIALS, MATERIAL, EFFECT_NODE, COMPONENT, SCENE_NODE }

/** One part of an object as the scene lists it; [inherited] marks an attachment that comes from a rig file. */
internal data class PartNode(
    val path: PartPath,
    val label: String,
    val icon: String,
    val kind: PartKind,
    val children: List<PartNode>,
    val inherited: Boolean = false,
)

/** An object's model and everything on it, read off the drawn instance. */
internal data class ObjectParts(
    val model: Model?,
    val components: Map<UUID, Any>,
    val roots: List<PartNode>,
    val bones: Map<List<NestStep>, List<String>>,
    val models: Map<List<NestStep>, RigModelInfo>,
    val loading: Boolean,
    val sceneComponents: Map<UUID, Map<UUID, Any>> = emptyMap(),
) {
    fun component(path: PartPath): Any? {
        val id = path.componentId ?: return null
        return if (path.sceneNodeId == null) components[id] else sceneComponents[path.sceneNodeId]?.get(id)
    }

    fun find(path: PartPath): PartNode? {
        fun search(node: PartNode): PartNode? =
            if (node.path == path) node else node.children.firstNotNullOfOrNull(::search)
        return roots.firstNotNullOfOrNull(::search)
    }
}

/** A part of one object. */
internal data class PartRef(val entityId: Int, val uuid: UUID, val path: PartPath)

internal object PartIcons {
    const val MODEL = "hollowengine:textures/gui/icons/files/rig.svg"
    const val BONE = "hollowengine:textures/gui/icons/graph.svg"
    const val MESH = "hollowengine:textures/gui/icons/box.svg"
    const val EFFECT = "hollowengine:textures/gui/icons/files/effect.svg"
    const val ATTACHMENT = "hollowengine:textures/gui/icons/link.svg"
    const val MATERIALS = "hollowengine:textures/gui/icons/files/image.svg"
    const val MATERIAL = "hollowengine:textures/gui/icons/file_image.svg"
    const val ADD = "hollowengine:textures/gui/icons/add.svg"
    const val REMOVE = "hollowengine:textures/gui/icons/remove.svg"
    const val VISIBLE = "hollowengine:textures/gui/icons/visible.svg"
    const val INVISIBLE = "hollowengine:textures/gui/icons/invisible.svg"
}

/** Reads one object's parts off its drawn model, remembering the bones and materials of every model on the way. */
internal class PartsReader {
    private val sceneComponents = LinkedHashMap<UUID, Map<UUID, Any>>()
    private val bones = HashMap<List<NestStep>, List<String>>()
    private val models = HashMap<List<NestStep>, RigModelInfo>()
    private var loading = false

    fun read(model: Model?, instance: ModelAttachment?, components: Map<UUID, Any>, scene: SceneObject? = null): ObjectParts {
        val roots = buildList {
            if (model != null) add(PartNode(
                PartPath(),
                modelTitle(model.model),
                PartIcons.MODEL,
                PartKind.MODEL,
                scope(instance, model.rig, emptyList()),
            ))
            addAll(componentRows(components))
            scene?.children?.mapNotNullTo(this, ::sceneNode)
        }
        return ObjectParts(model, components, roots, bones, models, loading, sceneComponents)
    }

    private fun componentRows(components: Map<UUID, Any>, nodeId: UUID? = null): List<PartNode> =
        components.map { (id, value) ->
            val path = PartPath(componentId = id, sceneNodeId = nodeId)
            PartNode(
                path,
                componentLabel(value),
                componentIcon(value),
                PartKind.COMPONENT,
                (value as? VfxComponent)?.played()?.nodes.orEmpty().map { effectNode(it, path) },
            )
        }

    private fun sceneNode(node: SceneObject): PartNode? {
        val id = node.createdId ?: return null
        val components = node.components
        if (components.isNotEmpty()) sceneComponents[id] = components
        return PartNode(
            PartPath(sceneNodeId = id), node.name, PartIcons.BONE, PartKind.SCENE_NODE,
            componentRows(components, id) + node.children.mapNotNull(::sceneNode),
        )
    }

    /**
     * What a model shows: what hangs on the model itself, its bones, then its materials. [own] is the rig
     * this object gives it.
     */
    private fun scope(model: ModelAttachment?, own: ModelRig, steps: List<NestStep>): List<PartNode> {
        if (model == null || model.location != null && model.nodes.isEmpty()) {
            loading = true
            return emptyList()
        }
        val nodes = model.nodes.flatMap { it.walk() }
        val materials =
            (model.materials.map { it.name } + model.rig.materials.keys).filter { it.isNotEmpty() }.distinct().sorted()
        bones[steps] = nodes.map { it.name }
        models[steps] = RigModelInfo(
            materials,
            nodes.filter { it.definition.mesh != null }.mapTo(HashSet()) { it.name },
            nodes.filter { it.supportsMeshExtrusion }.mapTo(HashSet()) { it.name },
        )

        val materialRows = materials.map { name ->
            PartNode(
                PartPath(steps, material = name),
                name,
                PartIcons.MATERIAL,
                PartKind.MATERIAL,
                emptyList(),
                inherited = name !in own.materials
            )
        }
        val materialGroup = PartNode(
            PartPath(steps, material = ""),
            rigText("materials"),
            PartIcons.MATERIALS,
            PartKind.MATERIALS,
            materialRows
        )
        return attachments(model.modelRoot, null, model.rig, own, steps) + model.nodes.map {
            bone(
                it,
                model.rig,
                own,
                steps
            )
        } + listOfNotNull(materialGroup.takeIf { materialRows.isNotEmpty() })
    }

    private fun bone(node: RenderModelNode, rig: ModelRig, own: ModelRig, steps: List<NestStep>): PartNode {
        val hidden = rig.bone(node.name)?.hidden == true
        return PartNode(
            path = PartPath(steps, node.name),
            label = node.name,
            icon = when {
                hidden -> PartIcons.INVISIBLE
                node.definition.mesh != null -> PartIcons.MESH
                else -> PartIcons.BONE
            },
            kind = PartKind.BONE,
            children = attachments(node, node.name, rig, own, steps) + node.children.map { bone(it, rig, own, steps) },
        )
    }

    private fun attachments(
        holder: RenderModelNode?,
        bone: String?,
        rig: ModelRig,
        own: ModelRig,
        steps: List<NestStep>,
    ): List<PartNode> = rig.holder(bone).attachments.map { spec ->
        val path = PartPath(steps, bone, spec.rigId)
        attachmentNode(spec, path, holder, own.holder(bone).attachment(spec.rigId) == null)
    }

    private fun attachmentNode(
        spec: Any,
        path: PartPath,
        holder: RenderModelNode? = null,
        inherited: Boolean = false,
    ): PartNode {
        val children = when (spec) {
            is ModelAttachmentSpec -> {
                val nested = holder?.attachments?.filterIsInstance<NestedModelAttachment>()
                    ?.firstOrNull { it.spec.id == spec.id }
                scope(nested?.model, spec.rig, path.steps + NestStep(path.bone, spec.id))
            }

            is VfxComponent -> spec.played()?.nodes.orEmpty().map { effectNode(it, path) }
            else -> emptyList()
        }
        return PartNode(
            path = path,
            label = attachmentLabel(spec),
            icon = componentIcon(spec),
            kind = PartKind.ATTACHMENT,
            children = children,
            inherited = inherited,
        )
    }

    private fun effectNode(node: VfxNodeSpec, attachment: PartPath): PartNode = PartNode(
        path = attachment.copy(effectNode = node.id),
        label = node.treeLabel(),
        icon = VfxNodeTypes.of(node)?.icon ?: PartIcons.EFFECT,
        kind = PartKind.EFFECT_NODE,
        children = node.children.map { effectNode(it, attachment) },
    )

    private fun modelTitle(path: String): String = fileName(path) ?: rigText("model")
}

internal fun attachmentLabel(spec: Any): String = when (spec) {
    is ModelAttachmentSpec -> fileName(spec.model) ?: spec.id
    is VfxComponent -> fileName(spec.effect)?.let { if (spec.ownEffect != null) "$it *" else it }
        ?: spec.id
    else -> "${RigAttachmentTypes.of(spec).title()} (${spec.rigId})"
}

private fun fileName(path: String): String? = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { null }
