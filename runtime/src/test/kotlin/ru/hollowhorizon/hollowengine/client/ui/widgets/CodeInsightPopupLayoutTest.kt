package ru.hollowhorizon.hollowengine.client.ui.widgets

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.scrollable
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextFonts
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import kotlin.test.*

class CodeInsightPopupLayoutTest {
    @Test
    fun `signature presentation omits callable name and preserves syntax colors`() {
        val label = "create(name: String = ..., count: Int): Result"
        val presentationStart = label.indexOf('(')
        val activeStart = label.indexOf("name")
        val activeEnd = label.indexOf(',', activeStart)
        val highlights = listOf(
            UiCodeInsightHighlight(activeStart until activeStart + "name".length, TokenType.VALUE_ARGUMENT_NAME),
            UiCodeInsightHighlight(label.indexOf("String") until label.indexOf("String") + "String".length, TokenType.CLASS),
            UiCodeInsightHighlight(label.indexOf("...") until label.indexOf("...") + 3, TokenType.DEFAULT),
        )

        val segments = codeInsightSegments(
            text = label,
            highlights = highlights,
            visibleRange = presentationStart until label.length,
            activeRange = activeStart until activeEnd,
        )
        val rendered = segments.joinToString("") { segment -> label.substring(segment.start, segment.end) }

        assertEquals("(name: String = ..., count: Int): Result", rendered)
        assertFalse("create" in rendered)
        assertEquals(TokenType.VALUE_ARGUMENT_NAME, segments.single { it.start == activeStart }.tokenType)
        assertEquals(TokenType.CLASS, segments.single { it.start == label.indexOf("String") }.tokenType)
        assertEquals(TokenType.DEFAULT, segments.single { it.start == label.indexOf("...") }.tokenType)
        assertTrue(segments.filter { it.start in activeStart until activeEnd }.all(CodeInsightSegment::active))
    }

    @Test
    fun `signature popup grows for wrapped lines without scrolling inside a short editor`() {
        val state = TextFieldState("call(", multiline = true).apply { focus() }
        val label = "(screen: ResourceLocation, kind: UiSurfaceKind, charDelay: Int, " +
                "choiceRevealDelay: Long, skipDelay: Int, portrait: ResourceLocation, voice: SoundEvent)"
        val provider = UiSignatureHelpProvider {
            UiTextSignatureHelp(
                anchor = state.text.length,
                signatures = listOf(UiTextSignature(label, emptyList())),
            )
        }

        HollowUiSurface().use { surface ->
            surface.setContent {
                EditableTextField(
                    state = state,
                    modifier = Modifier.size(620.px, 40.px),
                    insight = UiCodeInsight(signatureHelp = provider),
                )
            }

            var frame: HollowUiFrame? = null
            repeat(4) { index ->
                frame = surface.frame(660f, 240f, -1f, -1f, index * 16_000_000L)
            }
            val current = assertNotNull(frame)
            val popup = assertNotNull(current.nodeByIdentifier("editable-text-field-signature-help"))
            val popupLayout = current.layout[popup]

            val line = UiTextFonts.resolve(state.fontFamily).lineHeight(state.fontSize)
            assertTrue(
                popupLayout.rect.height > line * 1.5f,
                "wrapped lines must grow the popup: ${popupLayout.rect} for a $line line",
            )
            assertEquals(0f, popupLayout.scrollRange.y, 0.01f, "two wrapped lines must not create a scrollbar")
            assertTrue(popupLayout.rect.width < 604f, "fit width must hug the longest wrapped line")
        }
    }

