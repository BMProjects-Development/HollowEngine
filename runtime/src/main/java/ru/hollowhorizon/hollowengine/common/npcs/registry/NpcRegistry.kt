package ru.hollowhorizon.hollowengine.common.npcs.registry

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.Util
import net.minecraft.core.SectionPos
import net.minecraft.nbt.*
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.HollowEngine
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * The NPCs of one world, kept outside it like player data: one file with every NPC's [NpcRecord] and entity NBT,
 * replaced as a whole. [flush] hands a snapshot to a background writer, so saving does not hold up the server.
 */
class NpcRegistry(private val file: Path) {
    private val records = HashMap<UUID, NpcRecord>()
    private val byId = HashMap<String, UUID>()
    private val byChunk = HashMap<ResourceKey<Level>, Long2ObjectOpenHashMap<MutableSet<UUID>>>()

    /** The entity NBT of every NPC. A tag is never changed once it is here, so snapshots share it with the writer. */
    private val entities = HashMap<UUID, CompoundTag>()
    private var changed = false

    /** The newest snapshot no write has taken yet; snapshots of one save that wait together are written once. */
    private val pending = AtomicReference<CompoundTag?>()
    private var writer: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    @Volatile
    private var lastWriteFailed = false

    private val backup: Path get() = file.resolveSibling(file.name + BACKUP_SUFFIX)

    val size: Int get() = records.size

    val all: Collection<NpcRecord> get() = records.values

    /** Reads the file, or its previous version when a crash left the current one missing or broken. */
    fun load() {
        records.clear()
        byId.clear()
        byChunk.clear()
        entities.clear()
        changed = false

        val tag = readTag(file) ?: readTag(backup) ?: return
        tag.getList(NPCS_KEY, Tag.TAG_COMPOUND.toInt()).forEach { entry ->
            entry as CompoundTag
            val record = NpcRecord.load(entry.getCompound(RECORD_KEY)) ?: return@forEach
            index(record)
            entities[record.uuid] = entry.getCompound(ENTITY_KEY)
        }
    }

    operator fun get(uuid: UUID): NpcRecord? = records[uuid]

    operator fun get(id: String): NpcRecord? = byId[id]?.let(records::get)

    fun inChunk(dimension: ResourceKey<Level>, chunk: Long): List<NpcRecord> =
        byChunk[dimension]?.get(chunk)?.mapNotNull(records::get).orEmpty()

    /** The records within [radius] blocks of [center], nearest first. */
    fun near(dimension: ResourceKey<Level>, center: Vec3, radius: Double): List<NpcRecord> {
        val chunks = byChunk[dimension] ?: return emptyList()
        val minX = SectionPos.blockToSectionCoord(center.x - radius)
        val maxX = SectionPos.blockToSectionCoord(center.x + radius)
        val minZ = SectionPos.blockToSectionCoord(center.z - radius)
        val maxZ = SectionPos.blockToSectionCoord(center.z + radius)
        val area = (maxX - minX + 1).toLong() * (maxZ - minZ + 1)
        val candidates = if (area > chunks.size) {
            chunks.values.flatten()
        } else {
            (minX..maxX).flatMap { x -> (minZ..maxZ).flatMap { z -> chunks.get(ChunkPos.asLong(x, z)).orEmpty() } }
        }
        return candidates.mapNotNull(records::get).filter { it.position.distanceToSqr(center) <= radius * radius }
            .sortedBy { it.position.distanceToSqr(center) }
    }

    /** A copy of the NPC's saved entity NBT. */
    fun entityNbt(uuid: UUID): CompoundTag? = entities[uuid]?.copy()

