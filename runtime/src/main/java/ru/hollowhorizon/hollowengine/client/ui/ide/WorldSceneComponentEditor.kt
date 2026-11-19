package ru.hollowhorizon.hollowengine.client.ui.ide

import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.client.editor.SceneNodeGizmo
import ru.hollowhorizon.hollowengine.client.editor.WorldHistory
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.rigText
import ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx.VfxEditing
import ru.hollowhorizon.hollowengine.client.ui.inspector.AutoFields
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentLabels
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.vfx.played
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentSyncPolicy
import ru.hollowhorizon.hollowengine.common.entities.objects.EditWorldObjectComponentPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.EditWorldObjectNodePacket
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.scene.*
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import java.util.*

internal fun componentLabel(value: Any): String {
    val type = ComponentDescriptorRegistry.descriptorOrNull(value::class)
    return type?.let { ComponentLabels.componentName(it.id, it.serializer.descriptor) }
        ?: value::class.simpleName.orEmpty()
}

internal fun componentIcon(value: Any): String {
    val type = ComponentDescriptorRegistry.descriptorOrNull(value::class)
    return type?.serializer?.descriptor?.let(ComponentLabels::componentIcon) ?: PartIcons.ATTACHMENT
}

internal object WorldSceneComponentEditor {
    fun addMenu(entity: WorldObjectEntity, root: SceneObject = entity.sceneObject): List<UiDropdownItem> {
        val ownerId = root.createdId?.takeUnless { root === entity.sceneObject }
        val nodeMenu = UiDropdownItem(rigText("add_bone"), mark = UiMenuParts.icon(PartIcons.ADD)) {
            var index = 1
            var name = "bone_$index"
            while (root.children.any { it.name == name }) name = "bone_${++index}"
            val id = UUID.randomUUID()
            if (editNode(entity, root, id, CompoundTag().apply {
                    putString("name", name)
                    putBoolean("enabled", true)
                })) {
                WorldObjectScene.expand(entity.uuid.toString())
                ownerId?.let {
                    WorldObjectScene.expand(
                        WorldObjectParts.rowId(
                            entity.uuid,
                            PartPath(sceneNodeId = it)
                        )
                    )
                }
                WorldObjectParts.select(
                    PartRef(entity.id, entity.uuid, PartPath(sceneNodeId = id)),
                    WorldObjectParts.of(entity)
                )
            }
        }
        val types = ComponentDescriptorRegistry.mapNotNull { holder ->
            val type = holder.value
            if (type.syncPolicy != ComponentSyncPolicy.SYNC || ComponentLabels.isHidden(type.serializer.descriptor)) return@mapNotNull null
            val value = runCatching { type.create() }.getOrNull() ?: return@mapNotNull null
            value.takeIf(root::accepts)
        }
        if (types.isEmpty()) return listOf(nodeMenu)
        return listOf(
            nodeMenu, UiDropdownItem(
                rigText("attach"),
                mark = UiMenuParts.icon(PartIcons.ADD),
                children = types.map { value ->
                    UiDropdownItem(componentLabel(value), mark = UiMenuParts.icon(componentIcon(value))) {
                        val id = UUID.randomUUID()
                        val data =
                            SceneComponentData.encode(value, enabled = true, syncOnly = true) ?: return@UiDropdownItem
                        if (edit(entity, root, id, data)) {
                            WorldObjectScene.expand(entity.uuid.toString())
                            ownerId?.let {
                                WorldObjectScene.expand(
                                    WorldObjectParts.rowId(
                                        entity.uuid,
                                        PartPath(sceneNodeId = it)
                                    )
                                )
                            }
                            val ref = PartRef(entity.id, entity.uuid, PartPath(componentId = id, sceneNodeId = ownerId))
                            WorldObjectParts.select(ref, WorldObjectParts.of(entity))
                        }
                    }
                },
            )
        )
    }

    fun target(ref: PartRef): InspectorTarget? {
        val entity = entity(ref) ?: return null
        val owner = owner(entity, ref.path) ?: return null
        val id = ref.path.componentId ?: return null
        val value = owner.component(id) ?: return null
        val descriptor = ComponentDescriptorRegistry.descriptorOrNull(value::class) ?: return null
        val serializer = ComponentJson.serializerOf(value) ?: return null
        return InspectorTarget(
            "scene-component-$id",
            componentLabel(value),
            componentIcon(value),
            history = WorldHistory.history
        ) {
            val current =
                WorldObjectScene.partsOf(ref.uuid)?.component(ref.path) ?: owner.component(id) ?: return@InspectorTarget
            val json = ComponentJson.encode(current) ?: return@InspectorTarget
            Column(tags = listOf("insp-body")) {
                AutoFields(descriptor.id, serializer.descriptor, json, "/$id") { changed ->
                    val next = ComponentJson.decode(serializer, changed) ?: return@AutoFields
                    rewrite(entity, owner, id, next, "component")
                }
            }
        }
    }

    fun remove(ref: PartRef): Boolean {
        val entity = entity(ref) ?: return false
        val owner = owner(entity, ref.path) ?: return false
        return edit(entity, owner, ref.path.componentId ?: return false, CompoundTag())
    }

    fun nodeTarget(ref: PartRef): InspectorTarget? {
        val node = entity(ref)?.let { owner(it, ref.path) } ?: return null
        return InspectorTarget(
            "scene-node-${node.createdId}",
            node.name,
            PartIcons.BONE,
            history = WorldHistory.history
        ) {}
    }

