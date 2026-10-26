package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.Severity
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StoryScriptingAnalyzerTest {
    private fun styleOf(text: String, fragment: String): TokenType {
        val lines = StoryScriptingAnalyzer.highlight("example.story", text, 0)
        val piece = lines.flatMap { it.spans }.firstOrNull { it.first == fragment }
        assertNotNull(piece, "no span rendered exactly as '$fragment'")
        return piece.second.color
    }

    @Test
    fun `highlighting tells speakers, commands and comments apart`() {
        val source = """
            # Начало // метка
            Виталик: Привет!
            @wait 1s
            @jump #Начало
        """.trimIndent()

        assertEquals(TokenType.TOP_LEVEL, styleOf(source, "# Начало"))
        assertEquals(TokenType.COMMENT, styleOf(source, "// метка"))
        assertEquals(TokenType.CLASS, styleOf(source, "Виталик"))
        assertEquals(TokenType.FUNCTION, styleOf(source, "@wait"))
        assertEquals(TokenType.KEYWORD, styleOf(source, "@jump"))
        assertEquals(TokenType.STRING, styleOf(source, "#Начало"))
    }

    @Test
    fun `interpolation and named arguments are colored inside a line`() {
        val source = "Баланс {money} монет.\n@play-video example.ogg volume=1.0"

        assertEquals(TokenType.VARIABLE, styleOf(source, "{money}"))
        assertEquals(TokenType.VALUE_ARGUMENT_NAME, styleOf(source, "volume"))
        assertEquals(TokenType.NUMERIC_LITERAL, styleOf(source, "1.0"))
    }

    @Test
    fun `highlighting covers the source exactly once`() {
        val source = """
            # Начало
            Виталик: Баланс {money}.[-]
            @choice "Купить" if=money>10 id=buy
                @set money = money - 10
        """.trimIndent()

        val rendered = StoryScriptingAnalyzer.highlight("example.story", source, 0)
            .joinToString("\n") { line -> line.spans.joinToString("") { it.first } }

        assertEquals(source, rendered)
    }

    @Test
    fun `diagnostics come from the compiler`() {
        val diagnostics = StoryScriptingAnalyzer.diagnostic(
            "example.story",
            "# Начало\n# Начало\n@jump #Нет",
        )

        assertTrue(diagnostics.any { it.severity == Severity.ERROR && "Duplicate label" in it.message })
        val duplicate = diagnostics.first { "Duplicate label" in it.message }
        assertEquals(1, duplicate.range.start.line)
    }

    @Test
    fun `a clean story reports nothing`() {
        val source = """
            # Начало
            Виталик: Привет!
            @choice "Дальше"
                @jump #Начало
        """.trimIndent()

        assertEquals(emptyList(), StoryScriptingAnalyzer.diagnostic("example.story", source))
    }

    @Test
    fun `formatting tags are highlighted without swallowing their text`() {
        val source = "Виталик: <b>Очень <color=red>громко</color></b>!"

        assertEquals(TokenType.ANNOTATION, styleOf(source, "<b>"))
        assertEquals(TokenType.ANNOTATION, styleOf(source, "<color=red>"))
        assertEquals(TokenType.DEFAULT, styleOf(source, "Очень "))
    }

    @Test
    fun `formatted dialogue tags are validated in lines and choices`() {
        val valid = "Виталик: <b>Смело <color=gold>и ярко</color></b>.\n" +
                "@choice \"<wave speed=2>Ответ</wave>\"\n    Виталик: Хорошо."
        assertEquals(emptyList(), StoryScriptingAnalyzer.diagnostic("example.story", valid))

        val invalid = StoryScriptingAnalyzer.diagnostic(
            "example.story",
            "Виталик: <wave speed=быстро>Ошибка</wave>",
        )
        assertTrue(invalid.any { it.severity == Severity.WARNING && "finite number" in it.message })
    }

    @Test
    fun `commands complete after an at sign`() {
        val source = "@ch"
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertTrue(items.any { it.show == "@choice" })
        assertTrue(items.none { it.show == "@jump" })
    }

    @Test
    fun `jump completes the labels of the file`() {
        val source = "# Начало\n# Торговля\n@jump #"
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertEquals(listOf("#Начало", "#Торговля"), items.map { it.show })
    }

    @Test
    fun `interpolation completes variables the story uses`() {
        val source = "@set money = 0\nБаланс {mo"
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertEquals(listOf("money"), items.map { it.show })
    }

    @Test
    fun `a label highlights together with every jump and call that reaches it`() {
        val source = """
            @jump #Конец
            @call #Конец
            @call other.story#Конец
            # Конец
            Виталик: Всё.
        """.trimIndent()

        val fromLabel = StoryScriptingAnalyzer.occurrences("example.story", source, source.indexOf("# Конец") + 3)
        val fromJump = StoryScriptingAnalyzer.occurrences("example.story", source, source.indexOf("#Конец") + 2)

        assertEquals(fromLabel, fromJump)
        assertEquals(3, fromLabel.size, "the label plus the two local references")
        assertTrue(fromLabel.none { it.start > source.indexOf("other.story") && it.start < source.indexOf("# Конец") })
        fromLabel.forEach { assertEquals("Конец", source.substring(it.start, it.end)) }
    }

    @Test
    fun `a variable highlights everywhere it is written or read`() {
        val source = """
            @set money = 10
            @if money > 5
                Баланс {money} монет.
            @choice "Купить" if=money>10
                @set other = money
        """.trimIndent()

        val ranges = StoryScriptingAnalyzer.occurrences("example.story", source, source.indexOf("money") + 2)

        assertEquals(5, ranges.size, ranges.map { source.substring(it.start, it.end) }.toString())
        ranges.forEach { assertEquals("money", source.substring(it.start, it.end)) }
    }

    @Test
    fun `an unknown command is a warning, not an error`() {
        val diagnostics = StoryScriptingAnalyzer.diagnostic("example.story", "@teleport-everyone\n[unknown-inline]")

        assertTrue(diagnostics.isNotEmpty())
        assertTrue(
            diagnostics.none { it.severity == Severity.ERROR },
            "a function may still be registered on the controller: $diagnostics",
        )
        assertTrue(diagnostics.all { it.severity == Severity.WARNING })
    }

    @Test
    fun `built-in and unknown commands are drawn differently`() {
        val source = "@jump #Тут\n@play-video example.ogg\n# Тут"
        val lines = StoryScriptingAnalyzer.highlight("example.story", source, 0)
        val spans = lines.flatMap { it.spans }

        val builtin = spans.first { it.first == "@jump" }.second
        val unknown = spans.first { it.first == "@play-video" }.second

        assertEquals(TokenType.KEYWORD, builtin.color)
        assertEquals(TokenType.FUNCTION, unknown.color)
        assertTrue(unknown.italic, "a command nothing has registered is set apart by italics")
    }

    @Test
    fun `command completion replaces the at sign instead of doubling it`() {
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", "@ch", 3)
        val choice = items.first { it.show == "@choice" }

        assertTrue('@' in choice.wordChars, "the item must claim the '@' it is typed over")
        assertTrue('-' in choice.wordChars, "hyphenated names must replace the part already typed")
    }

    @Test
    fun `each overload is its own entry, with its parameters and their defaults`() {
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", "@", 1)

        val sounds = items.filter { it.show == "@play-sound" }.map { (it as CompletionItem.Declaration).middle }
        assertEquals(3, sounds.size, "one entry per overload: $sounds")
        assertTrue(sounds.all { it.orEmpty().startsWith(" ") }, sounds.toString())
        assertTrue(sounds.any { it == " name volume pitch" }, sounds.toString())

        val wait = items.first { it.show == "@wait" } as CompletionItem.Declaration
        assertEquals(" time", wait.middle)

        val jump = items.first { it.show == "@jump" } as CompletionItem.Declaration
        assertEquals(" #label | file.story#label", jump.middle)
    }

    @Test
    fun `an actor argument offers the characters the file speaks to`() {
        val source = "Виталик: Привет.\nСторож: Стой.\n@walk-to "
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertTrue(items.any { it.show == "Виталик" }, items.map { it.show }.toString())
        assertTrue(items.any { it.show == "Сторож" })
        assertTrue(items.any { it.show == "player" })
    }

    @Test
    fun `a parameter with a known set of values offers them`() {
        val source = "@hide-hud except=ch"
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertTrue(items.any { it.show == "chat" }, items.map { it.show }.toString())
        assertTrue(items.none { it.show == "hotbar" }, "the typed prefix must filter the list")
    }

    @Test
    fun `values are offered inside a list too, for the item being typed`() {
        val source = "@hide-hud except=[chat, sub"
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertTrue(items.any { it.show == "subtitle_overlay" }, items.map { it.show }.toString())
        assertTrue(items.none { it.show == "chat" })
    }

    @Test
    fun `a parameter without a known set still completes parameter names`() {
        val source = "@play-sound minecraft:x vol"
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", source, source.length)

        assertTrue(items.any { it.show == "volume=" }, items.map { it.show }.toString())
    }

    @Test
    fun `a parameter with a default shows the value it falls back to`() {
        val items = StoryScriptingAnalyzer.collectCompletions("example.story", "@fade-", 6)
        val fade = items.first { it.show == "@fade-in" } as CompletionItem.Declaration

        assertEquals(" time=500 color=#000000", fade.middle)
    }

    @Test
    fun `jumping to a local label resolves to its offset`() {
        val source = "@jump #Конец\nВиталик: Раз.\n# Конец\nВиталик: Два."
        val definition = StoryScriptingAnalyzer.definition("example.story", source, source.indexOf("#Конец") + 2)

        assertNotNull(definition)
        assertEquals("example.story", definition.path)
        assertEquals(source.indexOf("# Конец") + 2, definition.offset)
    }
}
