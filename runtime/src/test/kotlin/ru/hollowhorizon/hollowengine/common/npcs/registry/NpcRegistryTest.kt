package ru.hollowhorizon.hollowengine.common.npcs.registry

import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NpcRegistryTest {
    @TempDir
    lateinit var directory: Path

    private val uuid = UUID.randomUUID()

    @Test
    fun `an index left behind by a crash gives way to the NPC files`() {
        val registry = NpcRegistry(directory)
        registry.put(record(OVERWORLD, Vec3(0.0, 64.0, 0.0)), entity(1))
        registry.flush()

        blockReplacing(INDEX)
        registry.put(record(NETHER, Vec3(100.0, 70.0, 100.0)), entity(2))
        registry.flush()

        val restarted = NpcRegistry(directory).also { it.load() }
        assertEquals(NETHER, restarted[uuid]?.dimension)
        assertEquals(2, restarted.entityNbt(uuid)?.getInt(VERSION))
    }

    @Test
    fun `a write that failed is retried by the next flush`() {
        val registry = NpcRegistry(directory)
        registry.put(record(OVERWORLD, Vec3(0.0, 64.0, 0.0)), entity(1))
        registry.flush()

        val blocked = blockReplacing("$uuid")
        registry.put(record(OVERWORLD, Vec3(0.0, 64.0, 0.0)), entity(2))
        registry.flush()
        blocked.toFile().deleteRecursively()
        registry.flush()

        assertEquals(2, NpcRegistry(directory).also { it.load() }.entityNbt(uuid)?.getInt(VERSION))
    }

    @Test
    fun `the index catching up removes the journal`() {
        val registry = NpcRegistry(directory)
        registry.put(record(OVERWORLD, Vec3(0.0, 64.0, 0.0)), entity(1))
        registry.flush()

        val blocked = blockReplacing(INDEX)
        registry.put(record(NETHER, Vec3(0.0, 64.0, 0.0)), entity(2))
        registry.flush()
        blocked.toFile().deleteRecursively()
        registry.flush()

        assertFalse(directory.resolve("journal.dat").exists())
        assertEquals(NETHER, NpcRegistry(directory).also { it.load() }[uuid]?.dimension)
    }

    @Test
    fun `an NPC whose files could not be deleted stays removed`() {
        val registry = NpcRegistry(directory)
        registry.put(record(OVERWORLD, Vec3(0.0, 64.0, 0.0)), entity(1))
        registry.flush()

        val file = directory.resolve("$uuid.dat").toFile()
        file.setWritable(false)
        try {
            registry.remove(uuid)
            registry.flush()
            assertNull(NpcRegistry(directory).also { it.load() }[uuid])
        } finally {
            file.setWritable(true)
        }
    }

    private fun record(dimension: ResourceKey<Level>, position: Vec3) = NpcRecord(uuid, "test:npc", dimension, position)

    private fun entity(version: Int) = CompoundTag().apply { putInt(VERSION, version) }

    /** Makes the next replacement of [name] fail: its backup cannot be made over a directory that is not empty. */
    private fun blockReplacing(name: String): Path {
        val backup = directory.resolve("$name.dat_old")
        if (Files.isRegularFile(backup)) Files.delete(backup)
        backup.createDirectories().resolve("keep").writeText("")
        return backup
    }

    private companion object {
        const val INDEX = "index"
        const val VERSION = "version"
        val OVERWORLD: ResourceKey<Level> =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"))
        val NETHER: ResourceKey<Level> =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"))
    }
}
