package ru.hollowhorizon.hollowengine.common.data

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForTag
import kotlin.test.*

class NbtDataStoreTest {
    private val visits = dataKey<Int>("hollowengine:visits") { 0 }
    private val name = dataKey<String>("hollowengine:name")

    @Test
    fun `typed values update and round trip`() {
        val data = NbtDataStore()

        assertEquals(0, data[visits])
        assertFalse(visits in data)
        assertEquals(1, data.update(visits) { it + 1 })
        data[name] = "Guide"

        val restored = NbtDataStore().apply { load(data.save()) }
        assertEquals(1, restored[visits])
        assertEquals("Guide", restored[name])
        assertTrue(visits in restored)
    }

    @Test
    fun `a key holding a whole tag reads back what was written`() {
        val compound = DataKey("hollowengine:stories", ForCompoundNBT, null)
        val list = DataKey("hollowengine:history", ForTag, null)
        val stories = CompoundTag().apply { put("quest", CompoundTag().apply { putString("stage", "main") }) }
        val history = ListTag().apply { add(StringTag.valueOf("gate")) }
        val data = NbtDataStore()

        data[compound] = stories
        data[list] = history

        val restored = NbtDataStore().apply { load(data.save()) }
        assertEquals(stories, restored[compound])
        assertEquals(history, restored[list])
    }

    @Test
    fun `removal restores a non-persistent default`() {
        val data = NbtDataStore()
        data[visits] = 4

        assertTrue(data.remove(visits))
        assertEquals(0, data[visits])
        assertFalse(visits in data)
        assertNull(data[name])
    }

    @Test
    fun `an untouched store stays empty and serializes to nothing`() {
        val data = NbtDataStore()

        assertTrue(data.isEmpty())
        assertEquals(0, data[visits])
        assertFalse(data.remove(visits))
        assertTrue(data.isEmpty(), "reads must not materialize the backing compound")
        assertTrue(data.save().isEmpty)
    }

    @Test
    fun `clearing drops the backing compound`() {
        val data = NbtDataStore()
        data[name] = "Guide"
        assertFalse(data.isEmpty())

        data.clear()

        assertTrue(data.isEmpty())
        assertNull(data[name])
    }

    @Test
    fun `loading an empty compound leaves the store empty`() {
        val data = NbtDataStore()
        data[name] = "Guide"

        data.load(NbtDataStore().save())

        assertTrue(data.isEmpty())
    }
}
