package ru.hollowhorizon.hollowengine.common.ui

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import ru.hollowhorizon.hollowengine.common.data.dataKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UiDataTest {
    @Serializable
    data class Quest(val title: String = "", val progress: Int = 0)

    private val title = dataKey<String>("title") { "" }
    private val progress = dataKey<Int>("progress") { 0 }
    private val done = dataKey<Boolean>("done") { false }
    private val quest = dataKey<Quest>("quest") { Quest() }

    @Test
    fun `missing keys fall back to their default`() {
        val data = UiData()
        assertEquals("none", data[dataKey<String>("title") { "none" }])
        assertEquals(7, data[dataKey<Int>("progress") { 7 }])
        assertFalse(data[done])
    }

    @Test
    fun `find returns null for an absent key without a default`() {
        val data = UiData()
        assertEquals(null, data.find(dataKey<String>("missing")))
        assertEquals("fallback", data.getOr(dataKey<String>("missing"), "fallback"))
    }

    @Test
    fun `a patch merges compounds instead of replacing them`() {
        val data = UiData(CompoundTag().apply {
            put("quest", CompoundTag().apply {
                putString("title", "Old title")
                putInt("progress", 1)
            })
        })

        data.applyPatch(CompoundTag().apply {
            put("quest", CompoundTag().apply { putInt("progress", 2) })
        })

        // The patch only carried `progress`, so `title` has to survive it.
        assertEquals("Old title", data[quest].title)
        assertEquals(2, data[quest].progress)
    }

    @Test
    fun `a patch replaces non-compound tags wholesale`() {
        val data = UiData(CompoundTag().apply { putString("title", "idle") })
        data.applyPatch(CompoundTag().apply { putString("title", "busy") })
        assertEquals("busy", data[title])
    }

    @Test
    fun `removed keys are dropped from the document`() {
        val data = UiData()
        data[title] = "Doomed"

        data.applyPatch(CompoundTag(), removed = listOf("title"))

        assertFalse(data.contains(title))
        assertEquals("", data[title])
    }

    @Test
    fun `snapshot round-trips through replaceAll`() {
        val source = UiData()
        source[title] = "Round trip"
        source[progress] = 3
        source[quest] = Quest("Escort", 2)

        val restored = UiData()
        restored[done] = true
        restored.replaceAll(source.snapshot())

        assertEquals("Round trip", restored[title])
        assertEquals(3, restored[progress])
        assertEquals(Quest("Escort", 2), restored[quest])
        assertFalse(restored.contains(done), "replacing the document must drop old keys")
    }

    @Test
    fun `snapshot does not alias the live document`() {
        val data = UiData(CompoundTag().apply { put("quest", CompoundTag().apply { putString("title", "before") }) })
        val snapshot = data.snapshot()

        (snapshot.get("quest") as CompoundTag).put("title", StringTag.valueOf("mutated"))

        assertEquals("before", data[quest].title)
    }

}
