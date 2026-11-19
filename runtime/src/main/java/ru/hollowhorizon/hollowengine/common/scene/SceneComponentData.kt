package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.KSerializer

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentSyncPolicy
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import java.util.UUID

/** The saved and tracked form of an owner's components. */
internal object SceneComponentData {
    private data class Unresolved(val raw: CompoundTag)

    fun write(owner: ComponentOwner, syncOnly: Boolean = false) = CompoundTag().apply {
        for ((id, value) in owner.components) {
            encode(value, owner.componentEnabled(id), syncOnly)?.let { put(id.toString(), it) }
        }
    }

    fun apply(owner: ComponentOwner, tag: CompoundTag, syncOnly: Boolean) {
        for (key in tag.allKeys) {
            val id = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
            val item = tag.getCompound(key)
            if (!set(owner, id, item, syncOnly, replace = true) && !syncOnly && owner.component(id) == null) {
                val unresolved = Unresolved(item.copy())
                when (owner) {
                    is Scene -> owner.restoreUnknown(unresolved, id)
                    is SceneObject -> owner.restoreUnknown(unresolved, id)
                }
            }
        }
        for ((id, value) in owner.components) {
            if (!tag.contains(id.toString()) && (!syncOnly || synced(value))) owner.detach(id)
        }
    }

    fun set(owner: ComponentOwner, id: UUID, tag: CompoundTag, syncOnly: Boolean, replace: Boolean = false): Boolean {
        val key = ResourceLocation.tryParse(tag.getString("type")) ?: return false
        val type = ComponentDescriptorRegistry.descriptorOrNull(key) ?: return false
        if (syncOnly && type.syncPolicy != ComponentSyncPolicy.SYNC) return false
        val data = tag.get("data") ?: return false
        val value = runCatching { NBTFormat.deserialize(type.serializer, data) }.getOrNull() ?: return false
        if (!owner.accepts(value)) return false
        val current = owner.component(id)
        if (syncOnly && current != null && !synced(current)) return false
        val enabled = tag.getBoolean("enabled")
        if (current == null) owner.attach(value, id, enabled)
        else {
            if (current::class != value::class) {
                if (!replace) return false
                owner.detach(id)
                owner.attach(value, id, enabled)
            } else {
                owner.setComponent(id, value)
                owner.setComponentEnabled(id, enabled)
            }
        }
        return true
    }

    fun synced(value: Any): Boolean =
        ComponentDescriptorRegistry.descriptorOrNull(value::class)?.syncPolicy == ComponentSyncPolicy.SYNC

    fun encode(value: Any, enabled: Boolean, syncOnly: Boolean = false): CompoundTag? {
        if (value is Unresolved) return if (syncOnly) null else value.raw.copy()
        val type = ComponentDescriptorRegistry.descriptorOrNull(value::class) ?: return null
        if (syncOnly && type.syncPolicy != ComponentSyncPolicy.SYNC) return null
        @Suppress("UNCHECKED_CAST")
        val serializer = type.serializer as KSerializer<Any>
        return CompoundTag().apply {
            putString("type", type.id.toString())
            put("data", NBTFormat.serialize(serializer, value))
            putBoolean("enabled", enabled)
        }
    }
}
