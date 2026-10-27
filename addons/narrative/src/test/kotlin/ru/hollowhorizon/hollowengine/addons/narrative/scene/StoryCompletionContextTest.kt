package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryCompletionContext
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.storyCompletionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The caret context is read by scanning, not by matching patterns, and it has to hold up on lines
 * that do not parse yet, an unclosed `[` or a half-typed name is exactly when completion is asked
 * for.
 */
class StoryCompletionContextTest {
    private fun contextOf(line: String) = storyCompletionContext(line, line.length)

    @Test
    fun `a line that is not a command offers nothing`() {
        assertIs<StoryCompletionContext.None>(contextOf("Виталик: Привет"))
        assertIs<StoryCompletionContext.None>(contextOf(""))
        assertIs<StoryCompletionContext.None>(contextOf("# Метка"))
    }

    @Test
    fun `the command name is recognised while it is typed`() {
        assertEquals(StoryCompletionContext.Command("ch"), contextOf("@ch"))
        assertEquals(StoryCompletionContext.Command(""), contextOf("    @"))
        assertEquals(StoryCompletionContext.Command("play-so"), contextOf("@play-so"))
    }

    @Test
    fun `jump and call name labels`() {
        assertEquals(StoryCompletionContext.Label("Нач"), contextOf("@jump #Нач"))
        assertEquals(StoryCompletionContext.Label(""), contextOf("@call "))
    }

    @Test
    fun `a named parameter value is found even with the list still open`() {
        val open = contextOf("@hide-hud except=[chat, sub")
        assertIs<StoryCompletionContext.Argument>(open)
        assertEquals("except", open.parameter)
        assertEquals("sub", open.typed)

        val plain = contextOf("@hide-hud except=ch")
        assertIs<StoryCompletionContext.Argument>(plain)
        assertEquals("except", plain.parameter)
        assertEquals("ch", plain.typed)
    }

    @Test
    fun `an unnamed argument knows which position it is`() {
        val first = contextOf("@walk-to Вит")
        assertIs<StoryCompletionContext.Argument>(first)
        assertEquals(null, first.parameter)
        assertEquals(0, first.positional)
        assertEquals("Вит", first.typed)

        val third = contextOf("""@play-video "клип.ogg" [1, 2] x""")
        assertIs<StoryCompletionContext.Argument>(third)
        assertEquals(2, third.positional)
        assertEquals("x", third.typed)
    }

    @Test
    fun `an open brace is an expression, wherever it sits`() {
        val inArgument = contextOf("@wait {mo")
        assertIs<StoryCompletionContext.Expression>(inArgument)
        assertEquals("mo", inArgument.typed)

        val inText = contextOf("Баланс {mo")
        assertIs<StoryCompletionContext.Expression>(inText)
        assertEquals("mo", inText.typed)

        assertIs<StoryCompletionContext.Argument>(contextOf("@wait {money} ti"))
        assertIs<StoryCompletionContext.None>(contextOf("Баланс {money} монет"))
    }

    @Test
    fun `a quoted argument does not swallow the rest of the line`() {
        val context = contextOf("""@choice "Купить меч" id""")
        assertIs<StoryCompletionContext.Argument>(context)
        assertEquals("id", context.typed)
        assertEquals(1, context.positional)
    }
}
