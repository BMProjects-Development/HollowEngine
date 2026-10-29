package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a missing save has no stories`() {
        assertNull(StoryStore(directory.resolve("stories.dat")).read())
    }

    @Test
    fun `a damaged save recovers the previous complete snapshot`() {
        val file = directory.resolve("stories.dat")
        val store = StoryStore(file)
        val first = CompoundTag().apply { putInt("version", 1) }
        val second = CompoundTag().apply { putInt("version", 2) }
        assertTrue(store.write(first))
        assertTrue(store.write(second))
        assertEquals(second, store.read())
        Files.write(file, byteArrayOf(10, 0))
        assertEquals(first, store.read())
        Files.list(directory).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
    }
}