    @Test
    fun `signature popup lays out overloads and continuous active parameters`() {
        val state = TextFieldState("call(", multiline = true).apply { focus() }
        val labels = listOf(
            "(name: String, vararg params: StoryParam, handler: suspend " +
                    "StoryCallContext.(StoryArguments) -> Unit): Unit",
            "(name: String, params: List<String>, optional: Int = ..., block: suspend " +
                    "StoryCallContext.(String) -> Unit): Unit",
        )
        val provider = UiSignatureHelpProvider {
            UiTextSignatureHelp(
                anchor = state.text.length,
                signatures = List(8) { index ->
                    val label = labels[index % labels.size]
                    val parameterStart = label.indexOf(if (index % labels.size == 0) "vararg" else "params")
                    UiTextSignature(label, listOf(parameterStart until label.indexOf(',', parameterStart)))
                },
            )
        }

        HollowUiSurface().use { surface ->
            surface.setContent {
                EditableTextField(
                    state = state,
                    modifier = Modifier.size(620.px, 90.px),
                    insight = UiCodeInsight(signatureHelp = provider),
                )
            }

            var frame: HollowUiFrame? = null
            repeat(4) { index ->
                frame = surface.frame(660f, 240f, -1f, -1f, index * 16_000_000L)
            }
            val current = assertNotNull(frame)
            val popup = assertNotNull(current.nodeByIdentifier("editable-text-field-signature-help"))
            assertNotNull(current.layout[popup].clip, "signature popup must clip wrapped glyphs")
            val popupLayout = current.layout[popup]
            val rect = popupLayout.rect

            assertTrue(rect.width < 590f, "the popup must hug its longest wrapped line: $rect")
            assertTrue(popup.resolvedSnapshot.scrollable, "constrained signatures must be vertically scrollable")
            assertTrue(rect.height <= 240f, "the popup must stay inside the screen: $rect")
            assertEquals(0f, popupLayout.scrollRange.y, 0.01f, "content that fits the screen must not scroll")
            val activeParameter = assertNotNull(current.nodeByIdentifier("ide-active-parameter")) as BaseUiNode
            val decoration = assertNotNull(activeParameter.inlineDecoration)
            assertTrue(decoration.lines.all { it.width > 0f }, "active parameter must use continuous line boxes")
        }
    }

    @Test
    fun `hovering a symbol publishes its delayed popup`() = runBlocking {
        val text = "val target = HoverName"
        val symbolStart = text.indexOf("HoverName")
        val state = TextFieldState(text, multiline = true)
        var requestedOffset = -1
        val provider = UiHoverInfoProvider { context ->
            requestedOffset = context.caret
            if (context.caret in symbolStart until text.length) {
                UiTextHoverInfo(
                    symbolStart,
                    text.length,
                    "UiDialoguePresentation(screen: ResourceLocation, kind: UiSurfaceKind, " +
                            "charDelay: Int, choiceRevealDelay: Long)",
                    "Creates a dialogue presentation with configurable surface and reveal timing.",
                )
            } else {
                null
            }
        }
        val pointerX = UiTextLayouter.measureTextWidth(
            text.substring(0, symbolStart + 2),
            state.fontSize,
            state.fontFamily,
        )

        HollowUiSurface().use { surface ->
            surface.setContent {
                EditableTextField(
                    state = state,
                    modifier = Modifier.size(360.px, 100.px),
                    insight = UiCodeInsight(hover = provider),
                )
            }

            surface.frame(400f, 160f, pointerX, state.fontSize / 2f, 0L)
            surface.frame(400f, 160f, pointerX, state.fontSize / 2f, 16_000_000L)
            delay(1_050L)
            var frame: HollowUiFrame? = null
            repeat(3) { index ->
                frame = surface.frame(400f, 160f, pointerX, state.fontSize / 2f, (index + 2) * 16_000_000L)
            }

            assertTrue(requestedOffset in symbolStart until text.length, "hover queried wrong offset: $requestedOffset")
            val current = assertNotNull(frame)
            val popup = assertNotNull(current.nodeByIdentifier("editable-text-field-hover-info"))
            assertNotNull(
                current.layout[popup].clip,
                "the popup must clip wrapped glyphs to its border box",
            )
            val popupContent = current.layout[popup].content
            for (tag in listOf("ide-hover-signature", "ide-hover-documentation")) {
                val child = assertNotNull(current.nodeByIdentifier(tag))
                val childLayout = current.layout[child]
                val rect = childLayout.rect
                assertTrue(rect.x >= popupContent.x - 0.5f, "$tag escapes popup on the left: $rect vs $popupContent")
                assertTrue(rect.y >= popupContent.y - 0.5f, "$tag escapes popup at the top: $rect vs $popupContent")
                assertTrue(
                    rect.x + rect.width <= popupContent.x + popupContent.width + 0.5f,
                    "$tag escapes popup on the right: $rect vs $popupContent",
                )
                assertTrue(
                    rect.y + rect.height <= popupContent.y + popupContent.height + 0.5f,
                    "$tag escapes popup at the bottom: $rect vs $popupContent",
                )
                childLayout.textLayout?.let { textLayout ->
                    assertTrue(
                        textLayout.width <= rect.width + 0.5f && textLayout.height <= rect.height + 0.5f,
                        "$tag draws outside its measured box: $textLayout vs $rect",
                    )
                }
            }
        }
    }
}
