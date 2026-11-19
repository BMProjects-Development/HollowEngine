package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.builtins.ListSerializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.models.LegacyNodePlacement
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import java.util.UUID

/** Saves the authored branch of a world object or sends it to tracked clients. */
internal object SceneTreeData {
    val importedPathSerializer = ListSerializer(ScenePathSegment.serializer())

    fun write(root: SceneObject, syncOnly: Boolean, includeImported: Boolean = false): CompoundTag {
        val components = SceneComponentData.write(root, syncOnly)
        val children = ListTag()
        val overrides = if (includeImported) ListTag() else null
        for (child in root.children) {
            val id = child.createdId
            if (id != null) {
                children.add(writeNode(child, syncOnly, includeImported).apply { putUUID("id", id) })
            } else if (overrides != null) {
                val data = write(child, syncOnly, includeImported)
                if (!child.enabled) data.putBoolean("enabled", false)
                child.importedRestTransform?.let { data.put("pose", writePose(it)) }
                if (!data.isEmpty) {
                    val segment = ScenePathSegment(child.name, checkNotNull(child.importedOccurrence))
                    data.put("path", NBTFormat.serialize(importedPathSerializer, listOf(segment)))
                    overrides.add(data)
                }
            }
        }
        return CompoundTag().apply {
            if (!components.isEmpty || children.isNotEmpty()) {
                put("components", components)
                if (children.isNotEmpty()) put("children", children)
            }
            if (overrides != null && overrides.isNotEmpty()) put("overrides", overrides)
        }
    }

    fun writeNode(node: SceneObject, syncOnly: Boolean, includeImported: Boolean = false): CompoundTag =
        write(node, syncOnly, includeImported).apply {
            if (!contains("components")) put("components", CompoundTag())
            putString("name", node.name)
            putBoolean("enabled", node.enabled)
            val pose = if (includeImported && node.modelSource != null) node.restTransform else node.localTransform
            put("pose", writePose(pose))
        }

    fun apply(root: SceneObject, data: CompoundTag, syncOnly: Boolean) {
        if (!data.contains("components") && !data.contains("children")) {
            if (data.isEmpty) root.children.filter { it.createdId != null }.forEach(SceneObject::remove)
            applyLegacy(root, data, syncOnly)
            return
        }
        SceneComponentData.apply(root, data.getCompound("components"), syncOnly)
        val children = data.getList("children", Tag.TAG_COMPOUND.toInt())
        val incoming = HashSet<UUID>()
        for (index in children.indices) {
            val child = children.getCompound(index)
            if (child.hasUUID("id")) incoming.add(child.getUUID("id"))
        }
        for (child in root.children) {
            val id = child.createdId ?: continue
            if (id !in incoming) child.remove()
        }
        for (index in children.indices) {
            val entry = children.getCompound(index)
            if (!entry.hasUUID("id")) continue
            val id = entry.getUUID("id")
            val name = entry.getString("name").takeIf(String::isNotBlank) ?: continue
            val existing = root.scene.resolve(SceneAddress(id))
            if (existing != null && existing.parent !== root) continue
            if (existing?.name != name && root.children.any { it.name == name }) continue
            val child = existing ?: root.create(name, id)
            if (child.name != name) child.rename(name)
            child.setEnabled(entry.getBoolean("enabled"))
            entry.get("pose")?.let { applyPose(child, it as? CompoundTag ?: return@let) }
            apply(child, entry, syncOnly)
        }
    }

    fun writePose(transform: TrsTransformF): CompoundTag = NBTFormat.serialize(
        SceneTransformSerializer, transform,
    ) as CompoundTag

    fun readPose(tag: CompoundTag, destination: TrsTransformF = TrsTransformF()): TrsTransformF? {
        val pose = runCatching { NBTFormat.deserialize(SceneTransformSerializer, tag) }.getOrNull() ?: return null
        return pose.copyValidatedPoseTo(destination)
    }

    fun applyPose(node: SceneObject, tag: CompoundTag): Boolean = readPose(tag, node.localTransform) != null

    private fun applyLegacy(root: SceneObject, data: CompoundTag, syncOnly: Boolean) {
        val direct = data.copy()
        for (key in data.allKeys) {
            val id = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            val entry = data.getCompound(key)
            if (entry.getString("type") != "hollowengine:rig/vfx") continue
            val placement = runCatching {
                NBTFormat.deserialize(LegacyNodePlacement.serializer(), entry.getCompound("data"))
            }.getOrNull() ?: continue
            if (placement == LegacyNodePlacement()) continue
            val nodeId = UUID.nameUUIDFromBytes("placement:${root.createdId}:$id".toByteArray(Charsets.UTF_8))
            val name = "effect_$id"
            val child = root.children.firstOrNull { it.createdId == nodeId } ?: root.create(name, nodeId)
            child.localTransform.set(placement.origin(null).localTransform())
            direct.remove(key)
            SceneComponentData.apply(child, CompoundTag().apply { put(key, entry) }, syncOnly)
        }
        SceneComponentData.apply(root, direct, syncOnly)
    }
}
