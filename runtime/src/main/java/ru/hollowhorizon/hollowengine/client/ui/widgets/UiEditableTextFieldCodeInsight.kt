package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.toUiColor
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.style.parseColor
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType

internal data class EditableFieldHoverTarget(val offset: Int)

internal class EditableFieldCodeInsightState {
    private var signatureIdentity: SignatureIdentity? = null
    private var dismissedSignature: SignatureIdentity? = null

    var signatureHelp by mutableStateOf<UiTextSignatureHelp?>(null)
        private set

    fun publishSignature(
        text: String,
        caret: Int,
        provider: UiSignatureHelpProvider,
        help: UiTextSignatureHelp?,
    ) {
        val identity = SignatureIdentity(text, caret, provider)
        signatureIdentity = identity
        signatureHelp = help?.takeUnless { identity == dismissedSignature }
    }

    fun clearSignature() {
        signatureIdentity = null
        dismissedSignature = null
        signatureHelp = null
    }

    fun dismissSignature(): Boolean {
        if (signatureHelp == null) return false
        dismissedSignature = signatureIdentity
        signatureHelp = null
        return true
    }

    private data class SignatureIdentity(
        val text: String,
        val caret: Int,
        val provider: UiSignatureHelpProvider,
    )
}

@Composable
internal fun EditableFieldCodeInsight(
    state: EditableFieldCodeInsightState,
    text: String,
    caret: Int,
    focused: Boolean,
    completionVisible: Boolean,
    hoverTarget: EditableFieldHoverTarget?,
    signatureProvider: UiSignatureHelpProvider?,
    hoverProvider: UiHoverInfoProvider?,
    revision: Long,
    layout: EditableFieldLayout,
    scrollState: UiScrollHandle,
    contentOffsetX: Float,
) {
    LaunchedEffect(text, caret, focused, completionVisible, signatureProvider, revision) {
        if (focused && signatureProvider != null) {
            if (completionVisible) return@LaunchedEffect
            val context = UiCompletionContext(text, caret)
            val help = if (signatureProvider is UiDeferredSignatureHelpProvider) {
                // A completed empty result closes the popup; a pending one preserves it.
                val result = signatureProvider.query(context) ?: return@LaunchedEffect
                result.help
            } else signatureProvider.help(context)
            state.publishSignature(
                text = text,
                caret = caret,
                provider = signatureProvider,
                help = help,
            )
        } else {
            state.clearSignature()
        }
    }
    state.signatureHelp?.takeIf { it.signatures.isNotEmpty() }?.let { help ->
        EditableFieldSignatureHelpPopup(help, layout, scrollState, contentOffsetX, !completionVisible && focused)
    }

    var hoverReady by remember { mutableStateOf(false) }
    var hoverInfo by remember { mutableStateOf<UiTextHoverInfo?>(null) }
    LaunchedEffect(text, hoverTarget?.offset, hoverProvider, completionVisible) {
        hoverReady = false
        hoverInfo = null
        if (completionVisible || hoverTarget == null || hoverProvider == null) return@LaunchedEffect
        delay(EditorHoverDelayMillis)
        hoverReady = true
    }
    LaunchedEffect(text, hoverTarget?.offset, hoverReady, hoverProvider, revision, completionVisible) {
        hoverInfo = if (!completionVisible && hoverReady && hoverTarget != null && hoverProvider != null) {
            hoverProvider.hover(UiCompletionContext(text, hoverTarget.offset))
        } else {
            null
        }
    }
    hoverInfo?.let { info ->
        EditableFieldHoverPopup(info, layout, scrollState, contentOffsetX, !completionVisible)
    }
}

@Composable
private fun EditableFieldSignatureHelpPopup(
    help: UiTextSignatureHelp,
    layout: EditableFieldLayout,
    scrollState: UiScrollHandle,
    contentOffsetX: Float,
    visible: Boolean,
) {
    val anchor = layout.caretAt(help.anchor)
    val at = scrollState.toScreen(contentOffsetX + anchor.x - scrollState.offsetX, anchor.y - scrollState.offsetY)
    Popup(
        anchorBounds = UiRect(at.x, at.y, 0f, layout.fontSize * scrollState.screenScale),
        alignment = UiPopupAlignment(
            anchorVertical = UiAlign.START,
            popupVertical = UiAlign.END,
            offsetY = -CodeInsightPopupGap,
        ),
        id = "editable-text-field-signature-help",
        visible = visible,
        tags = listOf("ide-code-insight-popup", "ide-signature-help"),
        modifier = codeInsightPopupModifier(layout, scrollState.viewport),
        dismissOnOutside = false,
    ) {
        Column(modifier = Modifier.size(UiLength.Fit, UiLength.Fit).gap(3.px)) {
            help.signatures.take(CodeInsightMaxVisibleSignatures).forEach { signature ->
                SignatureText(signature, help.activeParameter)
            }
            val hidden = help.signatures.size - CodeInsightMaxVisibleSignatures
            if (hidden > 0) {
                Text("+$hidden overloads", modifier = Modifier.foreground(CodeInsightDocumentation))
            }
        }
    }
}

@Composable
private fun SignatureText(signature: UiTextSignature, activeParameter: Int) {
    CodeInsightText(
        text = signature.label,
        highlights = signature.highlights,
        visibleRange = signature.presentation,
        activeRange = signature.parameters.getOrNull(activeParameter),
    )
}

