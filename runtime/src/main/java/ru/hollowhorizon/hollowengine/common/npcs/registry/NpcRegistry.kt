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

    /** NPCs whose file may disagree with the index on disk. */
    private val unindexed = linkedSetOf<UUID>()

    /** The NPCs `journal.dat` names as unindexed now. */
    private val journaled = linkedSetOf<UUID>()

    /** The NPCs `journal.dat` names as removed now; their leftover files are not NPCs anymore. */
    private val journaledRemovals = linkedSetOf<UUID>()
    private var indexChanged = false

    val size: Int get() = records.size

    val all: Collection<NpcRecord> get() = records.values

    /**
     * Reads index, catching up with record files it does not list and dropping entries whose file is gone.
     * Files of NPCs the journal names as removed are deleted again rather than read.
     */
    fun load() {
        records.clear()
        byId.clear()
        byChunk.clear()
        entities.clear()
        dirty.clear()
        deleted.clear()
        unindexed.clear()
        journaled.clear()
        journaledRemovals.clear()

        val journal = readTag(JOURNAL_NAME)
        journaled += journal?.uuids(UNINDEXED_KEY).orEmpty()
        journaledRemovals += journal?.uuids(REMOVED_KEY).orEmpty()
        val stored = storedUuids()
        deleted += stored.filter { it in journaledRemovals }
        val present = stored - journaledRemovals
        val saved = readTag(directory.resolve("$INDEX_NAME$EXTENSION"))
            ?.getList(RECORDS_KEY, Tag.TAG_COMPOUND.toInt())
            ?.mapNotNull { NpcRecord.load(it as CompoundTag) }
        saved.orEmpty().filter { it.uuid in present && it.uuid !in journaled }.forEach(::index)
        present.filter { it !in records }.forEach { uuid -> readFile(uuid)?.let { (record, _) -> index(record) } }
        unindexed += journaled
        indexChanged = saved == null || saved.size != records.size || journal != null
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
            unindexed += record.uuid
            indexChanged = true
        }
        entities[record.uuid] = entity
        deleted -= record.uuid
        dirty += record.uuid
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
        indexChanged = true
        entities -= uuid
        dirty -= uuid
        deleted += uuid
        return true
    }

    /**
     * Writes what changed; whatever fails to be written stays pending for the next flush.
     */
    fun flush() {
        if (!journalCoversPending()) {
            if (!writeJournal(journaled + unindexed, deleted)) return
            journaled += unindexed
            journaledRemovals.clear()
            journaledRemovals += deleted
        }
        deleted.removeIf { deleteFiles("$it") }
        dirty.removeIf { uuid ->
            val record = records[uuid] ?: return@removeIf true
            val entity = entities[uuid] ?: return@removeIf true
            writeTag("$uuid", CompoundTag().apply {
                put(RECORD_KEY, record.save())
                put(ENTITY_KEY, entity)
            })
        }
        if (indexChanged && writeIndex()) indexChanged = false
        if (indexChanged) return
        unindexed.retainAll(dirty)
        val journalNeeded = unindexed.isNotEmpty() || deleted.isNotEmpty()
        val journalExists = journaled.isNotEmpty() || journaledRemovals.isNotEmpty()
        if (!journalNeeded && journalExists && deleteFiles(JOURNAL_NAME)) {
            journaled.clear()
            journaledRemovals.clear()
        }
    }

    /**
     * Whether the journal on disk stays true whatever part of the pending writes a crash interrupts: it names
     * every file about to get a record the index lacks, every removal, and no NPC about to be written again.
     */
    private fun journalCoversPending(): Boolean =
        journaledRemovals.containsAll(deleted) && dirty.none { it in journaledRemovals || it in unindexed && it !in journaled }

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

    /** The NPCs with a file, counting the ones a crash left with only the previous version. */
    private fun storedUuids(): Set<UUID> {
        if (!directory.exists()) return emptySet()
        return directory.listDirectoryEntries("*{$EXTENSION,$BACKUP_EXTENSION}").mapNotNullTo(linkedSetOf()) { file ->
            val name = file.name.removeSuffix(BACKUP_EXTENSION).removeSuffix(EXTENSION)
            runCatching { UUID.fromString(name) }.getOrNull()
        }
    }

    private fun readFile(uuid: UUID): Pair<NpcRecord, CompoundTag>? {
        val tag = readTag("$uuid") ?: return null
        val record = NpcRecord.load(tag.getCompound(RECORD_KEY))?.takeIf { it.uuid == uuid } ?: return null
        return record to tag.getCompound(ENTITY_KEY)
    }

    private fun writeIndex(): Boolean = writeTag(INDEX_NAME, CompoundTag().apply {
        put(RECORDS_KEY, ListTag().apply { records.values.forEach { add(it.save()) } })
    })

    private fun writeJournal(unindexed: Collection<UUID>, removed: Collection<UUID>): Boolean =
        writeTag(JOURNAL_NAME, CompoundTag().apply {
            put(UNINDEXED_KEY, ListTag().apply { unindexed.forEach { add(NbtUtils.createUUID(it)) } })
            put(REMOVED_KEY, ListTag().apply { removed.forEach { add(NbtUtils.createUUID(it)) } })
        })

    private fun CompoundTag.uuids(key: String): List<UUID> =
        getList(key, Tag.TAG_INT_ARRAY.toInt()).map(NbtUtils::loadUUID)

    /** Reads [name], or its previous version when a crash left the current one missing or broken. */
    private fun readTag(name: String): CompoundTag? =
        readTag(directory.resolve("$name$EXTENSION")) ?: readTag(directory.resolve("$name$BACKUP_EXTENSION"))

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
    private fun writeTag(name: String, tag: CompoundTag): Boolean {
        var temporary: Path? = null
        val written = try {
            Files.createDirectories(directory)
            temporary = Files.createTempFile(directory, "$name-", TEMPORARY_EXTENSION)
            NbtIo.writeCompressed(tag, temporary)
            val replaced = Util.safeReplaceOrMoveFile(
                directory.resolve("$name$EXTENSION"),
                temporary,
                directory.resolve("$name$BACKUP_EXTENSION"),
                false
            )
            if (!replaced) HollowEngine.LOGGER.error("Failed to replace NPC data {} in {}", name, directory)
            replaced
        } catch (exception: Exception) {
            HollowEngine.LOGGER.error("Failed to write NPC data {} to {}", name, directory, exception)
            false
        }
        if (!written) temporary?.let { runCatching { Files.deleteIfExists(it) } }
        return written
    }

    private fun deleteFiles(name: String): Boolean {
        return try {
            Files.deleteIfExists(directory.resolve("$name$BACKUP_EXTENSION"))
            Files.deleteIfExists(directory.resolve("$name$EXTENSION"))
            true
        } catch (exception: Exception) {
            HollowEngine.LOGGER.error("Failed to delete NPC data {} from {}", name, directory, exception)
            false
        }
    }

    private companion object {
        const val INDEX_NAME = "index"
        const val JOURNAL_NAME = "journal"
        const val RECORDS_KEY = "records"
        const val RECORD_KEY = "record"
        const val ENTITY_KEY = "entity"
        const val UNINDEXED_KEY = "unindexed"
        const val REMOVED_KEY = "removed"
        const val EXTENSION = ".dat"
        const val BACKUP_EXTENSION = ".dat_old"
        const val TEMPORARY_EXTENSION = ".tmp"
    }
}
