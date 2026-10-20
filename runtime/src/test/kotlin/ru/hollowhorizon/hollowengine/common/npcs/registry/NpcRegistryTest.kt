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

class NpcRegistryTest {
    @TempDir
    lateinit var directory: Path

    private val file: Path get() = directory.resolve("npcs.dat")
    private val uuid = UUID.randomUUID()

    @Test
    fun `a write that failed is retried by the next flush`() {
        val registry = NpcRegistry(file)
        registry.put(record(), entity(1))
        registry.close()

        val blocked = blockReplacing()
        registry.put(record(), entity(2))
        registry.flush()
        registry.close()
        blocked.toFile().deleteRecursively()
        registry.flush()
        registry.close()

        assertEquals(2, load().entityNbt(uuid)?.getInt(VERSION))
    }

    @Test
    fun `a save without changes leaves the file alone`() {
        val registry = NpcRegistry(file)
        registry.put(record(), entity(1))
        registry.close()

        Files.delete(file)
        registry.put(record(), entity(1))
        registry.flush()
        registry.close()

        assertFalse(file.exists())
    }

    @Test
    fun `a broken file gives way to its previous version`() {
        val registry = NpcRegistry(file)
        registry.put(record(), entity(1))
        registry.close()
        registry.put(record(), entity(2))
        registry.close()

        file.writeText("broken")

        assertEquals(1, load().entityNbt(uuid)?.getInt(VERSION))
    }

    private fun load() = NpcRegistry(file).also { it.load() }

    private fun record() = NpcRecord(uuid, "test:npc", OVERWORLD, Vec3(0.0, 64.0, 0.0))

    private fun entity(version: Int) = CompoundTag().apply { putInt(VERSION, version) }

    /** Makes the next write fail: the backup cannot be made over a directory that is not empty. */
    private fun blockReplacing(): Path {
        val backup = directory.resolve("npcs.dat_old")
        if (Files.isRegularFile(backup)) Files.delete(backup)
        backup.createDirectories().resolve("keep").writeText("")
        return backup
    }

    private companion object {
        const val VERSION = "version"
        val OVERWORLD: ResourceKey<Level> =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"))
    }
}
