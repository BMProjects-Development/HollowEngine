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
import net.minecraft.world.phys.AABB
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
class ServerNpcs internal constructor(private val server: MinecraftServer, file: Path) {
    internal val registry = NpcRegistry(file).also { it.load() }
    private val instances = HashMap<UUID, NpcEntity>()
    private val bodies = HashSet<UUID>()
    private val tickets = HashMap<UUID, Pair<ServerLevel, ChunkPos>>()

    /** NPCs captured this tick as their riding tree began to unload; their own removal must not overwrite it. */
    private val unloading = HashSet<UUID>()
    private var started = false

    /** Every NPC of the world, loaded or not. */
    val all: List<NpcRef> get() = registry.all.map { NpcRef(it.uuid) }

    operator fun get(uuid: UUID): NpcRef? = registry[uuid]?.let { NpcRef(it.uuid) }

    /** The NPC with the author's [id], such as `village:blacksmith`. */
    operator fun get(id: String): NpcRef? = registry[id]?.let { NpcRef(it.uuid) }

    /**
     * The NPCs within [radius] blocks of [center] in [level], nearest first, loaded or not. Bodies are found where
     * they stand now, the others where they were saved.
     */
    fun near(level: Level, center: Vec3, radius: Double): List<NpcRef> {
        val radiusSqr = radius * radius
        val loaded = level.getEntitiesOfClass(NpcEntity::class.java, AABB(center, center).inflate(radius)) {
            instances[it.uuid] === it && it.uuid in bodies && it.position().distanceToSqr(center) <= radiusSqr
        }.map { it.uuid to it.position() }
        val unloaded = registry.near(level.dimension(), center, radius).filter { it.uuid !in bodies }
            .map { it.uuid to it.position }
        return (loaded + unloaded).sortedBy { (_, position) -> position.distanceToSqr(center) }
            .map { (uuid, _) -> NpcRef(uuid) }
    }

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
        registry.put(recordOf(body, id), saveEntity(body))?.let { drop(it.uuid) }
        instances[body.uuid] = body
        bodies += body.uuid
        if (!addToLevel(body.level() as ServerLevel, body)) bodies -= body.uuid
    }

    fun teleport(uuid: UUID, level: ServerLevel, position: Vec3, yaw: Float, pitch: Float) {
        val npc = resolve(uuid) ?: return
        if (uuid in bodies) {
            npc.teleportTo(level, position.x, position.y, position.z, emptySet(), yaw, pitch)
            return
        }
        val moved = if (npc.level() === level) npc else {
            val copy = create(saveEntity(npc), level) ?: return
            forget(npc)
            instances[uuid] = copy
            copy
        }
        moved.savedVehicle = null
        moved.moveTo(position.x, position.y, position.z, yaw, pitch)
        moved.yHeadRot = yaw
        val record = capture(moved)
        if (level.areEntitiesLoaded(record.chunk)) embody(record)
    }

    /** Removes the NPC for good, record and entity. */
    fun despawn(uuid: UUID) {
        registry.remove(uuid)
        drop(uuid)
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

    /**
     * Called for every entity leaving a level, before its attachments are let go. A chunk unloads a riding tree
     * passengers first, detaching each from its vehicle, so the NPCs of the tree are captured at the first
     * removal, while the tree is still whole.
     */
    internal fun onEntityRemoved(entity: Entity, reason: Entity.RemovalReason) {
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            entity.rootVehicle.selfAndPassengers.forEach { member ->
                if (member !is NpcEntity || instances[member.uuid] !== member || member.isDeadOrDying) return@forEach
                if (unloading.add(member.uuid)) capture(member, isDead = false)
            }
        }
        if (entity is NpcEntity) onRemoved(entity, reason)
    }

    private fun onRemoved(body: NpcEntity, reason: Entity.RemovalReason) {
        val captured = unloading.remove(body.uuid)
        if (instances[body.uuid] !== body) return
        instances.remove(body.uuid)
        bodies.remove(body.uuid)
        releaseTicket(body.uuid)
        val died = reason == Entity.RemovalReason.KILLED || body.isDeadOrDying
        when {
            reason == Entity.RemovalReason.DISCARDED || died && !body.keepAfterDeath -> registry.remove(body.uuid)
            died || !captured -> capture(body, isDead = died)
        }
    }

    /** Brings back the NPCs of the chunk, then seats the ones that rode something, NPCs included. */
    internal fun onChunkEntitiesLoaded(level: ServerLevel, chunk: Long) {
        registry.inChunk(level.dimension(), chunk).filter { !it.isDead && it.uuid !in bodies }
            .mapNotNull(::embody)
            .forEach(::remount)
    }

    internal fun onLevelSaved(level: ServerLevel) {
        instances.values.filter { it.level() === level }.forEach(::capture)
        registry.flush()
    }

    /** Moves the chunk tickets after the NPCs that keep chunks loaded, the ones without a body included. */
    internal fun tick() {
        unloading.clear()
        if (!started) {
            started = true
            registry.all.filter { it.isForceLoaded && !it.isDead }.forEach { resolve(it.uuid) }
        }
        instances.values.forEach(::updateTicket)
    }

    internal fun close() {
        registry.close()
        instances.filterKeys { it !in bodies }.values.forEach(::forget)
        instances.clear()
        bodies.clear()
    }

    private fun embody(record: NpcRecord): NpcEntity? {
        val level = server.getLevel(record.dimension) ?: return null
        val npc = instances[record.uuid]?.takeIf { it.level() === level } ?: build(record, level) ?: return null
        instances.put(record.uuid, npc)?.takeIf { it !== npc }?.let(::forget)
        bodies += record.uuid
        if (!addToLevel(level, npc)) {
            bodies -= record.uuid
            HollowEngine.LOGGER.warn("Failed to add the body of NPC {} to {}", record.uuid, record.dimension.location())
            return null
        }
        return npc
    }

    /** Adds [npc] and the passengers it carries; false when the NPC itself is refused. */
    private fun addToLevel(level: ServerLevel, npc: NpcEntity): Boolean {
        if (!level.addFreshEntity(npc)) return false
        npc.indirectPassengers.forEach { level.addFreshEntity(it) }
        return true
    }

    /** Seats [npc] on the vehicle it rode when it was saved, if that vehicle is in the level. */
    private fun remount(npc: NpcEntity) {
        val vehicle = npc.savedVehicle?.let { (npc.level() as ServerLevel).getEntity(it) }
        npc.savedVehicle = null
        if (vehicle != null && !npc.isPassenger && !vehicle.isRemoved) npc.startRiding(vehicle, true)
    }

    private fun build(record: NpcRecord, level: ServerLevel? = server.getLevel(record.dimension)): NpcEntity? {
        val nbt = registry.entityNbt(record.uuid) ?: return null
        return create(nbt, level ?: return null)
    }

    /** The NPC of [nbt] with the passengers it carries, outside any level. */
    private fun create(nbt: CompoundTag, level: ServerLevel): NpcEntity? =
        EntityType.loadEntityRecursive(nbt, level) { it } as? NpcEntity

    /** Lets go of the NPC's entity and chunk ticket; a body leaves its level. */
    private fun drop(uuid: UUID) {
        releaseTicket(uuid)
        val npc = instances.remove(uuid) ?: return
        if (bodies.remove(uuid)) npc.discard() else forget(npc)
    }

    /** Lets go of an instance that never joined a level: its attachments live outside the level state. */
    private fun forget(npc: NpcEntity) {
        npc.selfAndPassengers.forEach { AttachmentRegistry.attachmentsOrNull(it)?.scope?.cancel() }
    }

    private fun capture(npc: NpcEntity, isDead: Boolean = registry[npc.uuid]?.isDead == true): NpcRecord {
        val record = recordOf(npc, registry[npc.uuid]?.id).copy(isDead = isDead)
        registry.put(record, saveEntity(npc))
        return record
    }

    /** A rider is recorded where its root vehicle stands, so loading the vehicle's chunk brings both back. */
    private fun recordOf(npc: NpcEntity, id: String?): NpcRecord {
        val root = npc.rootVehicle
        val position = Vec3(root.x, npc.y, root.z)
        return NpcRecord(npc.uuid, id, npc.level().dimension(), position, isForceLoaded = npc.isForceLoaded)
    }

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
