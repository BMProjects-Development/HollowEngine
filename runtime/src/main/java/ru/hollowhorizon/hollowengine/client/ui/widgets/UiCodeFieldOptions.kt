package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold

/**
 * How typing reshapes the text of a field that holds code.
 *
 * @param indentSize the indent step in spaces.
 * @param autoPairs whether an opening delimiter or quote adds its closing one.
 * @param syntax the language typing reads blocks, call chains and literals by; null edits plain text.
 */
data class UiCodeEditing(
    val indentSize: Int = 4,
    val autoPairs: Boolean = true,
    val multiCaret: Boolean = true,
    val syntax: UiCodeSyntax? = null,
)

/**
 * What a field offers on top of its text: highlighting, completion, signatures and hover, problems and inlay hints.
 *
 * @param completionRevision changes when completions change while the text does not.
 * @param revision the same for signatures and hover.
 * @param inlayRevision the same for the hints of [inlayHintsProvider].
 */
data class UiCodeInsight(
    val highlighter: UiSyntaxHighlighter? = null,
    val completions: UiCompletionContributor? = null,
    val signatureHelp: UiSignatureHelpProvider? = null,
    val hover: UiHoverInfoProvider? = null,
    val diagnostics: List<UiTextDiagnostic> = emptyList(),
    val inlayHints: List<UiInlayHint> = emptyList(),
    val inlayHintsProvider: UiInlayHintsProvider? = null,
    val onInlayAction: ((UiInlayAction) -> Unit)? = null,
    val completionRevision: Long = 0L,
    val revision: Long = completionRevision,
    val inlayRevision: Long = 0L,
)

/**
 * How a field shows lines of code: the gutter with line numbers, folds and markers, and indent guides. A null color
 * is the one the field draws with by default.
 */
data class UiCodeView(
    val folds: List<CodeFold> = emptyList(),
    val markers: List<UiGutterMarker> = emptyList(),
    val lineNumberColor: UiColor? = null,
    val indentGuides: Boolean = true,
    val indentGuideColor: UiColor? = null,
)

/** Search hits to mark in the text, and the one the search is on. */
data class UiTextSearch(
    val matches: List<IntRange> = emptyList(),
    val active: IntRange? = null,
)
