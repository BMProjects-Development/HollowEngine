package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.*
import kotlin.test.*

class StoryCompilerTest {
    private val catalog = storyCatalog {
        "play-video"(string("name"))
        "play-video"(string("name"), number("volume"))
        "wait"(number("time"))
        "camera"(list("location"))
    }

    private fun compile(source: String) = StoryCompiler.compile("test:story", source, catalog)

    @Test
    fun `adjacent choices become one menu that continues past the whole group`() {
        val result = compile(
            """
            @choice "День"
                Виталик: Утро.
            @choice "Ночь"
                Виталик: Спокойной ночи.
            Виталик: Пока.
            """.trimIndent(),
        )
        val program = assertNotNull(result.program, result.diagnostics.toString())

        val menu = assertIs<StoryInstruction.Menu>(program.instructions[0])
        assertEquals(2, menu.options.size)
        val exitInstruction = program.instructions[menu.exit]
        assertIs<StoryInstruction.Say>(exitInstruction)
        assertEquals("Пока.", exitInstruction.text.literalText())

        for (option in menu.options) {
            val jump = program.instructions.drop(option.bodyStart).filterIsInstance<StoryInstruction.Goto>().first()
            assertEquals(menu.exit, jump.target)
        }
    }

    @Test
    fun `conditional choices keep their condition and id`() {
        val result = compile(
            """
            @choice "Купить" if=money>10 id=buy
                Виталик: Держи.
            """.trimIndent(),
        )
        val program = assertNotNull(result.program, result.diagnostics.toString())
        val menu = assertIs<StoryInstruction.Menu>(program.instructions[0])
        assertEquals("buy", menu.options[0].id)
        assertNotNull(menu.options[0].condition)
    }

    @Test
    fun `while loops back to its condition`() {
        val result = compile(
            """
            @while money > 10
                @set money = money - 1
            """.trimIndent(),
        )
        val program = assertNotNull(result.program, result.diagnostics.toString())

        val exit = assertIs<StoryInstruction.GotoIfFalse>(program.instructions[0])
        val loop = assertIs<StoryInstruction.Goto>(program.instructions[2])
        assertEquals(0, loop.target, "loop must return to the condition")
        assertEquals(3, exit.target, "false must leave the loop")
    }

    @Test
    fun `if else-if else picks exactly one branch`() {
        val result = compile(
            """
            @if money > 10
                Виталик: Богат.
            @else-if money > 0
                Виталик: Есть немного.
            @else
                Виталик: Пусто.
            Виталик: Конец.
            """.trimIndent(),
        )
        val program = assertNotNull(result.program, result.diagnostics.toString())
        val says = program.instructions.filterIsInstance<StoryInstruction.Say>()
        assertEquals(4, says.size)

        val last = program.instructions.indexOfLast { it is StoryInstruction.Say }
        val gotos = program.instructions.filterIsInstance<StoryInstruction.Goto>()
        assertTrue(gotos.count { it.target == last } >= 2)
    }

    @Test
    fun `async body sits inline and the main flow skips it`() {
        val result = compile(
            """
            @async name=cam
                @camera location=[10, 45, 5]
            Виталик: Дальше.
            """.trimIndent(),
        )
        val program = assertNotNull(result.program, result.diagnostics.toString())

        val start = assertIs<StoryInstruction.AsyncStart>(program.instructions[0])
        assertEquals("cam", start.trackName)
        assertEquals(1, start.bodyStart)
        assertEquals(2, start.bodyEnd)
        assertIs<StoryInstruction.Invoke>(program.instructions[1])
        assertIs<StoryInstruction.Say>(program.instructions[start.bodyEnd])
    }

    @Test
    fun `inline async compiles to the same shape as a block`() {
        val result = compile("@async camera location=[10, 45, 5]")
        val program = assertNotNull(result.program, result.diagnostics.toString())
        val start = assertIs<StoryInstruction.AsyncStart>(program.instructions[0])
        assertEquals(1, start.bodyStart)
        assertEquals(2, start.bodyEnd)
    }

    @Test
    fun `anchors are label plus offset so a save survives edits above`() {
        val result = compile(
            """
            Виталик: Пролог.
            # Начало
            Виталик: Раз.
            Виталик: Два.
            """.trimIndent(),
        )
        val program = assertNotNull(result.program, result.diagnostics.toString())
        val two = program.instructions.indexOfFirst { it is StoryInstruction.Say && it.text.literalText() == "Два." }

        val anchor = program.anchorOf(two)
        assertEquals("Начало", anchor.label)
        assertEquals(1, anchor.offset)

        val edited = compile(
            """
            Виталик: Пролог.
            Виталик: Ещё строка.
            # Начало
            Виталик: Раз.
            Виталик: Два.
            """.trimIndent(),
        ).program!!
        val resolved = assertNotNull(edited.resolve(anchor))
        val instruction = assertIs<StoryInstruction.Say>(edited.instructions[resolved])
        assertEquals("Два.", instruction.text.literalText())
    }

    @Test
    fun `unknown command warns but still compiles`() {
        val result = compile("@teleport-everyone")

        assertNotNull(result.program)
        assertTrue(result.diagnostics.any { it.severity == StorySeverity.WARNING && "Unknown command" in it.message })
        assertTrue(result.diagnostics.none { it.severity == StorySeverity.ERROR })
    }

    @Test
    fun `overloads are matched by arity and literal types`() {
        assertNotNull(compile("@play-video example.ogg").program)
        assertNotNull(compile("@play-video example.ogg 1.0").program)

        val tooMany = compile("@play-video example.ogg 1.0 extra")
        assertNull(tooMany.program)
        assertTrue(tooMany.diagnostics.any { "No overload" in it.message })

        val wrongType = compile("@wait notanumber")
        assertNull(wrongType.program)
    }

    @Test
    fun `undeclared named parameters become metadata instead of errors`() {
        val result = compile("@play-video example.ogg tag=intro subtitle=hello")
        val program = assertNotNull(result.program, result.diagnostics.toString())
        val invoke = assertIs<StoryInstruction.Invoke>(program.instructions[0])

        assertEquals("intro", invoke.call.tag)
        assertTrue("subtitle" in invoke.call.metadata)
        assertEquals(1, invoke.call.args.size, "only the declared parameter stays an argument")
    }

    @Test
    fun `dialogue lines inside async are rejected before sync`() {
        val result = compile(
            """
            @async
                Виталик: Нельзя.
            """.trimIndent(),
        )
        assertNull(result.program)
        assertTrue(result.diagnostics.any { "before '@sync'" in it.message })
    }

    @Test
    fun `after sync a track may speak, because it is the main flow by then`() {
        val result = compile(
            """
            @async name=timer
                @wait 1s
                @sync
                Виталик: Время вышло.
            Виталик: Успел.
            """.trimIndent(),
        )
        assertNotNull(result.program, result.diagnostics.toString())
    }

    @Test
    fun `indenting under a line that opens nothing is an error`() {
        val result = compile(
            """
            Виталик: Привет.
                Виталик: Отступ.
            """.trimIndent(),
        )
        assertNull(result.program)
        assertTrue(result.diagnostics.any { "Unexpected indentation" in it.message })
    }

    @Test
    fun `duplicate labels are rejected`() {
        val result = compile("# Начало\nВиталик: Раз.\n# Начало\nВиталик: Два.")
        assertNull(result.program)
        assertTrue(result.diagnostics.any { "Duplicate label" in it.message })
    }
}
