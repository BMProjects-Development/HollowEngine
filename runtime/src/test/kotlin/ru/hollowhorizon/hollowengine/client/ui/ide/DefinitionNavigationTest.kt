package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.gui.scripting.testFileTypes
import ru.hollowhorizon.hollowengine.client.ui.HollowUiSurface
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.widgets.TextFieldState
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeEditor
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.withNormalizedLineSeparators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefinitionNavigationTest {
    @Test
    fun `navigation before the first editor frame scrolls to the declaration`() {
        val text = (1..100).joinToString("\n") { "fun declaration$it() {}" }
        val editor = TextFieldState(text, multiline = true)
        editor.moveCaret(text.indexOf("declaration80"))
        editor.focus()
        HollowUiSurface().use { surface ->
            surface.setContent {
                UiCodeEditor(value = text, onChange = {}, state = editor,
                    modifier = Modifier.size(300.px, 150.px))
            }
            repeat(6) { tick -> surface.frame(400f, 250f, -1f, -1f, tick * 16_000_000L) }
            assertTrue(editor.scroll.offsetY > 0f, "The editor must reveal the requested declaration")
        }
    }

    @Test
    fun `repeating navigation reveals a declaration even when the caret is already there`() {
        val text = (1..100).joinToString("\n") { "fun declaration$it() {}" }
        val editor = TextFieldState(text, multiline = true)
        val offset = text.indexOf("declaration80")
        editor.moveCaret(offset)
        HollowUiSurface().use { surface ->
            surface.setContent {
                UiCodeEditor(value = text, onChange = {}, state = editor,
                    modifier = Modifier.size(300.px, 150.px))
            }
            var tick = 0L
            fun frames() = repeat(6) { surface.frame(400f, 250f, -1f, -1f, ++tick * 16_000_000L) }
            frames()
            editor.scroll.scrollTo(y = 0f)
            frames()
            assertEquals(0f, editor.scroll.offsetY)
            editor.moveCaret(offset)
            frames()
            assertTrue(editor.scroll.offsetY > 0f, "A repeated navigation request must reveal the caret")
        }
    }

    @Test
    fun `remounting preserves manual scrolling and follows navigation while the tab was hidden`() {
        val text = (1..100).joinToString("\n") { "fun declaration$it() {}" }
        val editor = TextFieldState(text, multiline = true)
        editor.moveCaret(text.indexOf("declaration80"))
        var visible by mutableStateOf(true)
        HollowUiSurface().use { surface ->
            surface.setContent {
                if (visible) UiCodeEditor(value = text, onChange = {}, state = editor,
                    modifier = Modifier.size(300.px, 150.px))
            }
            var tick = 0L
            fun frames() = repeat(6) { surface.frame(400f, 250f, -1f, -1f, ++tick * 16_000_000L) }
            frames()
            assertTrue(editor.scroll.offsetY > 0f)
            editor.scroll.scrollTo(y = 0f)
            frames()
            visible = false
            frames()
            visible = true
            frames()
            assertEquals(0f, editor.scroll.offsetY, "Switching tabs must preserve manual scrolling")
            visible = false
            frames()
            editor.moveCaret(text.indexOf("declaration90"))
            visible = true
            frames()
            assertTrue(editor.scroll.offsetY > 0f, "Navigation while hidden must reveal the new declaration")
        }
    }

    @Test
    fun `opening sources preserves the declaration position for every line separator`() {
        for (extension in listOf("kt", "java")) {
            for (separator in listOf("\n", "\r\n", "\r", "\n\r\n")) {
                val declaration = if (extension == "kt") "fun declaration() {}" else "class Source { void declaration() {} }"
                val source = "// header${separator}// another line${separator}$declaration"
                val location = DefinitionLocation("library-sources/Source.$extension", source.indexOf("declaration"), source)
                    .withNormalizedLineSeparators()
                val file = HollowIdeModel(testFileTypes()).openReadOnly(location.path, location.text!!)
                val editor = TextFieldState(file.text, multiline = true)
                editor.moveCaret(location.offset)

                assertEquals(location.text, editor.text)
                assertTrue(editor.text.startsWith("declaration", editor.caret))
                assertSame(location, location.withNormalizedLineSeparators())
            }
        }
    }

    @Test
    fun `navigation without attached text keeps the file position`() {
        val location = DefinitionLocation("script.kts", 42)
        assertSame(location, location.withNormalizedLineSeparators())
    }
}
