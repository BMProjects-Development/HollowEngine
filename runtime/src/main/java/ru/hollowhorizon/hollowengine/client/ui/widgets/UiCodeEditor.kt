package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.DefaultUiFontSize
import ru.hollowhorizon.hollowengine.client.ui.text.Shadow

/**
 * The editor of a code file: a multi-caret field with line numbers, folds, markers and indent guides, which indents
 * and pairs delimiters as it is typed in.
 *
 * @param syntax the language of the code, which typing reads blocks and literals by; null edits it as plain text.
 */
@Composable
fun UiCodeEditor(
    value: String,
    onChange: (String) -> Unit,
    insight: UiCodeInsight = UiCodeInsight(),
    view: UiCodeView = UiCodeView(),
    search: UiTextSearch? = null,
    syntax: UiCodeSyntax? = null,
    readOnly: Boolean = false,
    fontSize: Float = DefaultUiFontSize,
    fontFamily: String? = UiCodeFontFamily,
    textShadow: Shadow? = null,
    state: TextFieldState? = null,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier = Modifier.size(100.percent, 100.percent),
    attributes: Map<String, String> = emptyMap(),
) {
    TextField(
        value = value,
        onChange = onChange,
        mode = UiTextFieldMode.MULTI_LINE,
        readOnly = readOnly,
        editing = UiCodeEditing(syntax = syntax),
        insight = insight,
        view = view.copy(
            lineNumberColor = view.lineNumberColor ?: CodeEditorLineNumberColor,
            indentGuideColor = view.indentGuideColor ?: CodeEditorIndentGuideColor,
        ),
        search = search,
        wrap = false,
        fontSize = fontSize,
        fontFamily = fontFamily,
        textShadow = textShadow,
        caretColor = CodeEditorCaretColor,
        selectionColor = CodeEditorSelectionColor,
        state = state,
        id = id,
        tags = listOf("code-editor") + tags,
        modifier = modifier,
        attributes = attributes,
    )
}

/** The font of code and logs; the rest of the interface keeps the game's own. */
const val UiCodeFontFamily = "hollowengine:fonts/jetbrains_mono_nl.ttf"

private val CodeEditorCaretColor = UiColor(0.941f, 0.965f, 1f, 1f)
private val CodeEditorSelectionColor = UiColor(0.373f, 0.549f, 0.804f, 0.46f)
private val CodeEditorLineNumberColor = UiColor(0.333f, 0.376f, 0.439f, 1f)
private val CodeEditorIndentGuideColor = UiColor(0.408f, 0.459f, 0.533f, 0.22f)
