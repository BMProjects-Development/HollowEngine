package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import net.minecraft.nbt.Tag
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.utils.nbt.ICanDecodeCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat

/** Older rig files wrapped the decal's fields and named the outer attachment. */
internal object RigDecalSerializer : KSerializer<Decal> by Decal.serializer() {
    override fun deserialize(decoder: Decoder): Decal = when (decoder) {
        is JsonDecoder -> {
            val data = decoder.decodeJsonElement()
            val legacy = (data as? JsonObject)?.get("decal") as? JsonObject
            val fields = if (legacy != null) JsonObject(legacy + (data as JsonObject).filterKeys { it == "id" }) else data
            decoder.json.decodeFromJsonElement(Decal.serializer(), fields)
        }
        is ICanDecodeCompoundNBT -> {
            val data = decoder.decodeCompoundNBT()
            val fields = if (data.contains("decal", Tag.TAG_COMPOUND.toInt())) data.getCompound("decal").copy().apply {
                data.get("id")?.let { put("id", it) }
            } else data
            NBTFormat.deserialize(Decal.serializer(), fields)
        }
        else -> Decal.serializer().deserialize(decoder)
    }
}
