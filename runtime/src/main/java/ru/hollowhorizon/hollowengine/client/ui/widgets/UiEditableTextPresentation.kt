package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberEditableTextPresentation(
    text: String,
    caret: Int,
    highlighter: UiSyntaxHighlighter?,
    inlayHints: List<UiInlayHint>,
    inlayHintsProvider: UiInlayHintsProvider?,
    inlayRevision: Long,
): EditableTextPresentation {
    val cache = remember { EditableTextPresentationCache() }
    val safeCaret = normalizePresentationCaret(caret, text.length)
    val deferredAnalyzer = highlighter as? UiDeferredTextAnalyzer
    val analysisCaretKey = when {
        deferredAnalyzer != null -> safeCaret
        highlighter is UiDeferredSyntaxHighlighter -> UiNoCaretOffset
        highlighter is UiCaretAwareSyntaxHighlighter -> safeCaret
        else -> UiNoCaretOffset
    }
    var completed by remember {
        mutableStateOf(
            value = cache.estimate(text, safeCaret, highlighter, inlayHints, inlayHintsProvider, inlayRevision),
            policy = referentialEqualityPolicy(),
        )
    }
    val immediate = cache.estimate(text, safeCaret, highlighter, inlayHints, inlayHintsProvider, inlayRevision)
    val visible =
        if (completed.matches(text, safeCaret, highlighter, inlayHintsProvider, inlayRevision)) completed else immediate

    LaunchedEffect(text, analysisCaretKey, highlighter, inlayHints, inlayHintsProvider, inlayRevision) {
        val reusableInlays = cache.reusableInlays(text, inlayHintsProvider, inlayRevision)
        val analyzed = withContext(Dispatchers.Default) {
            val analysis = when {
                deferredAnalyzer != null -> {
                    val analyzed = deferredAnalyzer.deferredAnalysis(text, safeCaret) ?: return@withContext null
                    if (reusableInlays != null) analyzed.copy(inlayHints = reusableInlays) else analyzed
                }

                highlighter == null -> UiTextAnalysis(emptyList(), reusableInlays ?: inlayHints)
                highlighter is UiDeferredSyntaxHighlighter -> UiTextAnalysis(
                    highlights = highlighter.exactHighlight(text, UiNoCaretOffset) ?: return@withContext null,
                    inlayHints = reusableInlays ?: (inlayHintsProvider?.hints(text) ?: inlayHints),
                )

                highlighter is UiCaretAwareSyntaxHighlighter -> UiTextAnalysis(
                    highlights = highlighter.highlight(text, safeCaret),
                    inlayHints = reusableInlays ?: (inlayHintsProvider?.hints(text) ?: inlayHints),
                )

                else -> UiTextAnalysis(
                    highlights = highlighter.highlight(text),
                    inlayHints = reusableInlays ?: (inlayHintsProvider?.hints(text) ?: inlayHints),
                )
            }
            EditableTextPresentation.exact(
                text = text,
                caret = safeCaret,
                highlighter = highlighter,
                inlayHintsProvider = inlayHintsProvider,
                inlayRevision = inlayRevision,
                highlights = analysis.highlights,
                inlayHints = analysis.inlayHints,
            ) to analysis.exact
        } ?: return@LaunchedEffect
        val (presentation, exact) = analyzed
        if (exact) cache.accept(presentation) else cache.acceptEstimate(presentation)
        completed = presentation
    }

    return visible
}

internal class EditableTextPresentationCache {
    private var exact = EditableTextPresentation.Empty
    private var estimate = EditableTextPresentation.Empty

    fun estimate(
        text: String,
        caret: Int,
        highlighter: UiSyntaxHighlighter?,
        inlayHints: List<UiInlayHint>,
        inlayHintsProvider: UiInlayHintsProvider?,
        inlayRevision: Long,
    ): EditableTextPresentation {
        if (exact.matches(text, caret, highlighter, inlayHintsProvider, inlayRevision)) return exact
        if (exact.matchesAnalysis(text, highlighter, inlayHintsProvider, inlayRevision)) {
            return exact.withCaret(caret)
        }
        if (estimate.matches(text, caret, highlighter, inlayHintsProvider, inlayRevision)) return estimate
        if (estimate.matchesAnalysis(text, highlighter, inlayHintsProvider, inlayRevision)) {
            return estimate.withCaret(caret)
        }
        val next = if (highlighter == null && inlayHintsProvider == null) {
            EditableTextPresentation.exact(
                text = text,
                caret = caret,
                highlighter = highlighter,
                inlayHintsProvider = inlayHintsProvider,
                inlayRevision = inlayRevision,
                highlights = emptyList(),
                inlayHints = inlayHints,
            )
        } else {
            val base = estimate.takeIf { it.hasText } ?: exact
            base.shiftedTo(text, caret, highlighter, inlayHints, inlayHintsProvider, inlayRevision)
        }
        estimate = next
        if (highlighter == null && inlayHintsProvider == null) exact = next
        return next
    }

