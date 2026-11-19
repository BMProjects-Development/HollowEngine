package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.ui.panels.UiPanelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.ICanDecodeCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import java.util.UUID

@Serializable
internal data class LegacyNodePlacement(
    val offset: Vec3f = Vec3f.ZERO,
    val rotation: Vec3f = Vec3f.ZERO,
    val scale: Float = 1f,
) {
    fun origin(parent: String?) = RigBoneOrigin(parent, offset, rotation, Vec3f(scale, scale, scale))
}

internal fun Any.hadLegacyPlacement(): Boolean = when (this) {
    is ModelAttachmentSpec, is VfxComponent, is Decal, is UiPanelAttachmentSpec, is IkTargetSpec -> true
    else -> false
}

internal fun legacyPlacementNode(parent: String?, id: String): String {
    val entry = "${parent.orEmpty()}\u0000$id"
    return "${id}_${UUID.nameUUIDFromBytes(entry.toByteArray(Charsets.UTF_8))}"
}

/** The old placement fields are read from file data once and stored as ordinary rig bones. */
@OptIn(ExperimentalSerializationApi::class)
internal object ModelRigSerializer : KSerializer<ModelRig> {
    private val delegate = ModelRig.generatedSerializer()
    override val descriptor get() = delegate.descriptor

    override fun serialize(encoder: Encoder, value: ModelRig) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ModelRig = when (decoder) {
        is ICanDecodeCompoundNBT -> {
            val tag = decoder.decodeCompoundNBT()
            val rig = NBTFormat.deserialize(delegate, tag)
            if (tag.contains("formatVersion")) rig else rig.migratePlacements { parent, index ->
                val owner = if (parent == null) tag else tag.getCompound("bones").getCompound(parent)
                val entry = owner.getList("attachments", Tag.TAG_COMPOUND.toInt()).getCompound(index)
                val data = entry.allKeys.singleOrNull()?.let(entry::get) as? CompoundTag
                data?.let { NBTFormat.deserialize(LegacyNodePlacement.serializer(), it) } ?: LegacyNodePlacement()
            }
        }
        is JsonDecoder -> {
            val data = decoder.decodeJsonElement()
            val rig = decoder.json.decodeFromJsonElement(delegate, data)
            if (data !is JsonObject || "formatVersion" in data) rig else rig.migratePlacements { parent, index ->
                val holder = if (parent == null) data else (data["bones"] as? JsonObject)?.get(parent) as? JsonObject
                val spec = (holder?.get("attachments") as? JsonArray)?.getOrNull(index)
                if (spec == null) LegacyNodePlacement()
                else decoder.json.decodeFromJsonElement(LegacyNodePlacement.serializer(), spec)
            }
        }
        else -> delegate.deserialize(decoder)
    }

    private fun ModelRig.migratePlacements(placement: (String?, Int) -> LegacyNodePlacement): ModelRig {
        if (attachments.none { it.hadLegacyPlacement() } &&
            bones.values.none { bone -> bone.attachments.any { it.hadLegacyPlacement() } }
        ) return this
        val placed = LinkedHashMap(bones)
        fun migrate(parent: String?, specs: List<Any>): List<Any> = buildList {
            for ((index, spec) in specs.withIndex()) {
                if (!spec.hadLegacyPlacement()) add(spec)
                else {
                    val name = legacyPlacementNode(parent, spec.rigId)
                    require(name !in placed) { "Legacy placement conflicts with rig bone '$name'" }
                    placed[name] = RigBone(attachments = listOf(spec), origin = placement(parent, index).origin(parent))
                }
            }
        }
        val root = migrate(null, attachments)
        for ((name, bone) in bones) placed[name] = bone.copy(attachments = migrate(name, bone.attachments))
        return copy(bones = placed, attachments = root)
    }
}
