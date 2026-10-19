package ru.hollowhorizon.hollowengine.common.npcs.registry

import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.*

/**
 * What the NPC registry knows about one NPC without reading its entity NBT: enough to find it and to decide
 * whether its body has to be built.
 *
 * @property id the author's id, such as `village:blacksmith`, unique among living NPCs; null for anonymous ones.
 * @property isDead the NPC died while it was kept after death; the record waits to be replaced or despawned.
 * @property isForceLoaded the NPC keeps the chunks around it loaded, so it is brought back at server start.
 */
data class NpcRecord(
    val uuid: UUID,
    val id: String?,
    val dimension: ResourceKey<Level>,
    val position: Vec3,
    val isDead: Boolean = false,
    val isForceLoaded: Boolean = false,
) {
    val chunk: Long
        get() = ChunkPos.asLong(SectionPos.blockToSectionCoord(position.x), SectionPos.blockToSectionCoord(position.z))

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID(UUID_KEY, uuid)
        id?.let { tag.putString(ID_KEY, it) }
        tag.putString(DIMENSION_KEY, dimension.location().toString())
        tag.put(POSITION_KEY, ListTag().apply {
            add(DoubleTag.valueOf(position.x))
            add(DoubleTag.valueOf(position.y))
            add(DoubleTag.valueOf(position.z))
        })
        if (isDead) tag.putBoolean(DEAD_KEY, true)
        if (isForceLoaded) tag.putBoolean(FORCE_LOADED_KEY, true)
        return tag
    }

    companion object {
        private const val UUID_KEY = "uuid"
        private const val ID_KEY = "id"
        private const val DIMENSION_KEY = "dimension"
        private const val POSITION_KEY = "pos"
        private const val DEAD_KEY = "dead"
        private const val FORCE_LOADED_KEY = "forceLoaded"

        /** Reads a record, or returns null when [tag] does not hold one. */
        fun load(tag: CompoundTag): NpcRecord? {
            if (!tag.hasUUID(UUID_KEY)) return null
            val dimension = ResourceLocation.tryParse(tag.getString(DIMENSION_KEY)) ?: return null
            val position = tag.getList(POSITION_KEY, Tag.TAG_DOUBLE.toInt())
            if (position.size != 3) return null
            return NpcRecord(
                uuid = tag.getUUID(UUID_KEY),
                id = tag.getString(ID_KEY).ifEmpty { null },
                dimension = ResourceKey.create(Registries.DIMENSION, dimension),
                position = Vec3(position.getDouble(0), position.getDouble(1), position.getDouble(2)),
                isDead = tag.getBoolean(DEAD_KEY),
                isForceLoaded = tag.getBoolean(FORCE_LOADED_KEY),
            )
        }
    }
}
