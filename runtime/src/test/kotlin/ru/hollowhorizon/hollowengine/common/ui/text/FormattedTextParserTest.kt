package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextAnimation
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextParser
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FormattedTextParserTest {
    @Test
    fun `nested tags produce styled spans without entering visible text`() {
        val document = FormattedTextParser.parse("Я теперь <b>очень <i>жирный</i></b>!")

        assertEquals("Я теперь очень жирный!", document.plainText)
        assertEquals(22, document.visibleLength)
        assertTrue(document.diagnostics.isEmpty())
        assertEquals(
            listOf(
                emptyList(),
                listOf(FormattedTextStyle.Bold),
                listOf(FormattedTextStyle.Bold, FormattedTextStyle.Italic),
                emptyList(),
            ),
            document.spans.map { it.styles },
        )
    }

    @Test
    fun `colors accept named short and rgba hex values`() {
        val document = FormattedTextParser.parse(
            "<color=red>красный</color> <color=#0F08>зелёный</color>",
        )

        assertEquals("красный зелёный", document.plainText)
        assertEquals(FormattedTextStyle.Color(255, 85, 85), document.spans[0].styles.single())
        assertEquals(FormattedTextStyle.Color(0, 255, 0, 136), document.spans[2].styles.single())
    }

    @Test
    fun `animation parameters are parsed and validated`() {
        val document = FormattedTextParser.parse(
            "<wave amplitude=4 speed=3>волна</wave> <glitch chromatic=false>сбой</glitch>",
        )

        val wave = assertIs<FormattedTextStyle.Animation>(document.spans[0].styles.single())
        assertEquals(FormattedTextAnimation.WAVE, wave.type)
        assertEquals(mapOf("amplitude" to 4f, "speed" to 3f), wave.parameters)

        val glitch = assertIs<FormattedTextStyle.Animation>(document.spans[2].styles.single())
        assertEquals(false, glitch.flags["chromatic"])
        assertTrue(document.diagnostics.isEmpty())
    }

    @Test
    fun `unknown invalid and mismatched tags remain visible`() {
        val source = "<unknown>x</unknown> <wave speed=fast>y</wave> <b>z</i>"
        val document = FormattedTextParser.parse(source)

        assertEquals("<unknown>x</unknown> <wave speed=fast>y</wave> z</i>", document.plainText)
        assertTrue(document.diagnostics.size >= 4)
    }

    @Test
    fun `an unclosed known tag styles fragment text for incremental dialogue updates`() {
        val document = FormattedTextParser.parse("<b>первая часть")

        assertEquals("первая часть", document.plainText)
        assertEquals(listOf(FormattedTextStyle.Bold), document.spans.single().styles)
        assertTrue(document.diagnostics.single().message.contains("Unclosed"))
    }

    @Test
    fun `entities and line breaks have their rendered unicode length`() {
        val document = FormattedTextParser.parse("&lt;b&gt;<br>😀")

        assertEquals("<b>\n😀", document.plainText)
        assertEquals(5, document.visibleLength)
    }
}
