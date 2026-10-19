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
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The NPCs of one world, kept outside it like player data: `<uuid>.dat` per NPC with its [NpcRecord] and entity
 * NBT, plus `index.dat` with the records alone. Loading reads only the index; an NPC's NBT is read the first time
 * it is needed, and [flush] writes what changed.
 */
class NpcRegistry(private val directory: Path) {
    private val records = HashMap<UUID, NpcRecord>()
    private val byId = HashMap<String, UUID>()
    private val byChunk = HashMap<ResourceKey<Level>, Long2ObjectOpenHashMap<MutableSet<UUID>>>()
    private val entities = HashMap<UUID, CompoundTag>()
    private val dirty = linkedSetOf<UUID>()
    private val deleted = linkedSetOf<UUID>()
    private var indexChanged = false

    val size: Int get() = records.size

    val all: Collection<NpcRecord> get() = records.values

    /** Reads index, catching up with record files it does not list and dropping entries whose file is gone. */
    fun load() {
        records.clear()
        byId.clear()
        byChunk.clear()
        entities.clear()
        dirty.clear()
        deleted.clear()

        val stored = storedUuids()
        val saved = readTag(directory.resolve(INDEX_FILE))?.getList(RECORDS_KEY, Tag.TAG_COMPOUND.toInt())
            ?.mapNotNull { NpcRecord.load(it as CompoundTag) }
        saved.orEmpty().filter { it.uuid in stored }.forEach(::index)
        stored.filter { it !in records }.forEach { uuid -> readFile(uuid)?.let { (record, _) -> index(record) } }
        indexChanged = saved == null || saved.size != records.size
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

    /** The NPC's saved entity NBT, read from its file the first time. */
    fun entityNbt(uuid: UUID): CompoundTag? {
        entities[uuid]?.let { return it }
        if (uuid !in records) return null
        return readFile(uuid)?.second?.also { entities[uuid] = it }
    }

    /**
     * Adds or updates a record together with its entity NBT. A dead NPC that held the record's id gives way.
     *
     * @throws IllegalStateException when a living NPC already has the record's id.
     */
    fun put(record: NpcRecord, entity: CompoundTag) {
        records[record.uuid]?.let { check(it.id == record.id) { "NPC ${record.uuid} cannot change its id" } }
        record.id?.let { id ->
            val holder = byId[id]?.takeIf { it != record.uuid }?.let(records::get)
            check(holder == null || holder.isDead) { "NPC id '$id' already belongs to ${holder?.uuid}" }
            holder?.let { remove(it.uuid) }
        }
        if (records[record.uuid] != record) {
            unindex(record.uuid)
            index(record)
            indexChanged = true
        }
        entities[record.uuid] = entity
        deleted -= record.uuid
        dirty += record.uuid
    }

    /** The living NPC with [id], or the one [create] makes when there is none. */
    fun obtain(id: String, create: () -> Pair<NpcRecord, CompoundTag>): NpcRecord {
        get(id)?.takeUnless { it.isDead }?.let { return it }
        val (record, entity) = create()
        require(record.id == id) { "A record created for '$id' has id '${record.id}'" }
        put(record, entity)
        return record
    }

    /** The record of a body that appeared without one, such as a summoned NPC; a known UUID keeps its record. */
    fun adopt(record: NpcRecord, entity: CompoundTag): NpcRecord {
        get(record.uuid)?.let { return it }
        put(record, entity)
        return record
    }

    fun remove(uuid: UUID): Boolean {
        if (!unindex(uuid)) return false
        indexChanged = true
        entities -= uuid
        dirty -= uuid
        deleted += uuid
        return true
    }

    fun flush() {
        deleted.forEach { uuid ->
            Files.deleteIfExists(file(uuid))
            Files.deleteIfExists(directory.resolve("$uuid$BACKUP_EXTENSION"))
        }
        deleted.clear()
        dirty.forEach { uuid ->
            val record = records[uuid] ?: return@forEach
            val entity = entities[uuid] ?: return@forEach
            writeTag("$uuid", CompoundTag().apply {
                put(RECORD_KEY, record.save())
                put(ENTITY_KEY, entity)
            })
        }
        dirty.clear()
        if (indexChanged) {
            writeTag(INDEX_NAME, CompoundTag().apply {
                put(RECORDS_KEY, ListTag().apply { records.values.forEach { add(it.save()) } })
            })
        }
        indexChanged = false
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

    private fun file(uuid: UUID): Path = directory.resolve("$uuid$EXTENSION")

    private fun storedUuids(): Set<UUID> {
        if (!directory.exists()) return emptySet()
        return directory.listDirectoryEntries("*$EXTENSION").mapNotNullTo(linkedSetOf()) { file ->
            runCatching { UUID.fromString(file.name.removeSuffix(EXTENSION)) }.getOrNull()
        }
    }

    private fun readFile(uuid: UUID): Pair<NpcRecord, CompoundTag>? {
        val tag = readTag(file(uuid)) ?: readTag(directory.resolve("$uuid$BACKUP_EXTENSION")) ?: return null
        val record = NpcRecord.load(tag.getCompound(RECORD_KEY))?.takeIf { it.uuid == uuid } ?: return null
        return record to tag.getCompound(ENTITY_KEY)
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

    /** Writes the way the game writes player data: a temporary file first, the previous version kept. */
    private fun writeTag(name: String, tag: CompoundTag) {
        try {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "$name-", TEMPORARY_EXTENSION)
            NbtIo.writeCompressed(tag, temporary)
            Util.safeReplaceFile(
                directory.resolve("$name$EXTENSION"),
                temporary,
                directory.resolve("$name$BACKUP_EXTENSION")
            )
        } catch (exception: Exception) {
            HollowEngine.LOGGER.error("Failed to write NPC data {} to {}", name, directory, exception)
        }
    }

    private companion object {
        const val INDEX_NAME = "index"
        const val INDEX_FILE = "$INDEX_NAME.dat"
        const val RECORDS_KEY = "records"
        const val RECORD_KEY = "record"
        const val ENTITY_KEY = "entity"
        const val EXTENSION = ".dat"
        const val BACKUP_EXTENSION = ".dat_old"
        const val TEMPORARY_EXTENSION = ".tmp"
    }
}
