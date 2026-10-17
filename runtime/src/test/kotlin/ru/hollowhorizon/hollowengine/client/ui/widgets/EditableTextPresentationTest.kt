package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.HollowUiComposition
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class EditableTextPresentationTest {
    private val style = UiInlineStyle()

    @Test
    fun `highlight sanitizer reuses already normalized sorted spans`() {
        val highlights = listOf(
            UiTextHighlight(0, 3, style),
            UiTextHighlight(4, 8, style),
        )

        assertSame(highlights, sanitizeTextHighlights(12, highlights))
    }

    @Test
    fun `highlight sanitizer clamps drops empty spans and sorts only when needed`() {
        val highlights = listOf(
            UiTextHighlight(8, 20, style),
            UiTextHighlight(3, 3, style),
            UiTextHighlight(-4, 2, style),
        )

        assertEquals(
            listOf(
                UiTextHighlight(0, 2, style),
                UiTextHighlight(8, 10, style),
            ),
            sanitizeTextHighlights(10, highlights),
        )
    }

    @Test
    fun `no caret offset is preserved instead of clamping to zero`() {
        val exact = EditableTextPresentation.exact(
            text = "val a = 1",
            caret = UiNoCaretOffset,
            highlighter = null,
            inlayHintsProvider = null,
            inlayRevision = 0L,
            highlights = emptyList(),
            inlayHints = emptyList(),
        )

        assertEquals(UiNoCaretOffset, exact.caret)
        assertFalse(exact.matches("val a = 1", 0, null, null, 0L))
        assertEquals(UiNoCaretOffset, exact.withCaret(UiNoCaretOffset).caret)
    }

    @Test
    fun `presentation cache returns the same estimate for unchanged inputs`() {
        val highlighter = UiSyntaxHighlighter { emptyList() }
        val cache = EditableTextPresentationCache()

        val first = cache.estimate("val a = 1", 3, highlighter, emptyList(), null, 0L)
        val second = cache.estimate("val a = 1", 3, highlighter, emptyList(), null, 0L)

        assertSame(first, second)
    }

    @Test
    fun `caret only estimate does not replace accepted analysis`() {
        val highlighter = UiSyntaxHighlighter { emptyList() }
        val provider = UiInlayHintsProvider { emptyList() }
        val cache = EditableTextPresentationCache()
        val exact = EditableTextPresentation.exact(
            text = "val a = 1",
            caret = 4,
            highlighter = highlighter,
            inlayHintsProvider = provider,
            inlayRevision = 2L,
            highlights = listOf(UiTextHighlight(4, 5, style)),
            inlayHints = listOf(UiInlayHint(5, ": Int")),
        )
        cache.accept(exact)

        val moved = cache.estimate("val a = 1", 9, highlighter, emptyList(), provider, 2L)

        assertEquals(9, moved.caret)
        assertSame(exact.highlights, moved.highlights)
        assertSame(exact.inlayHints, moved.inlayHints)
        assertSame(exact, cache.estimate("val a = 1", 4, highlighter, emptyList(), provider, 2L))
    }

    @Test
    fun `presentation cache exposes exact highlights and inlays for edited text`() {
        val highlighter = UiSyntaxHighlighter { emptyList() }
        val provider = UiInlayHintsProvider { emptyList() }
        val cache = EditableTextPresentationCache()
        cache.accept(
            EditableTextPresentation.exact(
                text = "val a = 1",
                caret = 9,
                highlighter = highlighter,
                inlayHintsProvider = provider,
                inlayRevision = 0L,
                highlights = listOf(UiTextHighlight(4, 5, style)),
                inlayHints = listOf(UiInlayHint(5, ": Int")),
            ),
        )

        cache.estimate("val aa = 1", 10, highlighter, emptyList(), provider, 1L)
        val exact = EditableTextPresentation.exact(
            text = "val aa = 1",
            caret = 10,
            highlighter = highlighter,
            inlayHintsProvider = provider,
            inlayRevision = 1L,
            highlights = listOf(UiTextHighlight(4, 6, style)),
            inlayHints = listOf(UiInlayHint(6, ": Int")),
        )
        cache.accept(exact)

        assertSame(exact, cache.estimate("val aa = 1", 10, highlighter, emptyList(), provider, 1L))
        assertEquals(listOf(UiTextHighlight(4, 6, style)), exact.highlights)
        assertEquals(listOf(UiInlayHint(6, ": Int")), exact.inlayHints)
    }

    @Test
    fun `presentation cache keeps accepted analysis ahead of deferred estimate`() {
        val highlighter = UiSyntaxHighlighter { emptyList() }
        val cache = EditableTextPresentationCache()
        val full = EditableTextPresentation.exact(
            text = "val a = 1",
            caret = 4,
            highlighter = highlighter,
            inlayHintsProvider = null,
            inlayRevision = 1L,
            highlights = listOf(UiTextHighlight(4, 5, style)),
            inlayHints = emptyList(),
        )
        val light = EditableTextPresentation.exact(
            text = "val a = 1",
            caret = 4,
            highlighter = highlighter,
            inlayHintsProvider = null,
            inlayRevision = 1L,
            highlights = listOf(UiTextHighlight(0, 3, style)),
            inlayHints = emptyList(),
        )

        cache.accept(full)
        cache.acceptEstimate(light)

        assertSame(full, cache.estimate("val a = 1", 4, highlighter, emptyList(), null, 1L))
    }

    @Test
    fun `publishing full analysis does not trigger deferred light analysis`() = runBlocking {
        val lightCalls = AtomicInteger()
        val exactCalls = AtomicInteger()
        val revision = mutableStateOf(0L)
        var exactHighlights: List<UiTextHighlight>? = null
        val highlighter = object : UiDeferredSyntaxHighlighter {
            override fun highlight(text: String, caret: Int): List<UiTextHighlight> {
                lightCalls.incrementAndGet()
                return listOf(UiTextHighlight(0, 3, style))
            }

            override fun exactHighlight(text: String, caret: Int): List<UiTextHighlight>? {
                exactCalls.incrementAndGet()
                return exactHighlights
            }
        }

        HollowUiComposition().use { composition ->
            composition.setContent {
                rememberEditableTextPresentation(
                    text = "val a = 1",
                    caret = 4,
                    highlighter = highlighter,
                    inlayHints = emptyList(),
                    inlayHintsProvider = null,
                    inlayRevision = revision.value,
                )
            }
            composition.applyPendingChanges()
            composition.settleDeferredPresentation(lightCalls, exactCalls)
            val initialLightCalls = lightCalls.get()
            val initialExactCalls = exactCalls.get()

            exactHighlights = listOf(UiTextHighlight(4, 5, style))
            revision.value++
            composition.applyPendingChanges()
            composition.settleDeferredPresentation(lightCalls, exactCalls)

            assertEquals(initialLightCalls, lightCalls.get())
            assertEquals(initialExactCalls + 1, exactCalls.get())
        }
    }
    @Test
    fun `deferred text analyzer publishes approximate inlays before exact analysis`() = runBlocking {
        val calls = AtomicInteger()
        val revision = mutableStateOf(0L)
        val text = mutableStateOf("val a = 1")
        val analyses = mutableMapOf<String, UiTextAnalysis>()
        val highlighter = object : UiDeferredTextAnalyzer {
            override fun highlight(text: String, caret: Int): List<UiTextHighlight> {
                return cachedAnalysis(text, caret)?.highlights.orEmpty()
            }

            override fun cachedAnalysis(text: String, caret: Int): UiTextAnalysis? {
                return null
            }

            override fun deferredAnalysis(text: String, caret: Int): UiTextAnalysis? {
                calls.incrementAndGet()
                return analyses[text]
            }
        }
        var presentation = EditableTextPresentation.Empty

        HollowUiComposition().use { composition ->
            analyses["val a = 1"] = UiTextAnalysis(
                highlights = listOf(UiTextHighlight(4, 5, style)),
                inlayHints = listOf(UiInlayHint(5, ": Int")),
                exact = true,
            )
            composition.setContent {
                presentation = rememberEditableTextPresentation(
                    text = text.value,
                    caret = text.value.length,
                    highlighter = highlighter,
                    inlayHints = emptyList(),
                    inlayHintsProvider = null,
                    inlayRevision = revision.value,
                )
            }
            composition.applyPendingChanges()
            composition.settleDeferredPresentation(AtomicInteger(), calls)
            assertEquals(listOf(UiTextHighlight(4, 5, style)), presentation.highlights)
            assertEquals(listOf(UiInlayHint(5, ": Int")), presentation.inlayHints)

            text.value = "x${text.value}"
            composition.applyPendingChanges()
            assertEquals("xval a = 1", presentation.text)
            assertEquals(listOf(UiInlayHint(6, ": Int")), presentation.inlayHints)

            text.value = text.value.removePrefix("x")
            composition.applyPendingChanges()
            assertEquals("val a = 1", presentation.text)
            assertEquals(listOf(UiInlayHint(5, ": Int")), presentation.inlayHints)

            text.value = "val answer = 1"
            analyses[text.value] = UiTextAnalysis(
                highlights = listOf(UiTextHighlight(0, 3, style)),
                inlayHints = listOf(UiInlayHint(10, ": Int")),
                exact = false,
            )
            composition.applyPendingChanges()
            composition.settleDeferredPresentation(AtomicInteger(), calls)
            assertEquals(listOf(UiTextHighlight(0, 3, style)), presentation.highlights)
            assertEquals(listOf(UiInlayHint(10, ": Int")), presentation.inlayHints)

            analyses[text.value] = UiTextAnalysis(
                highlights = listOf(UiTextHighlight(4, 10, style)),
                inlayHints = listOf(UiInlayHint(10, ": Int")),
                exact = true,
            )
            revision.value++
            composition.applyPendingChanges()
            assertEquals(listOf(UiInlayHint(10, ": Int")), presentation.inlayHints)
            composition.settleDeferredPresentation(AtomicInteger(), calls)
            assertEquals(listOf(UiTextHighlight(4, 10, style)), presentation.highlights)
            assertEquals(listOf(UiInlayHint(10, ": Int")), presentation.inlayHints)
        }
    }
    @Test
    fun `caret movement does not call deferred light analysis`() = runBlocking {
        val lightCalls = AtomicInteger()
        val exactCalls = AtomicInteger()
        val caret = mutableStateOf(4)
        val highlighter = object : UiDeferredSyntaxHighlighter {
            override fun highlight(text: String, caret: Int): List<UiTextHighlight> {
                lightCalls.incrementAndGet()
                return emptyList()
            }

            override fun exactHighlight(text: String, caret: Int): List<UiTextHighlight>? {
                exactCalls.incrementAndGet()
                return listOf(UiTextHighlight(4, 5, style))
            }
        }

        HollowUiComposition().use { composition ->
            composition.setContent {
                rememberEditableTextPresentation(
                    text = "val a = 1",
                    caret = caret.value,
                    highlighter = highlighter,
                    inlayHints = emptyList(),
                    inlayHintsProvider = null,
                    inlayRevision = 0L,
                )
            }
            composition.applyPendingChanges()
            composition.settleDeferredPresentation(lightCalls, exactCalls)
            val settledExactCalls = exactCalls.get()

            caret.value = 8
            composition.applyPendingChanges()
            composition.settleDeferredPresentation(lightCalls, exactCalls)

            assertEquals(0, lightCalls.get())
            assertEquals(settledExactCalls, exactCalls.get())
        }
    }

    @Test
    fun `editable text field settles after asynchronous presentation update`() = runBlocking {
        val state = TextFieldState(initialText = "val a = 1", multiline = true)
        val highlighterCalls = AtomicInteger()
        val inlayCalls = AtomicInteger()
        val highlighter = UiSyntaxHighlighter { text ->
            highlighterCalls.incrementAndGet()
            val start = text.indexOf('b')
            if (start < 0) emptyList() else listOf(UiTextHighlight(start, start + 1, style))
        }
        val inlayProvider = UiInlayHintsProvider { text ->
            inlayCalls.incrementAndGet()
            val start = text.indexOf('b')
            if (start < 0) emptyList() else listOf(UiInlayHint(start + 1, ": Int"))
        }
        var compositions = 0

        HollowUiComposition().use { composition ->
            composition.setContent {
                compositions++
                EditableTextField(
                    state = state,
                    syntaxHighlighter = highlighter,
                    inlayHintsProvider = inlayProvider,
                    modifier = Modifier.size(240.px, 120.px),
                )
            }
            composition.applyPendingChanges()
            composition.settlePresentation(highlighterCalls, inlayCalls)

            state.setText("val b = 2")
            composition.applyPendingChanges()
            composition.settlePresentation(highlighterCalls, inlayCalls)

            val settledCompositions = compositions
            val settledHighlighterCalls = highlighterCalls.get()
            val settledInlayCalls = inlayCalls.get()
            repeat(3) {
                delay(20)
                assertFalse(composition.applyPendingChanges())
                assertEquals(settledCompositions, compositions)
                assertEquals(settledHighlighterCalls, highlighterCalls.get())
                assertEquals(settledInlayCalls, inlayCalls.get())
            }
        }
    }

    private suspend fun HollowUiComposition.settleDeferredPresentation(
        lightCalls: AtomicInteger,
        exactCalls: AtomicInteger,
    ) {
        var stableFrames = 0
        var previousLightCalls = lightCalls.get()
        var previousExactCalls = exactCalls.get()
        repeat(50) {
            delay(10)
            val changed = applyPendingChanges()
            val nextLightCalls = lightCalls.get()
            val nextExactCalls = exactCalls.get()
            if (!changed &&
                nextLightCalls == previousLightCalls &&
                nextExactCalls == previousExactCalls
            ) {
                stableFrames++
                if (stableFrames >= 3) return
            } else {
                stableFrames = 0
            }
            previousLightCalls = nextLightCalls
            previousExactCalls = nextExactCalls
        }
    }
    private suspend fun HollowUiComposition.settlePresentation(
        highlighterCalls: AtomicInteger,
        inlayCalls: AtomicInteger,
    ) {
        var stableFrames = 0
        var previousHighlighterCalls = highlighterCalls.get()
        var previousInlayCalls = inlayCalls.get()
        repeat(50) {
            delay(10)
            val changed = applyPendingChanges()
            val nextHighlighterCalls = highlighterCalls.get()
            val nextInlayCalls = inlayCalls.get()
            if (!changed &&
                nextHighlighterCalls == previousHighlighterCalls &&
                nextInlayCalls == previousInlayCalls
            ) {
                stableFrames++
                if (stableFrames >= 3) return
            } else {
                stableFrames = 0
            }
            previousHighlighterCalls = nextHighlighterCalls
            previousInlayCalls = nextInlayCalls
        }
    }
}
