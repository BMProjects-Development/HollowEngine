package ru.hollowhorizon.hollowengine.client.gui.scripting

import ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds.SoundEntry
import ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds.SoundEntryType
import ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds.SoundEventsModel
import kotlin.test.*

class SoundEventsModelTest {
    @Test
    fun `parses shorthand and full sound entries`() {
        val model = SoundEventsModel.parse(SAMPLE)

        assertEquals(2, model.events.size)
        val first = model.events[0]
        assertEquals("block.custom.break", first.name)
        assertEquals("subtitles.block.custom.break", first.subtitle)
        assertEquals(2, first.sounds.size)
        assertTrue(first.sounds[0].isDefaultExceptName)

        val full = first.sounds[1]
        assertEquals(0.5f, full.volume)
        assertEquals(1.2f, full.pitch)
        assertEquals(3, full.weight)
        assertTrue(full.stream)
        assertEquals(8, full.attenuationDistance)
        assertTrue(full.preload)
        assertEquals(SoundEntryType.EVENT, full.type)

        assertTrue(model.events[1].replace)
    }

    @Test
    fun `serialize round-trips through a re-parse`() {
        val once = SoundEventsModel.parse(SAMPLE).serialize()
        val twice = SoundEventsModel.parse(once).serialize()
        assertEquals(once, twice)
    }

    @Test
    fun `blank content yields no events`() {
        assertEquals(0, SoundEventsModel.parse("{\n}\n").events.size)
        assertEquals(0, SoundEventsModel.parse("").events.size)
    }

    @Test
    fun `half-typed text does not read as an empty file`() {
        assertFailsWith<Exception> { SoundEventsModel.parse("{ \"block.custom\": {") }
        assertFailsWith<Exception> { SoundEventsModel.parse("[]") }
    }

    @Test
    fun `defaults are omitted and shorthand is used`() {
        val model = SoundEventsModel.parse("")
        val event = model.addEvent("test.event")
        event.sounds += SoundEntry(name = "modid:test")

        val text = model.serialize()
        assertTrue(text.contains("\"modid:test\""), "expected shorthand string entry")
        assertFalse(text.contains("volume"), "default volume must be omitted")
        assertFalse(text.contains("\"replace\""), "default replace must be omitted")
    }

    private companion object {
        val SAMPLE = """
            {
              "block.custom.break": {
                "subtitle": "subtitles.block.custom.break",
                "sounds": [
                  "modid:block/custom1",
                  {
                    "name": "modid:block/custom2",
                    "volume": 0.5,
                    "pitch": 1.2,
                    "weight": 3,
                    "stream": true,
                    "attenuation_distance": 8,
                    "preload": true,
                    "type": "event"
                  }
                ]
              },
              "ambient.custom": {
                "replace": true,
                "sounds": [
                  "modid:ambient/one"
                ]
              }
            }
        """.trimIndent()
    }
}
