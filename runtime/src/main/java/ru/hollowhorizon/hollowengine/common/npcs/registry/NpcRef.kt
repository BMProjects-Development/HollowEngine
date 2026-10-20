package ru.hollowhorizon.hollowengine.common.npcs.registry

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import java.util.*

/** A handle on an NPC that stays valid whether its chunk is loaded or not. */
class NpcRef(val uuid: UUID) {
    private val npcs: ServerNpcs get() = currentServer.npcs

    /** Whether the NPC still exists, alive or kept after death. */
    val exists: Boolean get() = npcs.registry[uuid] != null

    val isDead: Boolean get() = npcs.registry[uuid]?.isDead == true

    /** Whether the NPC's body stands in a loaded chunk. */
    val isLoaded: Boolean get() = npcs.isLoaded(uuid)

    /**
     * The NPC's entity: its body when the chunk is loaded, otherwise one built from the record outside the world,
     * which becomes the body once the chunk loads. Changes to it are saved with the world, but nothing ticks it:
     * it does not walk or act until it has a body. Null once the NPC is despawned.
     */
    fun resolve(): NpcEntity? = npcs.resolve(uuid)

    /** Moves the NPC; without a body it appears at once if the target chunk is loaded. */
    fun teleportTo(level: ServerLevel, position: Vec3, yaw: Float = 0f, pitch: Float = 0f) =
        npcs.teleport(uuid, level, position, yaw, pitch)

    /** Removes the NPC for good, whatever state it is in. */
    fun despawn() = npcs.despawn(uuid)

    override fun equals(other: Any?) = other is NpcRef && other.uuid == uuid

    override fun hashCode() = uuid.hashCode()

    override fun toString() = "NpcRef($uuid)"
}

/** The NPC's handle in the registry. */
val NpcEntity.ref: NpcRef get() = NpcRef(uuid)
