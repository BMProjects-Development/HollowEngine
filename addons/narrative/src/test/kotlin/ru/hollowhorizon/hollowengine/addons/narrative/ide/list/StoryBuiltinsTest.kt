package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import com.google.gson.JsonParser
import ru.hollowhorizon.hollowengine.addons.narrative.scene.NarrativeActions
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoryBuiltinsTest {
    @Test
    fun `every built-in statement reads back as itself and writes what it read`() {
        for (builtin in StoryBuiltins.all) {
            val model = StoryListModel("test.story", builtin.template)
            val kind = model.outline.cst.lines[0].kind

            assertSame(builtin, StoryBuiltins.of(kind), "'${builtin.template}' is read as $kind")
            assertEquals(builtin.template, builtin.write(builtin.read(model, kind)))
        }
    }

    @Test
    fun `a required field cleared leaves the statement as it was`() {
        val model = StoryListModel("test.story", "@set x = 0")
        val kind = model.outline.cst.lines[0].kind

        assertNull(StoryBuiltins.of(kind)!!.with(model, kind, 0, " "))
    }

    @Test
    fun `renaming the track of an async keeps the command it runs`() {
        val model = StoryListModel("test.story", "@async name=music @wait 1s")
        val kind = model.outline.cst.lines[0].kind

        assertEquals("@async name=intro @wait 1s", StoryBuiltins.of(kind)!!.with(model, kind, 0, "intro"))
        assertEquals("@async @wait 1s", StoryBuiltins.of(kind)!!.with(model, kind, 0, ""))
    }

    @Test
    fun `every command and built-in statement is named in both languages`() {
        NarrativeActions.register()
        val english = lang("en_us")
        val russian = lang("ru_ru")
        assertEquals(english, russian, "en_us and ru_ru name different keys")

        val names = ActionCommands.commands.filter { it.signature.title == null }.map { it.signature.name } +
            StoryBuiltins.all.map { it.id }
        val missing = names.distinct().filter { key(it) !in english }
        assertTrue(missing.isEmpty(), "No name for $missing")

        val parts = ActionCommands.commands.flatMap { command ->
            command.signature.params.filter { it.title == null }.map { command.signature.name to it.name }
        } + StoryBuiltins.all.flatMap { builtin -> builtin.fields.map { builtin.id to it.name } }
        val unnamed = parts.distinct().filter { (command, part) ->
            "${key(command)}.$part" !in english && "hollowengine.story.param.$part" !in english
        }
        assertTrue(unnamed.isEmpty(), "No name for the parts $unnamed")
    }

    /** The key a command [id] is named by: `ns:path` by `ns.story.command.path`, a built-in in the narrative namespace. */
    private fun key(id: String) = "${id.substringBefore(':', "hollowengine-narrative")}.story.command.${id.substringAfter(':')}"

    private fun lang(locale: String): Set<String> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/assets/hollowengine-narrative/lang/$locale.json"))
        return stream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject.keySet() }
    }
}
