package ru.hollowhorizon.hollowengine.common.scripting.ide.ui

import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.Severity
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.common.scripting.ide.collectCompletions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HssScriptingAnalyzerTest {
    private fun completions(source: String, caret: Int = source.length) =
        HssScriptingAnalyzer.collectCompletions("style.hss", source, caret)

    private fun labels(source: String, caret: Int = source.length) = completions(source, caret).map { it.show }

    @Test
    fun `property completion carries the full signature`() {
        val item = completions(".panel {\n    marg", caret = 17)
            .filterIsInstance<CompletionItem.Declaration>()
            .single { it.show == "margin" }
        assertEquals("margin: ", item.insert)
        assertEquals(": <top> <right> <bottom> <left>", item.middle)
        assertTrue(item.tail!!.isNotBlank())
    }

    @Test
    fun `aliases stay completable`() {
        assertTrue(labels(".panel {\n    box-sh").contains("box-shadow"))
    }

    @Test
    fun `values are completed for the slot being typed`() {
        val afterColon = labels(".panel {\n    align: ")
        assertTrue(afterColon.contains("center"), afterColon.toString())
        val secondSlot = labels(".panel {\n    align: center ")
        assertTrue(secondSlot.contains("stretch"), secondSlot.toString())
    }

    @Test
    fun `animation names are completed from the document keyframes`() {
        val source = "@keyframes fade-in { from { opacity: 0; } }\n.panel {\n    animations: fa"
        assertTrue(labels(source).contains("fade-in"), labels(source).toString())
    }

    @Test
    fun `transition properties are completed from the style props`() {
        val source = ".panel {\n    transitions: backg"
        assertTrue(labels(source).contains("background"), labels(source).toString())
    }

    @Test
    fun `states are completed after a colon in a selector`() {
        assertTrue(labels(".panel:ho").contains("hover"))
    }

    @Test
    fun `tags used in the document are completed after a dot`() {
        val source = ".panel { color: #FFFFFF; }\n.pa"
        assertTrue(labels(source).contains("panel"))
    }

    @Test
    fun `keyframe selectors are completed inside a keyframes block`() {
        val source = "@keyframes fade {\n    fr"
        assertTrue(labels(source).contains("from"))
    }

    @Test
    fun `inlay hints name the tokens of a shorthand`() {
        val source = ".panel {\n    margin: 8px 8px 60px 8px;\n}"
        val hints = HssScriptingAnalyzer.highlight("style.hss", source, 0)
            .flatMap { line -> line.hints.map { it.text } }
        assertEquals(listOf("top=", "right=", "bottom=", "left="), hints)
    }

    @Test
    fun `inlay hints stay on the line of their declaration`() {
        val source = ".panel {\n    margin: 8px 12px;\n}"
        val lines = HssScriptingAnalyzer.highlight("style.hss", source, 0)
        assertEquals(emptyList(), lines[0].hints.map { it.text })
        assertEquals(listOf("top/bottom=", "left/right="), lines[1].hints.map { it.text })
        val marginLine = source.lines()[1]
        assertEquals(marginLine.indexOf("8px"), lines[1].hints.first().index)
    }

    @Test
    fun `highlighting covers every character of every line`() {
        val source = "@keyframes fade {\n    from { opacity: 0; }\n}\n.panel:hover {\n    margin: 8px;\n}"
        val lines = HssScriptingAnalyzer.highlight("style.hss", source, 0)
        assertEquals(source.lines(), lines.map { line -> line.spans.joinToString("") { it.first } })
    }

    @Test
    fun `keyframe blocks keep selectors and declarations apart`() {
        val source = "@keyframes fade {\n    from { opacity: 0; }\n}"
        val lines = HssScriptingAnalyzer.highlight("style.hss", source, 0)
        val atRule = lines[0].spans.first { it.first == "@keyframes" }
        assertEquals(TokenType.KEYWORD, atRule.second.color)
        val name = lines[0].spans.first { it.first == "fade" }
        assertEquals(TokenType.CLASS, name.second.color)
        val from = lines[1].spans.first { it.first == "from" }
        assertEquals(TokenType.KEYWORD, from.second.color)
        val opacity = lines[1].spans.first { it.first == "opacity" }
        assertEquals(TokenType.PROPERTY_IDENTIFIER, opacity.second.color)
    }

    @Test
    fun `unknown properties are reported with a suggestion`() {
        val source = ".panel {\n    marrgin: 8px;\n}"
        val diagnostic = HssScriptingAnalyzer.diagnostic("style.hss", source).single()
        assertEquals(Severity.WARNING, diagnostic.severity)
        assertTrue("margin" in diagnostic.message, diagnostic.message)
        assertEquals(1, diagnostic.range.start.line)
        assertEquals(4, diagnostic.range.start.column)
    }

    @Test
    fun `invalid values are reported on the value`() {
        val source = ".panel {\n    align: nowhere;\n}"
        val diagnostic = HssScriptingAnalyzer.diagnostic("style.hss", source).single()
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertEquals(1, diagnostic.range.start.line)
        assertEquals(source.lines()[1].indexOf("nowhere"), diagnostic.range.start.column)
    }

    @Test
    fun `animations naming a missing keyframes block are reported`() {
        val source = ".panel {\n    animations: fade 200ms;\n}"
        val diagnostic = HssScriptingAnalyzer.diagnostic("style.hss", source).single()
        assertEquals(Severity.WARNING, diagnostic.severity)
        assertTrue("fade" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `a declared keyframes block silences the animation warning`() {
        val source = "@keyframes fade { from { opacity: 0; } }\n.panel {\n    animations: fade 200ms;\n}"
        assertEquals(emptyList(), HssScriptingAnalyzer.diagnostic("style.hss", source))
    }

    @Test
    fun `image-uv accepts both a reset keyword and a four value region`() {
        assertEquals(emptyList(), HssScriptingAnalyzer.diagnostic("style.hss", ".icon {\n    image-uv: none;\n}"))
        assertEquals(
            emptyList(),
            HssScriptingAnalyzer.diagnostic("style.hss", ".icon {\n    image-uv: 16px 0px 16px 16px;\n}"),
        )
        assertEquals(emptyList(), HssScriptingAnalyzer.diagnostic("style.hss", ".icon {\n    uv: 0% 0% 50% 50%;\n}"))
    }

    @Test
    fun `an incomplete image-uv region is reported`() {
        val source = ".icon {\n    image-uv: 16px 0px;\n}"
        val diagnostic = HssScriptingAnalyzer.diagnostic("style.hss", source).single()
        assertEquals(Severity.ERROR, diagnostic.severity)
    }

    @Test
    fun `keyframe selectors are completed`() {
        val offered = labels("@keyframes bounce {\n    ")
        assertTrue(offered.containsAll(listOf("from", "to", "50%")), offered.toString())
    }

    @Test
    fun `a syntax error does not hide the rest of the file`() {
        val source = ".broken { aaa }\n.panel {\n    marrgin: 8px;\n}"
        val diagnostics = HssScriptingAnalyzer.diagnostic("style.hss", source)
        assertEquals(2, diagnostics.size, diagnostics.toString())
        assertEquals(Severity.ERROR, diagnostics.first().severity)
        assertEquals(Severity.WARNING, diagnostics.last().severity)
    }

    @Test
    fun `animation names jump to their keyframes block`() {
        val source = "@keyframes fade { from { opacity: 0; } }\n.panel {\n    animations: fade 200ms;\n}"
        val definition = HssScriptingAnalyzer.definition("style.hss", source, source.lastIndexOf("fade") + 1)
        assertNotNull(definition)
        assertEquals(source.indexOf("fade"), definition.offset)
    }
}
