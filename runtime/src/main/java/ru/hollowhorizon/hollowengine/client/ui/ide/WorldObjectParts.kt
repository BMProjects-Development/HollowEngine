package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.AttachmentGizmo
import ru.hollowhorizon.hollowengine.client.editor.BoneGizmo
import ru.hollowhorizon.hollowengine.client.editor.PartGizmo
import ru.hollowhorizon.hollowengine.client.editor.WorldHistory
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.NestedModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstanceOrNull
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorSelection
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.LocalEditorBones
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ROOT_COMPONENT_ID
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelOrNull
import ru.hollowhorizon.hollowengine.common.attachments.binding.transformOrNull
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.attachments.sync.EntityStateSync
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.PlacedAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.vfx.VfxBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeTypes
import java.util.*

/**
 * Parts of world objects in the scene window: an object's model, its bones, what hangs on them, the nodes
 * of the effects among it, and the same again for every model hung on it.
 */
@ClientOnly
internal object WorldObjectParts {
    private const val SOURCE = "world-object-parts"
    private const val ROW = '\u001D'
    private const val MAX_SESSIONS = 32

    var selected by mutableStateOf<PartRef?>(null)
        private set

    /** The rig sessions of the objects edited lately, so each keeps its undo history while another is worked on. */
    private val sessions = LinkedHashMap<Int, EntityRigEditing>()

    /** Which sections of an effect node are open, shared by every effect in the scene. */
    private val effectInspector by lazy { VfxInspectorState() }

    /** The model of [entity] with everything on it; null when it has no model. */
    fun of(entity: WorldObjectEntity): ObjectParts? {
        val model = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelOrNull() ?: return null
        val instance = entity.modelInstanceOrNull(ROOT_COMPONENT_ID, model.model)?.attachment
        return PartsReader().read(model, instance)
    }

    fun rowId(uuid: UUID, path: PartPath) = "$uuid$ROW${path.encode()}"

    /** The object and part a row stands for; null for any other row. */
    fun parse(id: String): Pair<UUID, PartPath>? {
        val separator = id.indexOf(ROW).takeIf { it > 0 } ?: return null
        val uuid = runCatching { UUID.fromString(id.substring(0, separator)) }.getOrNull() ?: return null
        return uuid to (PartPath.decode(id.substring(separator + 1)) ?: return null)
    }

    /** The rows of an object's parts under the object's own row, at [depth]. */
    fun appendRows(
        items: MutableList<UiTreeItem<Any?>>,
        uuid: UUID,
        entityId: Int,
        parts: ObjectParts,
        depth: Int,
        open: (String) -> Boolean,
    ) {
        val selectedPath = selected?.takeIf { it.uuid == uuid }?.path
        fun add(node: PartNode, level: Int) {
            val id = rowId(uuid, node.path)
            items += UiTreeItem(
                id = id,
                label = if (node.inherited) "${node.label}  ◇" else node.label,
                depth = level,
                payload = entityId,
                icon = node.icon,
                hasChildren = node.children.isNotEmpty(),
                expanded = open(id),
                selected = node.path == selectedPath,
            )
            if (open(id)) node.children.forEach { add(it, level + 1) }
        }
        add(parts.root, depth)
    }

    /**
     * Puts the part into the inspector, ready to have things hung on it. A node of an effect is taken even
     * before the scene has read it, since it may have been added a moment ago.
     */
    fun select(ref: PartRef, parts: ObjectParts) {
        val editing = editing(ref, parts.model)
        val target = if (ref.path.effectNode != null) {
            effectTarget(ref, effectEditing(ref, editing) ?: return)
        } else {
            target(editing, ref, parts.find(ref.path) ?: return, parts)
        }
        selected = ref
        InspectorSelection.publish(SOURCE, target)
    }

    fun clear() {
        selected = null
        InspectorSelection.release(SOURCE)
    }

    init {
        EntityStateSync.onReceived(::received)
    }