@Composable
private fun EditableFieldHoverPopup(
    info: UiTextHoverInfo,
    layout: EditableFieldLayout,
    scrollState: UiScrollHandle,
    contentOffsetX: Float,
    visible: Boolean,
) {
    val start = layout.caretAt(info.start)
    val end = layout.caretAt(info.end)
    val at = scrollState.toScreen(contentOffsetX + start.x - scrollState.offsetX, start.y - scrollState.offsetY)
    val scale = scrollState.screenScale
    Popup(
        anchorBounds = UiRect(
            at.x,
            at.y,
            (end.x - start.x).coerceAtLeast(layout.fontSize) * scale,
            layout.fontSize * scale,
        ),
        alignment = UiPopupAlignment.BelowStart.copy(offsetY = CodeInsightPopupGap),
        id = "editable-text-field-hover-info",
        visible = visible,
        tags = listOf("ide-code-insight-popup", "ide-hover-info"),
        modifier = codeInsightPopupModifier(layout, scrollState.viewport),
        dismissOnOutside = false,
    ) {
        Column(modifier = Modifier.size(UiLength.Fit, UiLength.Fit).gap(5.px)) {
            CodeInsightText(
                text = info.signature,
                highlights = info.highlights,
                tags = listOf("ide-hover-signature"),
            )
            info.documentation?.takeIf(String::isNotBlank)?.let { documentation ->
                Text(
                    documentation,
                    tags = listOf("ide-hover-documentation"),
                    modifier = Modifier.foreground(CodeInsightDocumentation).textWrap(true),
                )
            }
        }
    }
}

@Composable
private fun CodeInsightText(
    text: String,
    highlights: List<UiCodeInsightHighlight>,
    visibleRange: IntRange = text.indices,
    activeRange: IntRange? = null,
    tags: List<String> = emptyList(),
) {
    val segments = codeInsightSegments(text, highlights, visibleRange, activeRange)
    val firstActive = segments.indexOfFirst(CodeInsightSegment::active)
    val lastActive = segments.indexOfLast(CodeInsightSegment::active)
    Text(tags = tags, modifier = Modifier.textWrap(true)) {
        if (firstActive < 0) {
            CodeInsightSpans(text, segments)
        } else {
            CodeInsightSpans(text, segments.subList(0, firstActive))
            Text(tags = listOf("ide-active-parameter")) {
                CodeInsightSpans(text, segments.subList(firstActive, lastActive + 1))
            }
            CodeInsightSpans(text, segments.subList(lastActive + 1, segments.size))
        }
    }
}

@Composable
private fun CodeInsightSpans(text: String, segments: List<CodeInsightSegment>) {
    segments.forEach { segment ->
        Span(
            text.substring(segment.start, segment.end),
            modifier = Modifier.foreground(segment.tokenType.toUiColor()),
        )
    }
}

internal fun codeInsightSegments(
    text: String,
    highlights: List<UiCodeInsightHighlight>,
    visibleRange: IntRange = text.indices,
    activeRange: IntRange? = null,
): List<CodeInsightSegment> {
    val visibleStart = visibleRange.first.coerceIn(0, text.length)
    val visibleEnd = if (visibleRange.isEmpty()) {
        visibleStart
    } else {
        (visibleRange.last + 1).coerceIn(visibleStart, text.length)
    }
    if (visibleStart == visibleEnd) return emptyList()

    val boundaries = sortedSetOf(visibleStart, visibleEnd)
    fun addBoundaries(range: IntRange) {
        if (range.isEmpty()) return
        val start = range.first.coerceIn(visibleStart, visibleEnd)
        val end = (range.last + 1).coerceIn(visibleStart, visibleEnd)
        if (start < end) {
            boundaries += start
            boundaries += end
        }
    }
    highlights.forEach { addBoundaries(it.range) }
    activeRange?.let(::addBoundaries)

    return boundaries.zipWithNext().map { (start, end) ->
        val tokenType = highlights.lastOrNull { start in it.range }?.tokenType ?: TokenType.DEFAULT
        CodeInsightSegment(
            start = start,
            end = end,
            tokenType = tokenType,
            active = activeRange?.let { start in it } == true,
        )
    }
}

internal data class CodeInsightSegment(
    val start: Int,
    val end: Int,
    val tokenType: TokenType,
    val active: Boolean,
)

@Composable
private fun codeInsightPopupModifier(layout: EditableFieldLayout, viewport: UiRect): Modifier {
    val maxWidth = (viewport.width - CodeInsightViewportMargin * 2f).coerceAtLeast(1f)
    return Modifier.size(UiLength.Fit, UiLength.Fit)
        .maxSize(width = maxWidth.px)
        .clip()
        .scrollable(vertical = true, horizontal = false, hasHorizontalScrollbar = false)
        .fontSize(layout.fontSize)
        .let { base -> layout.fontFamily?.let { base.fontFamily(it) } ?: base }
}

private const val EditorHoverDelayMillis = 1_000L
private const val CodeInsightPopupGap = 5f
private const val CodeInsightViewportMargin = 8f
private const val CodeInsightMaxVisibleSignatures = 6
private val CodeInsightDocumentation = parseColor("#A9B7C6")