    fun accept(presentation: EditableTextPresentation) {
        exact = presentation
        estimate = presentation
    }

    fun acceptEstimate(presentation: EditableTextPresentation) {
        estimate = presentation
    }

    /** Inlays already computed for [text] under the same [inlayHintsProvider]/[inlayRevision]. Null if nothing matches. */
    fun reusableInlays(
        text: String,
        inlayHintsProvider: UiInlayHintsProvider?,
        inlayRevision: Long,
    ): List<UiInlayHint>? {
        if (!exact.hasText) return null
        if (exact.textHash != text.hashCode() || exact.textLength != text.length) return null
        if (exact.inlayHintsProvider !== inlayHintsProvider || exact.inlayRevision != inlayRevision) return null
        return exact.inlayHints
    }
}

internal data class EditableTextPresentation(
    val text: String,
    val textHash: Int,
    val textLength: Int,
    val caret: Int,
    val highlighter: UiSyntaxHighlighter?,
    val inlayHintsProvider: UiInlayHintsProvider?,
    val inlayRevision: Long,
    val highlights: List<UiTextHighlight>,
    val inlayHints: List<UiInlayHint>,
) {
    val hasText: Boolean get() = textLength >= 0

    fun matches(
        text: String,
        caret: Int,
        highlighter: UiSyntaxHighlighter?,
        inlayHintsProvider: UiInlayHintsProvider?,
        inlayRevision: Long,
    ): Boolean {
        return textHash == text.hashCode() && textLength == text.length && this.caret == normalizePresentationCaret(
            caret, text.length
        ) && this.highlighter === highlighter && this.inlayHintsProvider === inlayHintsProvider && this.inlayRevision == inlayRevision
    }

    fun matchesAnalysis(
        text: String,
        highlighter: UiSyntaxHighlighter?,
        inlayHintsProvider: UiInlayHintsProvider?,
        inlayRevision: Long,
    ): Boolean {
        return textHash == text.hashCode() && textLength == text.length && this.highlighter === highlighter && this.inlayHintsProvider === inlayHintsProvider && this.inlayRevision == inlayRevision
    }

    fun withCaret(nextCaret: Int): EditableTextPresentation {
        val safeCaret = normalizePresentationCaret(nextCaret, textLength.coerceAtLeast(0))
        return if (caret == safeCaret) this else copy(caret = safeCaret)
    }

    fun shiftedTo(
        nextText: String,
        nextCaret: Int,
        nextHighlighter: UiSyntaxHighlighter?,
        staticInlayHints: List<UiInlayHint>,
        nextInlayHintsProvider: UiInlayHintsProvider?,
        nextInlayRevision: Long,
    ): EditableTextPresentation {
        if (!hasText || highlighter !== nextHighlighter || inlayHintsProvider !== nextInlayHintsProvider) {
            return exact(
                text = nextText,
                caret = nextCaret,
                highlighter = nextHighlighter,
                inlayHintsProvider = nextInlayHintsProvider,
                inlayRevision = nextInlayRevision,
                highlights = emptyList(),
                inlayHints = if (nextInlayHintsProvider == null) staticInlayHints else emptyList(),
            )
        }
        val shiftedInlays = when {
            nextInlayHintsProvider != null || nextHighlighter is UiDeferredTextAnalyzer -> shiftTextInlayHintsThroughEdit(
                text, nextText, inlayHints
            )

            else -> staticInlayHints
        }
        return exact(
            text = nextText,
            caret = nextCaret,
            highlighter = nextHighlighter,
            inlayHintsProvider = nextInlayHintsProvider,
            inlayRevision = nextInlayRevision,
            highlights = shiftTextHighlightsThroughEdit(text, nextText, highlights),
            inlayHints = shiftedInlays,
        )
    }

    companion object {
        val Empty = EditableTextPresentation("", 0, -1, 0, null, null, 0L, emptyList(), emptyList())

        fun exact(
            text: String,
            caret: Int,
            highlighter: UiSyntaxHighlighter?,
            inlayHintsProvider: UiInlayHintsProvider?,
            inlayRevision: Long,
            highlights: List<UiTextHighlight>,
            inlayHints: List<UiInlayHint>,
        ): EditableTextPresentation {
            return EditableTextPresentation(
                text = text,
                textHash = text.hashCode(),
                textLength = text.length,
                caret = normalizePresentationCaret(caret, text.length),
                highlighter = highlighter,
                inlayHintsProvider = inlayHintsProvider,
                inlayRevision = inlayRevision,
                highlights = sanitizeTextHighlights(text.length, highlights),
                inlayHints = sanitizeInlayHints(text.length, inlayHints),
            )
        }
    }
}

internal fun normalizePresentationCaret(caret: Int, textLength: Int): Int {
    return if (caret == UiNoCaretOffset) UiNoCaretOffset else caret.coerceIn(0, textLength)
}
