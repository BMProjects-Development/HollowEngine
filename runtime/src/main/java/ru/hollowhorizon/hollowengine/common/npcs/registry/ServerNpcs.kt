package ru.hollowhorizon.hollowengine.common.npcs.registry

import kotlinx.coroutines.cancel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import java.nio.file.Path
import java.util.*

/**
 * The NPCs of one server. Each one has a single [NpcEntity]: its body when its chunk is loaded, otherwise an
 * instance built from the [NpcRegistry] record outside the world, which becomes the body once the chunk loads.
 */
class ServerNpcs internal constructor(private val server: MinecraftServer, directory: Path) {
    internal val registry = NpcRegistry(directory).also { it.load() }
    private val instances = HashMap<UUID, NpcEntity>()
    private val bodies = HashSet<UUID>()
    private val tickets = HashMap<UUID, Pair<ServerLevel, ChunkPos>>()
    private var started = false

    /** Every NPC of the world, loaded or not. */
    val all: List<NpcRef> get() = registry.all.map { NpcRef(it.uuid) }

    operator fun get(uuid: UUID): NpcRef? = registry[uuid]?.let { NpcRef(it.uuid) }

    /** The NPC with the author's [id], such as `village:blacksmith`. */
    operator fun get(id: String): NpcRef? = registry[id]?.let { NpcRef(it.uuid) }

    /** The NPCs within [radius] blocks of [center] in [level], nearest first, loaded or not. */
    fun near(level: Level, center: Vec3, radius: Double): List<NpcRef> =
        registry.near(level.dimension(), center, radius).map { NpcRef(it.uuid) }

    /** Whether the NPC's body stands in a loaded chunk. */
    fun isLoaded(uuid: UUID): Boolean = uuid in bodies

    /** The NPC's entity, built from its record when it has nobody; null once it is despawned. */
    fun resolve(uuid: UUID): NpcEntity? {
        instances[uuid]?.let { return it }
        val record = registry[uuid] ?: return null
        return build(record)?.also { instances[uuid] = it }
    }

    /** The living NPC with [id], whether it has a body or not. */
    internal fun alive(id: String): NpcEntity? = registry[id]?.takeUnless { it.isDead }?.let { resolve(it.uuid) }

    /** Gives a new [body] its record and adds it to its level. */
    internal fun spawn(body: NpcEntity, id: String?) {
        registry.put(recordOf(body, id), saveEntity(body))
        instances[body.uuid] = body
        bodies += body.uuid
        if (!body.level().addFreshEntity(body)) bodies -= body.uuid
    }

    fun teleport(uuid: UUID, level: ServerLevel, position: Vec3, yaw: Float, pitch: Float) {
        val npc = resolve(uuid) ?: return
        if (uuid in bodies) {
            npc.teleportTo(level, position.x, position.y, position.z, emptySet(), yaw, pitch)
            return
        }
        val moved = if (npc.level() === level) npc else {
            val copy = EntityType.create(saveEntity(npc), level).orElse(null) as? NpcEntity ?: return
            forget(npc)
            instances[uuid] = copy
            copy
        }
        moved.moveTo(position.x, position.y, position.z, yaw, pitch)
        moved.yHeadRot = yaw
        val record = capture(moved)
        if (level.areEntitiesLoaded(record.chunk)) embody(record)
    }

    /** Removes the NPC for good, record and entity. */
    fun despawn(uuid: UUID) {
        registry.remove(uuid)
        releaseTicket(uuid)
        val npc = instances.remove(uuid) ?: return
        if (bodies.remove(uuid)) npc.discard() else forget(npc)
    }

    internal fun onJoined(body: NpcEntity) {
        val current = instances[body.uuid]
        if (current === body) {
            bodies += body.uuid
            return
        }
        val record = registry[body.uuid]
        if (record != null && (record.isDead || body.uuid in bodies && current?.level() === body.level())) {
            HollowEngine.LOGGER.warn("Discarding a second body of NPC {}", body.uuid)
            server.execute { body.discard() }
            return
        }
        if (record == null) registry.adopt(recordOf(body, null), saveEntity(body))
        if (current != null && body.uuid !in bodies) forget(current)
        instances[body.uuid] = body
        bodies += body.uuid
    }