    fun nodeMenu(ref: PartRef): List<UiDropdownItem> {
        val entity = entity(ref) ?: return emptyList()
        val node = owner(entity, ref.path) ?: return emptyList()
        return addMenu(entity, node) + UiDropdownItem(
            rigText("remove_bone"),
            mark = UiMenuParts.icon(PartIcons.REMOVE)
        ) {
            if (removeNode(ref)) WorldObjectParts.clear()
        }
    }

    fun removeNode(ref: PartRef): Boolean {
        val entity = entity(ref) ?: return false
        val node = owner(entity, ref.path) ?: return false
        return editNode(entity, node.parent ?: return false, node.createdId ?: return false, CompoundTag())
    }

    fun nodeGizmo(ref: PartRef): SceneNodeGizmo? {
        val entity = entity(ref) ?: return null
        val node = owner(entity, ref.path) ?: return null
        val id = node.createdId ?: return null
        return SceneNodeGizmo(entity, node, { transform ->
            fun read() = SceneTreeData.writePose(node.localTransform)
            fun write(pose: CompoundTag) {
                if (node.isRemoved || !SceneTreeData.applyPose(node, pose)) return
                entity.onComponentsChanged()
                EditWorldObjectNodePacket(entity.id, entity.uuid, id, pose = pose).send()
            }

            val before = read()
            val next = SceneTreeData.writePose(transform)
            if (before != next) {
                write(next)
                WorldHistory.record(entity.id, SnapshotStep(node, UndoLabel.EDIT, before, ::read, ::write), "pose:$id")
            }
        }, ref.path.encode())
    }

    fun effectEditing(ref: PartRef): VfxEditing? {
        val entity = entity(ref) ?: return null
        val owner = owner(entity, ref.path) ?: return null
        val id = ref.path.componentId ?: return null
        fun spec() = owner.component(id) as? VfxComponent
        spec() ?: return null
        return object : VfxEditing {
            override val history get() = WorldHistory.history
            override val effect: VfxEffect get() = spec()?.played() ?: VfxEffect.EMPTY
            override fun beginGesture() = history.begin()
            override fun endGesture() = history.commit()
            override fun edit(
                mergeKey: String?,
                recorded: Boolean,
                label: UndoLabel?,
                change: (VfxEffect) -> VfxEffect,
            ) {
                val current = spec() ?: return
                rewrite(entity, owner, id, current.copy(ownEffect = change(effect)), mergeKey, label, recorded)
            }
        }
    }

    private fun rewrite(
        entity: WorldObjectEntity, owner: SceneObject, id: UUID, value: Any, mergeKey: String?,
        label: UndoLabel? = null, recorded: Boolean = true,
    ) {
        val next = SceneComponentData.encode(value, owner.componentEnabled(id), syncOnly = true) ?: return
        edit(entity, owner, id, next, mergeKey, label, recorded)
    }

    private fun edit(
        entity: WorldObjectEntity, root: SceneObject, id: UUID, next: CompoundTag, mergeKey: String? = null,
        label: UndoLabel? = null, recorded: Boolean = true,
    ): Boolean {
        fun read() =
            root.components[id]?.let { SceneComponentData.encode(it, root.componentEnabled(id), syncOnly = true) }
                ?: CompoundTag()

        fun write(data: CompoundTag): Boolean {
            if (root.isRemoved || entity.isRemoved) return false
            if (data.isEmpty) root.detach(id)
            else if (!SceneComponentData.set(root, id, data, syncOnly = true)) return false
            entity.onComponentsChanged()
            EditWorldObjectComponentPacket(
                entity.id,
                entity.uuid,
                id,
                data.takeUnless { it.isEmpty },
                root.createdId
            ).send()
            return true
        }

        val before = read()
        if (before == next || !write(next)) return false
        if (recorded) {
            WorldHistory.record(
                entity.id,
                SnapshotStep(root, label ?: UndoLabel.EDIT, before, ::read) { write(it) },
                mergeKey?.let { "$id/$it" })
        }
        return true
    }

    private fun editNode(entity: WorldObjectEntity, parent: SceneObject, id: UUID, next: CompoundTag): Boolean {
        fun node() = parent.scene.resolve(SceneAddress(id))
        fun read() = node()?.let { SceneTreeData.writeNode(it, syncOnly = true) } ?: CompoundTag()
        fun write(data: CompoundTag) {
            if (entity.isRemoved || parent.isRemoved) return
            if (data.isEmpty) {
                node()?.remove()
                EditWorldObjectNodePacket(entity.id, entity.uuid, id).send()
            } else {
                val name = data.getString("name")
                if (parent.children.any { it.name == name }) return
                val child = parent.create(name, id)
                child.setEnabled(data.getBoolean("enabled"))
                (data.get("pose") as? CompoundTag)?.let { SceneTreeData.applyPose(child, it) }
                SceneTreeData.apply(child, data, syncOnly = true)
                EditWorldObjectNodePacket(
                    entity.id,
                    entity.uuid,
                    id,
                    parent.createdId,
                    name,
                    data.get("pose") as? CompoundTag,
                    data
                ).send()
            }
            entity.onComponentsChanged()
        }

        val before = read()
        if (before == next) return false
        write(next)
        WorldHistory.record(entity.id, SnapshotStep(parent, UndoLabel.EDIT, before, ::read, ::write))
        return true
    }

    private fun owner(entity: WorldObjectEntity, path: PartPath): SceneObject? {
        val root = entity.sceneObjectOrNull ?: return null
        return path.sceneNodeId?.let { root.scene.resolve(SceneAddress(it)) }
            ?: root.takeIf { path.sceneNodeId == null }
    }

    private fun entity(ref: PartRef): WorldObjectEntity? =
        (Minecraft.getInstance().level?.getEntity(ref.entityId) as? WorldObjectEntity)?.takeIf { it.uuid == ref.uuid }
}