    /**
     * Adds or updates a record together with its entity NBT, which the registry takes over: the caller must not
     * change [entity] afterwards. A dead NPC that held the record's id gives way.
     *
     * @return the dead NPC that gave way, if any.
     * @throws IllegalStateException when a living NPC already has the record's id.
     */
    fun put(record: NpcRecord, entity: CompoundTag): NpcRecord? {
        records[record.uuid]?.let { check(it.id == record.id) { "NPC ${record.uuid} cannot change its id" } }
        val replaced = record.id?.let { id ->
            val holder = byId[id]?.takeIf { it != record.uuid }?.let(records::get)
            check(holder == null || holder.isDead) { "NPC id '$id' already belongs to ${holder?.uuid}" }
            holder?.also { remove(it.uuid) }
        }
        if (records[record.uuid] != record) {
            unindex(record.uuid)
            index(record)
            changed = true
        }
        if (entities.put(record.uuid, entity) != entity) changed = true
        return replaced
    }

    /** The record of a body that appeared without one, such as a summoned NPC; a known UUID keeps its record. */
    fun adopt(record: NpcRecord, entity: CompoundTag): NpcRecord {
        get(record.uuid)?.let { return it }
        put(record, entity)
        return record
    }

    fun remove(uuid: UUID): Boolean {
        if (!unindex(uuid)) return false
        entities -= uuid
        changed = true
        return true
    }

    /** Hands what changed to the background writer; a write that failed is repeated by the next flush. */
    fun flush() {
        if (!changed && !lastWriteFailed) return
        changed = false
        pending.set(snapshot())
        writer = writer.handleAsync({ _, _ -> writePending() }, Util.ioPool())
    }

    /** Waits for the background writer, then writes what is left on the calling thread. */
    fun close() {
        writer.handle { _, _ -> }.join()
        if (changed || lastWriteFailed || pending.get() != null) {
            changed = false
            pending.set(snapshot())
            writePending()
        }
    }

    private fun snapshot(): CompoundTag = CompoundTag().apply {
        put(NPCS_KEY, ListTag().apply {
            records.values.forEach { record ->
                add(CompoundTag().apply {
                    put(RECORD_KEY, record.save())
                    put(ENTITY_KEY, entities.getValue(record.uuid))
                })
            }
        })
    }

    private fun writePending() {
        val tag = pending.getAndSet(null) ?: return
        lastWriteFailed = !writeTag(tag)
    }

    private fun index(record: NpcRecord) {
        records[record.uuid] = record
        record.id?.let { byId[it] = record.uuid }
        byChunk.getOrPut(record.dimension, ::Long2ObjectOpenHashMap)
            .computeIfAbsent(record.chunk) { linkedSetOf() } += record.uuid
    }

    private fun unindex(uuid: UUID): Boolean {
        val record = records.remove(uuid) ?: return false
        record.id?.let { if (byId[it] == uuid) byId.remove(it) }
        byChunk[record.dimension]?.let { chunks ->
            chunks.get(record.chunk)?.let { uuids ->
                uuids -= uuid
                if (uuids.isEmpty()) chunks.remove(record.chunk)
            }
            if (chunks.isEmpty()) byChunk.remove(record.dimension)
        }
        return true
    }

    private fun readTag(file: Path): CompoundTag? {
        if (!file.exists()) return null
        return try {
            NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        } catch (exception: Exception) {
            HollowEngine.LOGGER.warn("Failed to read NPC data from {}", file, exception)
            null
        }
    }

    /**
     * Writes the way the game writes player data: a temporary file first, the previous version kept.
     *
     * @return whether the data reached the disk.
     */
    private fun writeTag(tag: CompoundTag): Boolean {
        var temporary: Path? = null
        val written = try {
            Files.createDirectories(file.parent)
            temporary = Files.createTempFile(file.parent, file.name + "-", TEMPORARY_SUFFIX)
            NbtIo.writeCompressed(tag, temporary)
            val replaced = Util.safeReplaceOrMoveFile(file, temporary, backup, false)
            if (!replaced) HollowEngine.LOGGER.error("Failed to replace NPC data {}", file)
            replaced
        } catch (exception: Exception) {
            HollowEngine.LOGGER.error("Failed to write NPC data {}", file, exception)
            false
        }
        if (!written) temporary?.let { runCatching { Files.deleteIfExists(it) } }
        return written
    }

    private companion object {
        const val NPCS_KEY = "npcs"
        const val RECORD_KEY = "record"
        const val ENTITY_KEY = "entity"
        const val BACKUP_SUFFIX = "_old"
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}
