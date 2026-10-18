package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import ru.hollowhorizon.hollowengine.client.ui.style.fontSize
import ru.hollowhorizon.hollowengine.client.ui.style.opacity
import ru.hollowhorizon.hollowengine.client.ui.style.padding
import ru.hollowhorizon.hollowengine.client.ui.text.UiInlineWidgetRun
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold
import kotlin.test.*

class EditorGutterFoldingTest {
    private val text = "fun outer() {\n fun inner() {\n  call()\n }\n}\nfun after() {}"
    private val folds = listOf(CodeFold(text.indexOf('{'), text.indexOf("\nfun after"), " … }"),
        CodeFold(text.indexOf('{', text.indexOf("inner")), text.indexOf("\n}"), " … }"))

    @Test
    fun `folded braces are one editable symbol and boundary typing retains folding`() {
        val text = "onStart {\n work()\n}.next()"
        val fold = CodeFold(text.indexOf('{'), text.indexOf('}') + 1)
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, listOf(fold))
        state.folding.toggle(fold)
        val replacement = state.folding.projection().placeholders.single()
        assertEquals(fold.start, replacement.offset)
        assertEquals(fold.end - fold.start, replacement.sourceLength)
        assertEquals("{ … }", replacement.text)
        state.moveCaret(fold.start)
        state.moveCarets({ it.position + 1 })
        assertEquals(fold.end, state.caret)
        assertTrue(state.folding.isCollapsed(fold))
        state.moveCarets({ it.position - 1 })
        assertEquals(fold.start, state.caret)
        state.insert("before ")
        // A stale asynchronous analysis must not replace the locally shifted ranges.
        state.folding.update(state.text, listOf(fold))
        val shifted = fold.copy(start = fold.start + 7, end = fold.end + 7)
        assertTrue(state.folding.isCollapsed(shifted))
        state.moveCaret(shifted.end)
        state.insert(" after")
        assertTrue(state.folding.isCollapsed(shifted))
        assertEquals("onStart before {\n work()\n} after.next()", state.text)
        state.moveCaret(shifted.start)
        state.deleteForward()
        assertEquals("onStart before  after.next()", state.text)
        assertTrue(state.undo())
        assertEquals("onStart before {\n work()\n} after.next()", state.text)
    }

    @Test
    fun `clicking either half of a replacement positions the caret without revealing it`() {
        val fold = folds.first().copy(placeholder = "{…}")
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, listOf(fold))
        state.folding.toggle(fold)
        val projection = state.folding.projection()
        val metrics = EditableFieldInlayMetrics().apply { record(projection.placeholders.single(), 30f, 12f) }
        val layout = computeEditableFieldLayout(text, 12f, null, false, 400f,
            hiddenLines = projection.hiddenLines, projectedLines = projection.lines, inlayHints = projection.placeholders,
            inlayMetrics = metrics)
        val before = layout.caretAt(fold.start).x
        val after = layout.caretAt(fold.end).x
        assertTrue(after > before, "before=$before after=$after lines=${layout.lineLayouts.first()?.lines}")
        assertEquals(fold.start, layout.offsetAt(before + (after - before) * 0.2f, 1f))
        assertEquals(fold.end, layout.offsetAt(before + (after - before) * 0.8f, 1f))
        state.moveCaret(layout.offsetAt(before + (after - before) * 0.8f, 1f))
        assertTrue(state.folding.isCollapsed(fold))
        state.moveCaret(fold.start, inlayAffinity = UiInlayCaretAffinity.BEFORE)
        fun press(key: Int) = handleEditableFieldKey(state,
            UiKeyInput(UiEvent(UiEventKind.KEY_PRESSED, node = BoxNode(), key = key)), layout)
        assertTrue(press(GLFW.GLFW_KEY_RIGHT))
        assertEquals(fold.end, state.caret)
        assertTrue(press(GLFW.GLFW_KEY_LEFT))
        assertEquals(fold.start, state.caret)
        assertTrue(state.folding.isCollapsed(fold))
    }

    @Test
    fun `folding preserves full text selection undo and original logical line numbers`() {
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, folds)
        state.folding.toggle(folds.first())
        val lines = editableFieldLines(text)
        val projection = state.folding.projection()
        val hidden = projection.hiddenLines
        assertEquals(setOf(1, 2, 3, 4), hidden)
        val layout = computeEditableFieldLayout(text, 12f, null, false, 400f, hiddenLines = hidden,
            projectedLines = projection.lines, inlayHints = projection.placeholders)
        assertEquals(layout.lineHeight, layout.lineTop(5))
        assertEquals(lines[5].start, layout.offsetAt(0f, layout.lineHeight + 1f))
        assertEquals(lines[5].start, layout.visualCaretMove(0, 1))
        state.selectAll()
        assertEquals(text, state.selectedText())
        assertEquals(text, state.text)
        assertFalse(state.undo())
    }

    @Test
    fun `navigation reveals enclosing folds and nested collapsed states are retained`() {
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, folds)
        state.folding.toggle(folds.last())
        state.folding.toggle(folds.first())
        state.folding.toggle(folds.first())
        assertTrue(state.folding.isCollapsed(folds.last()))
        state.folding.toggle(folds.first())
        state.moveCaret(text.indexOf("call()"))
        assertFalse(state.folding.isCollapsed(folds.first()))
        assertFalse(state.folding.isCollapsed(folds.last()))
    }

    @Test
    fun `edits before folded code carry collapsed regions forward while interior edits unfold it`() {
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, folds)
        state.folding.toggle(folds.first())
        val edited = "// header\n$text"
        val shifted = shiftCodeFoldsThroughEdit(text, edited, folds)
        state.folding.update(edited, shifted)
        assertTrue(state.folding.isCollapsed(shifted.first()))
        val inside = edited.replace("call()", "changed()")
        state.folding.update(inside, shiftCodeFoldsThroughEdit(edited, inside, shifted))
        assertTrue(state.folding.projection().hiddenLines.isEmpty())
    }

    @Test
    fun `folding an if branch retains else and other text after its closing brace`() {
        val text = "if (ready) {\n first()\n} else {\n second()\n}\nafter()"
        val fold = CodeFold(text.indexOf('{'), text.indexOf("} else") + 1, " … }")
        val folding = TextFieldFolding()
        folding.update(text, listOf(fold))
        folding.toggle(fold)
        val lines = editableFieldLines(text)
        val projection = folding.projection()
        assertEquals(setOf(1, 2), projection.hiddenLines)
        assertTrue(projection.lines.first().text.endsWith("} else {"))
        assertEquals(" … }", projection.placeholders.single().text)
        val layout = computeEditableFieldLayout(text, 12f, null, false, 400f,
            hiddenLines = projection.hiddenLines, projectedLines = projection.lines, inlayHints = projection.placeholders)
        assertEquals(1, layout.lineLayouts.first()!!.lines.size)
    }

    @Test
    fun `regions ending after a newline can be collapsed safely`() {
        val text = "header\n hidden\nnext"
        val fold = CodeFold(0, text.indexOf("next"))
        val folding = TextFieldFolding()
        folding.update(text, listOf(fold))
        folding.toggle(fold)
        assertEquals(setOf(1), folding.projection().hiddenLines)
        assertEquals(1, folding.projection().placeholders.size)
        folding.reveal(text.indexOf("next"))
        assertTrue(folding.isCollapsed(fold))
    }

    @Test
    fun `adjacent folds merge into one row and retain exact source lengths and suffix offsets`() {
        val text = "if (ready) {\n first()\n} else {\n second()\n}\nafter()"
        val folds = listOf(CodeFold(text.indexOf('{'), text.indexOf("} else") + 1, " … }"),
            CodeFold(text.indexOf('{', text.indexOf("else")), text.lastIndexOf('}') + 1, " … }"))
        val folding = TextFieldFolding()
        folding.update(text, folds)
        folds.forEach(folding::toggle)
        val projection = folding.projection()
        assertEquals(setOf(1, 2, 3, 4), projection.hiddenLines)
        val layout = computeEditableFieldLayout(text, 12f, null, false, 400f, hiddenLines = projection.hiddenLines,
            projectedLines = projection.lines, inlayHints = projection.placeholders)
        val line = assertNotNull(layout.lineLayouts.first()).lines.single()
        assertEquals(projection.lines.first().text.length, line.sourceLength)
        assertEquals(2, line.fragments.filterIsInstance<UiInlineWidgetRun>().size)
        assertEquals(projection.placeholders.map { it.sourceLength },
            line.fragments.filterIsInstance<UiInlineWidgetRun>().map { it.widget.sourceLength })
        assertEquals(0f, layout.caretAt(text.indexOf("else")).y)
        assertEquals(layout.lineHeight, layout.caretAt(text.indexOf("after")).y)
    }

    @Test
    fun `typing a long prefix updates fold coordinates before moving the caret`() {
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, folds)
        state.folding.toggle(folds.first())
        state.moveCaret(0)
        val prefix = "// ${"header ".repeat(20)}\n"
        state.insert(prefix)
        val shifted = folds.first().copy(start = folds.first().start + prefix.length, end = folds.first().end + prefix.length)
        assertTrue(state.folding.isCollapsed(shifted))
    }

    @Test
    fun `default folds apply on opening including a single import and preserve manual expansion`() {
        val text = "import java.io.File"
        val fold = CodeFold(0, text.length, "…", prefixLength = 7, collapsedByDefault = true)
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, listOf(fold))
        assertTrue(state.folding.isCollapsed(fold))
        assertTrue(state.folding.projection().hiddenLines.isEmpty())
        assertEquals(7, state.folding.projection().placeholders.single().offset)
        state.folding.toggle(fold)
        val edited = "// header\n$text"
        val next = shiftCodeFoldsThroughEdit(text, edited, listOf(fold))
        state.folding.update(edited, next)
        assertFalse(state.folding.isCollapsed(next.single()))
        TextFieldFolding().apply {
            update(edited, next)
            assertTrue(isCollapsed(next.single()))
        }
    }

    @Test
    fun `fold button is compact uses the code font has gutter spacing and expands on click`() {
        val text = "onStart {\n work()\n}"
        val fold = CodeFold(text.indexOf('{'), text.length)
        val state = TextFieldState(text, multiline = true)
        state.folding.update(text, listOf(fold))
        state.folding.toggle(fold)
        val source = listOf("widgets", "ide").joinToString("\n") { name ->
            requireNotNull(javaClass.getResourceAsStream("/assets/hollowengine/ui/styles/$name.hss"))
                .bufferedReader().use { it.readText() }
        }
        HollowUiSurface(stylesheet = compileHss(source)).use { surface ->
            surface.setContent {
                UiCodeEditor(text, {}, state = state, folds = listOf(fold), id = "editor",
                    modifier = Modifier.size(500.px, 250.px))
            }
            var time = 0L
            fun frame() = surface.frame(600f, 350f, -1f, -1f, ++time * 16_000_000L)
            repeat(6) { frame() }
            val current = frame()
            val button = assertNotNull(current.nodeByIdentifier("fold-placeholder"))
            assertNotNull(button.id)
            val label = button.children.single { "code-editor-inlay-text" in it.tags }
            assertEquals(state.fontSize, label.resolvedSnapshot.fontSize)
            assertEquals(0f, label.resolvedSnapshot.padding.left.resolve(0f))
            val rect = current.layout[button].rect
            val divider = assertNotNull(current.nodeByIdentifier("gutter-separator"))
            val dividerRect = current.layout[divider].rect
            val gap = rect.x - dividerRect.x - dividerRect.width -
                UiTextLayouter.measureTextWidth("onStart ", state.fontSize, state.fontFamily)
            assertTrue(gap > 0f, "gap=$gap")
            val textWidth = UiTextLayouter.measureTextWidth(fold.placeholder, state.fontSize, state.fontFamily)
            assertTrue(rect.width <= textWidth + 5f, "button=${rect.width}, text=$textWidth")
            assertTrue(surface.runtime.mouseClicked(rect.x + rect.width / 2f, rect.y + rect.height / 2f, 0))
            surface.runtime.mouseReleased(rect.x + rect.width / 2f, rect.y + rect.height / 2f, 0)
            repeat(4) { frame() }
            assertFalse(state.folding.isCollapsed(fold))
            assertEquals(text, state.text)
        }
    }

    @Test
    fun `gutter places icons after numbers shows fold controls on hover and uses appropriate cursors`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/assets/hollowengine/ui/styles/widgets.hss"))
            .bufferedReader().use { it.readText() }
        HollowUiSurface(stylesheet = compileHss(source)).use { surface ->
            val state = TextFieldState(text, multiline = true)
            surface.setContent {
                UiCodeEditor(text, {}, state = state, folds = folds, id = "editor",
                    gutterMarkers = listOf(UiGutterMarker("run", 0, "play.svg", "Run", onClick = {})),
                    modifier = Modifier.size(500.px, 250.px))
            }
            var time = 0L
            fun frame(x: Float = -1f, y: Float = -1f) = surface.frame(600f, 350f, x, y, ++time * 16_000_000L)
            repeat(4) { frame() }
            val initial = frame()
            val gutter = assertNotNull(initial.nodeByIdentifier("editor-gutter"))
            val arrow = assertNotNull(initial.nodeByIdentifier("editor-fold-${folds.first().start}"))
            val marker = assertNotNull(initial.nodeByIdentifier("editor-gutter-run"))
            val divider = assertNotNull(initial.nodeByIdentifier("gutter-separator"))
            val gutterRect = initial.layout[gutter].rect
            val markerRect = initial.layout[marker].rect
            assertTrue(markerRect.x >= gutterRect.x + editableFieldGutterWidth(text, state.fontSize),
                "marker=$markerRect gutter=$gutterRect font=${state.fontSize}")
            assertEquals(1f, initial.layout[divider].rect.width)
            assertEquals(0f, arrow.resolvedSnapshot.opacity)
            repeat(3) { frame(gutterRect.x + 2f, gutterRect.y + 2f) }
            assertTrue(arrow.resolvedSnapshot.opacity > 0f && arrow.resolvedSnapshot.opacity < 1f)
            repeat(10) { frame(gutterRect.x + 2f, gutterRect.y + 2f) }
            assertEquals(1f, arrow.resolvedSnapshot.opacity)
            assertEquals(UiCursorShape.DEFAULT, surface.runtime.cursor)
            repeat(3) { frame(markerRect.x + markerRect.width / 2f, markerRect.y + markerRect.height / 2f) }
            assertEquals(UiCursorShape.HAND, surface.runtime.cursor)
            repeat(3) { frame() }
            assertTrue(arrow.resolvedSnapshot.opacity > 0f && arrow.resolvedSnapshot.opacity < 1f)
            repeat(10) { frame() }
            assertEquals(0f, arrow.resolvedSnapshot.opacity)
        }
    }

    @Test
    fun `gutter clicks collapse code and actions receive input without editing text`() {
        val state = TextFieldState(text, multiline = true)
        var clicks = 0
        HollowUiSurface().use { surface ->
            surface.setContent {
                UiCodeEditor(text, {}, state = state, folds = folds, id = "editor",
                    gutterMarkers = listOf(UiGutterMarker("run", 0, "run.svg", "Run", onClick = { clicks++ })),
                    modifier = Modifier.size(500.px, 250.px))
            }
            var tick = 0L
            fun frame() = surface.frame(600f, 350f, -1f, -1f, ++tick * 16_000_000L)
            repeat(4) { frame() }
            fun click(id: String) {
                val current = frame()
                val node = assertNotNull(current.nodeByIdentifier(id), id)
                val rect = current.layout[node].rect
                val x = rect.x + rect.width / 2f
                val y = rect.y + rect.height / 2f
                assertTrue(surface.runtime.mouseClicked(x, y, 0))
                surface.runtime.mouseReleased(x, y, 0)
                repeat(4) { frame() }
            }
            click("editor-gutter-run")
            assertEquals(1, clicks)
            click("editor-fold-${folds.first().start}")
            assertTrue(state.folding.isCollapsed(folds.first()))
            assertEquals(text, state.text)
            click("editor-fold-${folds.first().start}")
            assertFalse(state.folding.isCollapsed(folds.first()))
        }
    }
}