    /** Sends what the last frames changed; an edit waits for the tick when another went out a moment ago. */
    @SubscribeEvent
    fun onClientTick(event: TickEvent.Client) {
        sessions.values.forEach(EntityRigEditing::flush)
    }

    /**
     * What the server just sent for [entity]. Only answers from the server are compared with the edits sent,
     * never the local copy, which already holds the latest edit and would hide the answers still on the way.
     */
    private fun received(entity: Entity) {
        val session = sessions[entity.id] ?: return
        val model = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid)?.modelOrNull()
        if (model != null) {
            session.accept(model)
            return
        }
        sessions.remove(entity.id)?.forget()
        if (selected?.entityId == entity.id) clear()
    }

    /** What a right click on a part offers. */
    fun menu(ref: PartRef, parts: ObjectParts): List<UiDropdownItem> {
        val node = parts.find(ref.path) ?: return emptyList()
        val editing = editing(ref, parts.model)
        val path = ref.path
        return when (node.kind) {
            PartKind.MODEL, PartKind.BONE -> listOfNotNull(
                attachMenu(ref, parts, editing, path.bone),
                effectPartMenu(ref, editing, path.bone),
                path.bone?.let { bone -> visibilityItem(editing, bone) },
            )

            PartKind.ATTACHMENT -> {
                val id = path.attachment ?: return emptyList()
                val effect = effectEditing(ref, editing)
                effect?.let { vfxNodeMenu(it, EffectNodes(ref), null) }.orEmpty() + listOfNotNull(
                    effect?.let {
                        UiDropdownItem(
                            rigText("effect_reset"),
                            icon = PartIcons.REMOVE,
                            enabled = it.canReset,
                            separatorBefore = true
                        ) { it.reset() }
                    },
                    UiDropdownItem(
                        rigText("remove_attachment"),
                        icon = PartIcons.REMOVE,
                        enabled = !node.inherited,
                        shortcut = "Delete"
                    ) {
                        removeAttachment(editing, path.bone, id)
                    },
                )
            }

            PartKind.EFFECT_NODE -> effectEditing(ref, editing)?.let {
                vfxNodeMenu(
                    it, EffectNodes(ref), path.effectNode
                )
            }.orEmpty()

            PartKind.MATERIAL -> {
                val name = path.material ?: return emptyList()
                listOf(
                    UiDropdownItem(rigText("material_reset"), icon = PartIcons.REMOVE, enabled = !node.inherited) {
                        editing.edit { rig -> rig.copy(materials = rig.materials - name) }
                    },
                )
            }

            PartKind.MATERIALS -> emptyList()
        }
    }

    private fun attachMenu(ref: PartRef, parts: ObjectParts, editing: RigEditing, bone: String?): UiDropdownItem? {
        val kinds = instanceKinds(bone)
        if (kinds.isEmpty()) return null
        return UiDropdownItem(
            rigText("attach"),
            icon = PartIcons.ADD,
            children = kinds.map { type ->
                val create = requireNotNull(type.createDefault)
                UiDropdownItem(type.title()) {
                    val spec = create(freeAttachmentId(editing.occupied, bone, type))
                    editing.edit { rig -> rig.withHolder(bone, rig.holder(bone).withAttachment(spec)) }
                    select(ref, parts)
                }
            },
        )
    }

    /**
     * The parts of an effect - an emitter, a mesh, a trail - hung straight on the bone or the model, as an effect
     * of its own that needs no file. The new part comes up selected.
     */
    private fun effectPartMenu(ref: PartRef, editing: RigEditing, bone: String?): UiDropdownItem? {
        val type = instanceKinds(bone).firstOrNull { it.specClass == VfxBoneAttachmentSpec::class } ?: return null
        return UiDropdownItem(
            rigText("attach_effect_part"),
            icon = PartIcons.EFFECT,
            children = VfxNodeTypes.all.mapNotNull { nodeType ->
                val create = nodeType.createDefault ?: return@mapNotNull null
                UiDropdownItem(nodeType.titleKey.lang, icon = nodeType.icon) {
                    val part = create()
                    val spec = VfxBoneAttachmentSpec(
                        freeAttachmentId(editing.occupied, bone, type), ownEffect = VfxEffect(nodes = listOf(part))
                    )
                    editing.edit { rig -> rig.withHolder(bone, rig.holder(bone).withAttachment(spec)) }
                    val attachment = ref.path.copy(attachment = spec.id)
                    WorldObjectScene.expand(rowId(ref.uuid, ref.path))
                    WorldObjectScene.expand(rowId(ref.uuid, attachment))
                    WorldObjectScene.partsOf(ref.uuid)
                        ?.let { select(ref.copy(path = attachment.copy(effectNode = part.id)), it) }
                }
            },
        )
    }

    private fun visibilityItem(editing: RigEditing, bone: String): UiDropdownItem {
        val hidden = editing.occupied.bone(bone)?.hidden == true
        return UiDropdownItem(
            rigText(if (hidden) "visible" else "hidden"), icon = if (hidden) PartIcons.VISIBLE else PartIcons.INVISIBLE
        ) {
            editing.edit { rig -> rig.withBone(bone, rig.holder(bone).copy(hidden = !hidden)) }
        }
    }

    private fun removeAttachment(editing: RigEditing, bone: String?, id: String) {
        editing.edit { rig -> rig.withHolder(bone, rig.holder(bone).withoutAttachment(id)) }
        selected?.let { if (it.path.attachment == id && it.path.bone == bone) clear() }
    }

    /**
     * Delete on the selected part, and on a node of an effect whatever its menu offers by key; true when [key]
     * was one of them. Undo and redo belong to [WorldHistory], which callers ask first.
     */
    fun handleKey(key: Int, modifiers: Int, parts: (UUID) -> ObjectParts?): Boolean {
        val ref = selected ?: return false
        val objectParts = parts(ref.uuid) ?: return false
        val editing = editing(ref, objectParts.model)
        if (ref.path.effectNode != null) {
            val effect = effectEditing(ref, editing) ?: return false
            return handleVfxNodeKey(effect, EffectNodes(ref), key, modifiers, repeat = false)
        }
        if (key != GLFW.GLFW_KEY_DELETE || modifiers != 0) return false
        val node = objectParts.find(ref.path) ?: return false
        val id = ref.path.attachment ?: return false
        if (node.kind != PartKind.ATTACHMENT || node.inherited) return false
        removeAttachment(editing, ref.path.bone, id)
        return true
    }

    /**
     * The selected part, ready for the gizmo: a bone, or a model or effect placed on one or on the model itself.
     * Null for anything else, and while the object is not drawn.
     */
    fun selectedPart(): PartGizmo? {
        val ref = selected?.takeIf { it.path.material == null && it.path.effectNode == null } ?: return null
        val path = ref.path
        if (path.bone == null && path.attachment == null) return null
        val entity = Minecraft.getInstance().level?.getEntity(ref.entityId) as? WorldObjectEntity ?: return null
        val snapshot = AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid) ?: return null
        val model = snapshot.modelOrNull() ?: return null
        val drawn = drawnScope(entity, model, path.steps) ?: return null
        val transform = snapshot.transformOrNull() ?: TransformComponent()
        val editing = editing(ref, model)
        val holder = (if (path.bone == null) drawn.modelRoot else drawn.findNode(path.bone)) ?: return null
        val attachment = path.attachment ?: return BoneGizmo(entity, drawn, holder, transform, editing, path.encode())
        if (editing.occupied.holder(path.bone).attachment(attachment) !is PlacedAttachmentSpec) return null
        return AttachmentGizmo(entity, holder, transform, editing, path.bone, attachment, path.encode())
    }

    /** The drawn model a part belongs to, found through the models nested on the way. */
    private fun drawnScope(entity: WorldObjectEntity, model: Model, steps: List<NestStep>): ModelAttachment? {
        var current = entity.modelInstanceOrNull(ROOT_COMPONENT_ID, model.model)?.attachment ?: return null
        steps.forEach { step ->
            val holder = if (step.bone == null) current.modelRoot else current.findNode(step.bone)
            current = holder?.attachments?.filterIsInstance<NestedModelAttachment>()
                ?.firstOrNull { it.spec.id == step.attachment }?.model ?: return null
        }
        return current
    }

    /** The rig the part belongs to, reached through every model nested on the way. */
    fun editing(ref: PartRef, model: Model): RigEditing =
        ref.path.steps.fold(session(ref.entityId, model) as RigEditing) { outer, step ->
            NestedRigEditing(
                outer, step.bone, step.attachment
            )
        }

    /** The effect the part's attachment plays; null when the attachment is not an effect. */
    private fun effectEditing(ref: PartRef, editing: RigEditing): AttachedVfxEditing? {
        val id = ref.path.attachment ?: return null
        if (editing.occupied.holder(ref.path.bone).attachment(id) !is VfxBoneAttachmentSpec) return null
        return AttachedVfxEditing(editing, ref.path.bone, id)
    }

    private fun session(entityId: Int, model: Model): EntityRigEditing {
        sessions.remove(entityId)?.let { existing ->
            sessions[entityId] = existing
            return existing
        }
        val created = EntityRigEditing(entityId, model)
        sessions[entityId] = created
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())?.forget()
        return created
    }

    /** What an object can hang on its models: whatever a rig file can. */
    private fun instanceKinds(bone: String?): List<RigAttachmentType<*>> = attachableKinds(bone)

    private fun target(editing: RigEditing, ref: PartRef, node: PartNode, parts: ObjectParts) = InspectorTarget(
        id = "object-part-${ref.entityId}-${ref.path.encode()}",
        title = node.label,
        icon = node.icon,
        subtitle = rigText(
            when (node.kind) {
                PartKind.MODEL -> "section_model"
                PartKind.MATERIALS, PartKind.MATERIAL -> "section_material"
                else -> "section_bone"
            }
        ),
    ) {
        CompositionLocalProvider(
            LocalEditorBones provides parts.bones[ref.path.steps].orEmpty(),
            LocalRigModelInfo provides (parts.models[ref.path.steps] ?: RigModelInfo()),
        ) {
            key(ref.path) {
                when (node.kind) {
                    PartKind.MATERIAL -> MaterialFields(editing, ref.path.material.orEmpty())
                    PartKind.MATERIALS -> Hint(rigText("material_hint"))
                    else -> HolderFields(editing, ref.path.bone, ref.path.attachment, instanceKinds(ref.path.bone))
                }
            }
        }
    }

    /** A node of an effect, with the same fields the effect editor gives it. */
    private fun effectTarget(ref: PartRef, effect: AttachedVfxEditing): InspectorTarget {
        val nodeId = ref.path.effectNode.orEmpty()
        val node = effect.effect.node(nodeId)
        val type = node?.let(VfxNodeTypes::of)
        return InspectorTarget(
            id = "object-part-${ref.entityId}-${ref.path.encode()}",
            title = node?.name ?: nodeId,
            icon = type?.icon,
            subtitle = type?.titleKey?.lang,
        ) {
            val live = effect.effect.node(nodeId) ?: return@InspectorTarget Hint(rigText("effect_node_gone"))
            key(ref.path) { VfxNodeFields(effect, effectInspector, live) }
        }
    }

    /** The tree of the effect under [ref]'s attachment, as the scene shows it: a node is selected by its row. */
    private class EffectNodes(private val ref: PartRef) : VfxNodeSelection {
        private val attachment = ref.path.copy(effectNode = null)

        override val selected: String?
            get() = WorldObjectParts.selected?.takeIf { it.uuid == ref.uuid && it.path.copy(effectNode = null) == attachment }?.path?.effectNode

        override fun select(id: String?) {
            val parts = WorldObjectScene.partsOf(ref.uuid) ?: return
            select(ref.copy(path = attachment.copy(effectNode = id)), parts)
        }

        override fun reveal(parent: String?) =
            WorldObjectScene.expand(rowId(ref.uuid, attachment.copy(effectNode = parent)))
    }
}
