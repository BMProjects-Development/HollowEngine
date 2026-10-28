package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryExpression
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryParser
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.TextPart
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryScriptingAnalyzer
import ru.hollowhorizon.hollowengine.common.utils.expressions.Ast
import kotlin.test.*

class StoryParserTest {
    @Test
    fun `printing an unchanged tree reproduces the source byte for byte`() {
        val source = """
            # Начало   // метка

            @play-background project:sounds/bg/music_1.ogg
            	Виталик: Привет!
            Ваш баланс: {money} монет.

            @choice "Сейчас день."   // с комментарием
                Виталик: С добрым утром.
        """.trimIndent() + "\r\nхвост без перевода строки"

        assertEquals(source, StoryParser.parse(source).cst.print())
    }

    @Test
    fun `speaker is only recognised for a single word before the colon`() {
        val parsed = StoryParser.parse("Виталик: Привет!\nВаш баланс: 10 монет.")
        val first = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Dialogue>(first)
        assertEquals("Виталик", first.speaker)
        assertEquals("Привет!", first.text.literalText())

        val second = parsed.cst.lines[1].kind
        assertIs<StoryLineKind.Dialogue>(second)
        assertNull(second.speaker)
        assertEquals("Ваш баланс: 10 монет.", second.text.literalText())
    }

    @Test
    fun `a braced expression right before the colon is the speaker`() {
        val parsed = StoryParser.parse("{player}: Стою!\n{money} монет: не густо.")
        val braced = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Dialogue>(braced)
        assertNull(braced.speaker)
        assertEquals("player", (braced.speakerExpr!!.ast as Ast.Name).name)
        assertEquals("Стою!", braced.text.literalText())

        // The braces have to sit on the colon; anything between them makes it an ordinary line.
        val narration = parsed.cst.lines[1].kind
        assertIs<StoryLineKind.Dialogue>(narration)
        assertNull(narration.speakerExpr)
        assertNull(narration.speaker)
        assertEquals(" монет: не густо.", narration.text.literalText())
    }

    @Test
    fun `a single word before a colon is always a speaker`() {
        val parsed = StoryParser.parse("Баланс: 10 монет.\nБаланс\\: 10 монет.")
        val speakerLine = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Dialogue>(speakerLine)
        assertEquals("Баланс", speakerLine.speaker)

        val escaped = parsed.cst.lines[1].kind
        assertIs<StoryLineKind.Dialogue>(escaped)
        assertNull(escaped.speaker)
        assertEquals("Баланс: 10 монет.", escaped.text.literalText())
    }

    @Test
    fun `text splits into interpolation and literals, brackets included`() {
        val parsed = StoryParser.parse("Виталик: Баланс {money} монет.[-] Ждём [wait 1s].")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Dialogue>(kind)
        assertEquals("Виталик", kind.speaker)

        assertEquals(listOf("Literal", "Interpolation", "Literal"), kind.text.parts.map { it::class.simpleName })
        assertEquals(" монет.[-] Ждём [wait 1s].", (kind.text.parts[2] as TextPart.Literal).text)
    }

    @Test
    fun `duration literals become milliseconds`() {
        val parsed = StoryParser.parse("@fade-in 1sec #000000\n@wait 500ms\n@wait 2min")
        fun firstArg(index: Int): Float {
            val kind = parsed.cst.lines[index].kind
            assertIs<StoryLineKind.FuncCall>(kind)
            return (controllerless(kind.args[0].expr) as StoryNumber).value
        }
        assertEquals(1000f, firstArg(0))
        assertEquals(500f, firstArg(1))
        assertEquals(120_000f, firstArg(2))
    }

    @Test
    fun `a list argument holds values, not variable names`() {
        val parsed = StoryParser.parse("@hide-hud except=[chat, subtitle_overlay] only=[10, 2.5]")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.FuncCall>(kind)

        val except = (kind.args.first { it.name == "except" }.expr).parts
        assertEquals(
            listOf("chat", "subtitle_overlay"),
            except.map { (it.constant as StoryString).value },
        )

        val numbers = kind.args.first { it.name == "only" }.expr.parts
        assertEquals(
            listOf(10f, 2.5f),
            numbers.map { (it.constant as StoryNumber).value },
        )
    }

    @Test
    fun `a call is an argument whose spaces and commas stay inside it`() {
        val parsed = StoryParser.parse("""@look-at-entity Виталик npc([0, 64, 0], id="guard") speed=2""")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.FuncCall>(kind)

        assertEquals(listOf(null, null, "speed"), kind.args.map { it.name })
        val call = kind.args[1].expr.calls.single().call
        assertEquals("npc", call.function)
        assertEquals(listOf(null, "id"), call.args.map { it.name })
    }

