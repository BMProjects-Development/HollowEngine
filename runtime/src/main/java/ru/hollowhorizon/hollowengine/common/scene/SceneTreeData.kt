package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.models.LegacyNodePlacement
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForQuatF
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import java.util.UUID

/** Saves the authored branch of a world object or sends it to tracked clients. */
internal object SceneTreeData {
    @Serializable
    private data class Pose(
        val position: Vec3f,
        @Serializable(with = ForQuatF::class) val rotation: QuatF,
        val scale: Vec3f,
    )

    fun write(root: SceneObject, syncOnly: Boolean): CompoundTag {
        val components = SceneComponentData.write(root, syncOnly)
        val children = ListTag()
        for (child in root.children) {
            val id = child.createdId ?: continue
            children.add(writeNode(child, syncOnly).apply { putUUID("id", id) })
        }
        return CompoundTag().apply {
            if (!components.isEmpty || children.isNotEmpty()) {
                put("components", components)
                if (children.isNotEmpty()) put("children", children)
            }
        }
    }

    fun writeNode(node: SceneObject, syncOnly: Boolean): CompoundTag = write(node, syncOnly).apply {
        if (!contains("components")) put("components", CompoundTag())
        putString("name", node.name)
        putBoolean("enabled", node.enabled)
        put("pose", writePose(node.localTransform))
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
        Pose.serializer(), Pose(Vec3f(transform.translation), QuatF(transform.rotation), Vec3f(transform.scale)),
    ) as CompoundTag

    fun applyPose(node: SceneObject, tag: CompoundTag): Boolean {
        val pose = runCatching { NBTFormat.deserialize(Pose.serializer(), tag) }.getOrNull() ?: return false
        val (x, y, z) = pose.position
        if (!(x.isFinite() && y.isFinite() && z.isFinite() && pose.rotation.x.isFinite() &&
                    pose.rotation.y.isFinite() && pose.rotation.z.isFinite() && pose.rotation.w.isFinite() &&
                    pose.scale.x.isFinite() && pose.scale.y.isFinite() && pose.scale.z.isFinite())) return false
        val norm = pose.rotation.dot(pose.rotation)
        if (!norm.isFinite() || norm == 0f) return false
        node.localTransform.setCompositionOf(pose.position, pose.rotation.normed(), pose.scale)
        return true
    }

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