    internal fun onRemoved(body: NpcEntity, reason: Entity.RemovalReason) {
        if (instances[body.uuid] !== body) return
        instances.remove(body.uuid)
        bodies.remove(body.uuid)
        releaseTicket(body.uuid)
        val died = reason == Entity.RemovalReason.KILLED || body.isDeadOrDying
        if (reason == Entity.RemovalReason.DISCARDED || died && !body.keepAfterDeath) {
            registry.remove(body.uuid)
        } else {
            capture(body, isDead = died)
        }
    }

    internal fun onChunkEntitiesLoaded(level: ServerLevel, chunk: Long) {
        registry.inChunk(level.dimension(), chunk).filter { !it.isDead && it.uuid !in bodies }.forEach(::embody)
    }

    internal fun onLevelSaved(level: ServerLevel) {
        instances.values.filter { it.level() === level }.forEach(::capture)
        registry.flush()
    }

    /** Moves the chunk tickets after the NPCs that keep chunks loaded, the ones without a body included. */
    internal fun tick() {
        if (!started) {
            started = true
            registry.all.filter { it.isForceLoaded && !it.isDead }.forEach { resolve(it.uuid) }
        }
        instances.values.forEach(::updateTicket)
    }

    internal fun close() {
        registry.flush()
        instances.filterKeys { it !in bodies }.values.forEach(::forget)
        instances.clear()
        bodies.clear()
    }

    private fun embody(record: NpcRecord) {
        val level = server.getLevel(record.dimension) ?: return
        val npc = instances[record.uuid]?.takeIf { it.level() === level } ?: build(record, level) ?: return
        instances.put(record.uuid, npc)?.takeIf { it !== npc }?.let(::forget)
        bodies += record.uuid
        if (!level.addFreshEntity(npc)) {
            bodies -= record.uuid
            HollowEngine.LOGGER.warn("Failed to add the body of NPC {} to {}", record.uuid, record.dimension.location())
        }
    }

    private fun build(record: NpcRecord, level: ServerLevel? = server.getLevel(record.dimension)): NpcEntity? {
        val nbt = registry.entityNbt(record.uuid) ?: return null
        return EntityType.create(nbt, level ?: return null).orElse(null) as? NpcEntity
    }

    /** Let's go of an instance that never joined a level: its attachments live outside the level state. */
    private fun forget(npc: NpcEntity) {
        AttachmentRegistry.attachmentsOrNull(npc)?.scope?.cancel()
    }

    private fun capture(npc: NpcEntity, isDead: Boolean = registry[npc.uuid]?.isDead == true): NpcRecord {
        val record = recordOf(npc, registry[npc.uuid]?.id).copy(isDead = isDead)
        registry.put(record, saveEntity(npc))
        return record
    }

    private fun recordOf(npc: NpcEntity, id: String?) =
        NpcRecord(npc.uuid, id, npc.level().dimension(), npc.position(), isForceLoaded = npc.isForceLoaded)

    /** The entity's NBT with its type id, which chunks would add but never get to write for NPCs. */
    private fun saveEntity(npc: NpcEntity): CompoundTag = npc.saveWithoutId(CompoundTag()).apply {
        putString(Entity.ID_TAG, BuiltInRegistries.ENTITY_TYPE.getKey(npc.type).toString())
    }

    private fun updateTicket(npc: NpcEntity) {
        val level = npc.level() as? ServerLevel
        val wanted = if (npc.keepsChunksLoaded && !npc.isRemoved && level != null) level to npc.chunkPosition() else null
        val held = tickets[npc.uuid]
        if (held?.first === wanted?.first && held?.second == wanted?.second) return
        wanted?.let { (wantedLevel, chunk) -> wantedLevel.chunkSource.addRegionTicket(TICKET, chunk, TICKET_RADIUS, npc.uuid) }
        releaseTicket(npc.uuid)
        wanted?.let { tickets[npc.uuid] = it }
    }

    private fun releaseTicket(uuid: UUID) {
        val (level, chunk) = tickets.remove(uuid) ?: return
        level.chunkSource.removeRegionTicket(TICKET, chunk, TICKET_RADIUS, uuid)
    }

    private companion object {
        val TICKET: TicketType<UUID> = TicketType.create("hollowengine_npc", UUID::compareTo)

        /** Keeps the NPC's chunk and its neighbors entity-ticking, so it does not stop at a chunk border. */
        const val TICKET_RADIUS = 3
    }
}

val MinecraftServer.npcs: ServerNpcs get() = runtimeContext.npcs
