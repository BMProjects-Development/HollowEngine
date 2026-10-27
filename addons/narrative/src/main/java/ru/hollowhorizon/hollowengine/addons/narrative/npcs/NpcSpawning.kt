package ru.hollowhorizon.hollowengine.addons.narrative.npcs

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.Action
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.Unsaved
import ru.hollowhorizon.hollowengine.addons.narrative.story.action
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.registry.npcs
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.buildNpc
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForUuid
import java.util.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory

/**
 * Spawns an NPC at [pos], set up by [builder]. Undone, the NPC is removed for good, wherever it went since; brought
 * back by a rewind, it returns as [builder] made it, since the journal keeps the NPC it built rather than [builder].
 *
 * @param id the author's id, such as `"village:blacksmith"`. When a live NPC has it, that NPC is returned, [builder]
 * does not run, and undoing the call leaves that NPC where it is.
 */
@EditorCategory("hollowengine.gui.ide.story.category.npc")
@Action("npc")
suspend fun ServerLevel.npc(pos: Vec3, id: String? = null, @Unsaved builder: NpcEntity.() -> Unit = {}): NpcEntity =
    action(this, pos, id, builder) {
        val npcs = server.npcs
        val built = capture { if (id != null && npcs.alive(id) != null) null else BuiltNpc.of(buildNpc(pos, builder)) }
        undo { built?.let { npcs.despawn(it.uuid) } }
        perform {
            if (built == null) return@perform npcs.alive(id!!) ?: throw StoryException("NPC '$id' is gone")
            npcs.resolve(built.uuid) ?: built.spawn(this@npc, id)
        }
    }

/** An NPC as built for [npc], kept by the journal to bring it back exactly so. */
@Serializable
internal class BuiltNpc(
    @Serializable(ForUuid::class) val uuid: UUID,
    @Serializable(ForCompoundNBT::class) val nbt: CompoundTag,
) {
    /** The instance just built, spawned as it is the first time; the journal reads only [nbt] back. */
    @Transient
    private var built: NpcEntity? = null

    fun spawn(level: ServerLevel, id: String?): NpcEntity {
        val npc = built ?: EntityType.loadEntityRecursive(nbt.copy(), level) { it } as? NpcEntity
        ?: throw StoryException("NPC $uuid cannot be built again from what the story kept")
        built = null
        level.server.npcs.spawn(npc, id)
        return npc
    }

    companion object {
        fun of(npc: NpcEntity): BuiltNpc {
            val nbt = npc.saveWithoutId(CompoundTag())
            nbt.putString(Entity.ID_TAG, BuiltInRegistries.ENTITY_TYPE.getKey(npc.type).toString())
            return BuiltNpc(npc.uuid, nbt).also { it.built = npc }
        }
    }
}
