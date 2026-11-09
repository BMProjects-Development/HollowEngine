package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StoryFieldFormTest {
    /** A field writes the file on every letter, so what was typed has to come back letter for letter. */
    @Test
    fun `the inside of a string survives being typed`() {
        listOf(
            "Hello", "Hello, \${player.name}!", "say \"hi\"", "a \\ b", "three \"\"\" quotes", "cost \$5",
            "price \\\$total", "",
        ).forEach { typed ->
            assertEquals(typed, StoryTemplates.content(StoryTemplates.expression(typed)))
        }
    }

    @Test
    fun `an escaped string shows what it holds, its inserts kept`() {
        assertEquals("Hi, \${name}!\n", StoryTemplates.content("\"Hi, \${name}!\\n\""))
        assertEquals("price \\\$total", StoryTemplates.content("\"price \\\$total\""))
        assertEquals("cost \$5", StoryTemplates.content("\"cost \\\$5\""))
    }

    @Test
    fun `a dollar sign the field escapes puts nothing in`() {
        assertEquals("\"\"\"price \${'$'}total\"\"\"", StoryTemplates.expression("price \\\$total"))
        val shown = "a \\\$b \"\"\" c"
        val analyzed = StoryTemplates.analyzed(shown)
        assertEquals(shown.length, analyzed.length)
        assertFalse("\$b" in analyzed)
        assertFalse("\"\"\"" in analyzed)
    }

    @Test
    fun `any other expression is put into the string whole`() {
        assertEquals("\${npc.name}", StoryTemplates.content("npc.name"))
        assertEquals("\${\"a\" + b}", StoryTemplates.content("\"a\" + b"))
    }

    @Test
    fun `a number is written as its type writes one and read back`() {
        val float = StoryFieldForm.Numeric(false, "f")
        val double = StoryFieldForm.Numeric(false)
        val whole = StoryFieldForm.Numeric(true)
        assertEquals("1.5f", float.write(1.5))
        assertEquals("2f", float.write(2.0))
        assertEquals("2.0", double.write(2.0))
        assertEquals("3", whole.write(3.0))
        assertEquals(2.0, float.read("2f"))
        assertEquals(1.0, double.read("1.toDouble()"))
        assertEquals(-4.0, whole.read("-4L"))
        assertNull(double.read("speed * 2"))
    }

    @Test
    fun `an enum constant is shown by its name in its package and written in full`() {
        val form = StoryFieldForm.of(typeOf<Mood>())
        val qualified = Mood::class.qualifiedName
        assertEquals("StoryFieldFormTest.Mood.CALM", form.shown("$qualified.CALM", null))
        assertEquals("$qualified.ANGRY", form.written("StoryFieldFormTest.Mood.ANGRY", null))
        assertEquals("mood()", form.written("mood()", null))
        assertEquals(listOf("StoryFieldFormTest.Mood.CALM", "StoryFieldFormTest.Mood.ANGRY"), form.choices)
    }

    @Test
    fun `a type written by name gets the field of its kind`() {
        assertEquals(StoryFieldForm.Template, StoryFieldForm.ofName("kotlin.String"))
        assertEquals(StoryFieldForm.Template, StoryFieldForm.ofName("String?"))
        assertEquals(StoryFieldForm.Numeric(true), StoryFieldForm.ofName("Int"))
        assertEquals(StoryFieldForm.Code, StoryFieldForm.ofName("List<String>"))
        assertEquals(StoryFieldForm.Code, StoryFieldForm.ofName(null))
    }

    enum class Mood { CALM, ANGRY }
}