    @Test
    fun `clearing an argument of a nested call keeps the ones after it on their parameters`() {
        val source = "@look-at-entity npc([0, 64, 0], guard, \"minecraft:overworld\")"
        val kind = StoryParser.parse(source).cst.lines[0].kind
        assertIs<StoryLineKind.FuncCall>(kind)
        val call = kind.args.single().expr.calls.single().call
        val params = listOf("pos", "id", "level")

        assertEquals("npc([0, 64, 0], level=\"minecraft:overworld\")", StoryEdits.setCallArgument(source, call, params, "id", ""))
        assertEquals("npc([0, 64, 0], guard, \"minecraft:overworld\")", StoryEdits.setCallArgument(source, call, params, "id", "guard"))
    }

    @Test
    fun `a bad argument keeps the line a command`() {
        val parsed = StoryParser.parse("@move-to Виталик [25, 70 speed=1.1")
        val kind = parsed.cst.lines[0].kind

        assertIs<StoryLineKind.FuncCall>(kind, "the card must still know its command")
        assertEquals("move-to", kind.function)
        assertTrue(parsed.diagnostics.all.isNotEmpty())
    }

    @Test
    fun `a value being typed keeps the line what it is`() {
        val parsed = StoryParser.parse("@set x = 1 +\n@while счёт <")

        assertIs<StoryLineKind.Set>(parsed.cst.lines[0].kind)
        assertIs<StoryLineKind.While>(parsed.cst.lines[1].kind)
        assertEquals(2, parsed.diagnostics.all.size)
    }

    @Test
    fun `negative numbers are numbers, not strings`() {
        val parsed = StoryParser.parse("@camera location=[25, 70, -171] rotation=[-158, 2] fov=-1.5")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.FuncCall>(kind)

        fun numbers(argument: String) = kind.args.first { it.name == argument }.expr
            .parts.map { (it.evaluate { null } as StoryNumber).value }

        assertEquals(listOf(25f, 70f, -171f), numbers("location"))
        assertEquals(listOf(-158f, 2f), numbers("rotation"))
        assertEquals(-1.5f, (kind.args.first { it.name == "fov" }.expr.evaluate { null } as StoryNumber).value)
    }

    @Test
    fun `a negative number is highlighted whole, sign included`() {
        val source = "@camera rotation=[-158, 2]"
        val spans = StoryScriptingAnalyzer.highlight("example.story", source, 0)
            .flatMap { it.spans }

        val sign = spans.first { it.first == "-158" }
        assertEquals(TokenType.NUMERIC_LITERAL, sign.second.color)
    }

    @Test
    fun `a list argument still takes expressions in braces`() {
        val parsed = StoryParser.parse("@hide-hud except=[{выбранный}, chat]")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.FuncCall>(kind)

        val items = kind.args.single().expr.parts
        assertIs<Ast.Name>(items[0].ast)
        assertNotNull(items[1].constant)
    }

    @Test
    fun `a condition is an expression, spaces and all when quoted`() {
        val tight = StoryParser.parse("@choice \"Взять\" if=вещи.size>0").cst.lines[0].kind
        assertIs<StoryLineKind.Choice>(tight)
        assertIs<Ast.Binary>(tight.args.single().expr.ast)

        val quoted = StoryParser.parse("@choice \"Взять\" if=\"вещи.size > 0\"").cst.lines[0].kind
        assertIs<StoryLineKind.Choice>(quoted)
        assertIs<Ast.Binary>(quoted.args.single().expr.ast)
    }

    @Test
    fun `a comment inside quotes is not a comment`() {
        val parsed = StoryParser.parse("""@choice "Скидка 50// процентов" id=sale""")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Choice>(kind)
        assertEquals("Скидка 50// процентов", kind.text.literalText())
        assertNull(parsed.cst.lines[0].comment)
    }

    @Test
    fun `vanilla command keeps its selector brackets`() {
        val parsed = StoryParser.parse("@command gamemode @p creative")
        val kind = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Command>(kind)
        assertEquals("gamemode @p creative", kind.text.literalText())

        val selector = StoryParser.parse("@command tp @e[tag=npc] ~ ~ ~").cst.lines[0].kind
        assertIs<StoryLineKind.Command>(selector)
        assertEquals("tp @e[tag=npc] ~ ~ ~", selector.text.literalText())
    }

    @Test
    fun `jump targets split into address and label`() {
        val parsed = StoryParser.parse("@jump project:stories/other.story#Начало\n@jump #Локально")
        val global = parsed.cst.lines[0].kind
        assertIs<StoryLineKind.Jump>(global)
        assertEquals("project:stories/other.story", global.target.address)
        assertEquals("Начало", global.target.label)

        val local = parsed.cst.lines[1].kind
        assertIs<StoryLineKind.Jump>(local)
        assertNull(local.target.address)
        assertEquals("Локально", local.target.label)
    }

    @Test
    fun `a broken line becomes a diagnostic without losing the text`() {
        val source = "# \n@jump\nВиталик: Привет!"
        val parsed = StoryParser.parse(source)

        assertTrue(parsed.diagnostics.hasErrors)
        assertEquals(source, parsed.cst.print())
        assertIs<StoryLineKind.Dialogue>(parsed.cst.lines[2].kind)
    }

    private fun controllerless(expr: StoryExpression): StoryValue = expr.evaluate { null }
}
