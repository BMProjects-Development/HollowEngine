package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Reusable authored contents of a scene object. The target keeps its own placement. */
@Serializable
class ScenePrefab private constructor(
    @Serializable(with = ForCompoundNBT::class) private val contents: CompoundTag,
) {
    fun write(output: OutputStream) = NBTFormat.serialize(serializer(), this).save(output)

    fun applyTo(owner: SceneObject) {
        val application = Application(owner.address, owner.scene)
        application.apply(application.prepare(owner, contents, place = false))
    }

    companion object {
        fun read(input: InputStream): ScenePrefab = NBTFormat.deserialize(serializer(), input.loadAsNBT())

        fun capture(owner: SceneObject): ScenePrefab {
            check(!owner.isRemoved && !owner.scene.isClosed) { "Cannot capture a removed scene object" }
            return ScenePrefab(SceneTreeData.write(owner, syncOnly = false, includeImported = true).copy())
        }
    }

    private class Application(anchor: SceneAddress, private val scene: Scene) {
        private val prefix = buildString {
            append(anchor.instanceId)
            for ((name, occurrence) in anchor.importedPath) append('/').append(name.length).append(':').append(name)
                .append(':').append(occurrence)
        }
        private val ids = HashSet<UUID>()
        private val targets = HashSet<SceneObject>()

        fun prepare(
            owner: SceneObject?, data: CompoundTag, id: UUID? = null, name: String? = null, place: Boolean = true,
        ): Edit {
            if (owner != null) require(targets.add(owner)) { "Duplicate prefab target ${owner.address}" }
            val components = data.compound("components")?.let { stored ->
                buildMap {
                    for (key in stored.allKeys) {
                        val componentId = UUID.fromString(key)
                        require(componentId !in this) { "Duplicate prefab component ID $componentId" }
                        val value = SceneComponentData.readPrefab(requireNotNull(stored.compound(key)))
                        SceneComponentData.validatePrefab(owner, componentId, value)
                        put(componentId, value)
                    }
                }
            }.orEmpty()
            val names = HashSet<String>()
            val children = data.entries("children").map { child ->
                require(child.hasUUID("id")) { "Missing prefab object ID" }
                val sourceId = child.getUUID("id")
                require(ids.add(sourceId)) { "Duplicate prefab object ID $sourceId" }
                val childName = child.getString("name")
                require(childName.isNotBlank() && names.add(childName)) { "Invalid or duplicate prefab name '$childName'" }
                val existing = scene.resolve(SceneAddress(sourceId))?.takeIf { owner != null && it.parent === owner }
                val instanceId = existing?.createdId ?: UUID.nameUUIDFromBytes("$prefix/$sourceId".toByteArray(Charsets.UTF_8))
                val target = existing ?: scene.resolve(SceneAddress(instanceId))
                require(target == null || owner != null && target.parent === owner) { "Prefab object $instanceId has another parent" }
                require(owner?.children?.none { it !== target && it.name == childName } != false) {
                    "Prefab name '$childName' conflicts with an existing child"
                }
                prepare(target, child, instanceId, childName)
            }
            val overrides = data.entries("overrides").map { override ->
                val path = NBTFormat.deserialize(SceneTreeData.importedPathSerializer, requireNotNull(override.get("path")))
                require(path.isNotEmpty()) { "Prefab override has an empty imported path" }
                val node = path.fold(owner) { target, segment -> target?.importedChild(segment) }
                requireNotNull(node) { "Cannot resolve prefab override $path under ${owner?.address}" }
                prepare(node, override)
            }
            val pose = if (place) data.compound("pose")?.let {
                requireNotNull(SceneTreeData.readPose(it)) { "Invalid prefab pose" }
            } else null
            val enabled = if (place && data.contains("enabled")) data.getBoolean("enabled") else null
            return Edit(owner, id, name, pose, enabled, components, children, overrides)
        }

        fun apply(root: Edit) {
            val rollback = ArrayDeque<() -> Unit>()
            val ready = ArrayList<Pair<SceneObject, Map<UUID, SceneComponentData.Value>>>()
            val activate = ArrayList<SceneObject>()
            fun prepareNodes(edit: Edit, parent: SceneObject?) {
                val node = edit.owner ?: checkNotNull(parent).create(checkNotNull(edit.name), checkNotNull(edit.id))
                    .also { created -> rollback.addFirst(created::remove) }
                edit.name?.takeIf { it != node.name }?.let {
                    val previous = node.name
                    rollback.addFirst { node.rename(previous) }
                    node.rename(it)
                }
                edit.pose?.let { pose ->
                    val previous = TrsTransformF().set(node.localTransform)
                    val rest = node.importedRestTransform
                    rollback.addFirst {
                        if (node.createdId == null) node.setImportedRestTransform(rest)
                        node.localTransform.set(previous)
                    }
                    if (node.createdId == null) node.setImportedRestTransform(pose) else node.localTransform.set(pose)
                }
                edit.enabled?.takeIf { it != node.enabled }?.let {
                    if (it) activate.add(node)
                    else {
                        rollback.addFirst { node.setEnabled(true) }
                        node.setEnabled(false)
                    }
                }
                if (edit.components.isNotEmpty()) ready.add(node to edit.components)
                for (child in edit.children) prepareNodes(child, node)
                for (override in edit.overrides) prepareNodes(override, null)
            }

            try {
                prepareNodes(root, null)
                for ((owner, components) in ready) {
                    for ((id, value) in components) {
                        val previous = owner.component(id)?.let { SceneComponentData.Value(it, owner.componentEnabled(id)) }
                        rollback.addFirst {
                            if (previous == null) owner.detach(id) else SceneComponentData.put(owner, id, previous)
                        }
                        SceneComponentData.put(owner, id, value)
                    }
                }
                for (node in activate) {
                    rollback.addFirst { node.setEnabled(false) }
                    node.setEnabled(true)
                }
            } catch (failure: Exception) {
                for (undo in rollback) {
                    try {
                        undo()
                    } catch (cleanup: Exception) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }
    }

    private data class Edit(
        val owner: SceneObject?,
        val id: UUID?,
        val name: String?,
        val pose: TrsTransformF?,
        val enabled: Boolean?,
        val components: Map<UUID, SceneComponentData.Value>,
        val children: List<Edit>,
        val overrides: List<Edit>,
    )
}

private fun CompoundTag.compound(key: String): CompoundTag? =
    get(key)?.let { requireNotNull(it as? CompoundTag) { "Expected compound '$key'" } }

private fun CompoundTag.entries(key: String): List<CompoundTag> {
    val stored = get(key) ?: return emptyList()
    require(stored is ListTag) { "Expected list '$key'" }
    return stored.map { requireNotNull(it as? CompoundTag) { "Expected compound in '$key'" } }
}
