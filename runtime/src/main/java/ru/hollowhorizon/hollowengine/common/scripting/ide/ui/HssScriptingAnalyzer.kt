package ru.hollowhorizon.hollowengine.common.scripting.ide.ui

import ru.hollowhorizon.hollowengine.common.scripting.ide.*

/**
 * IDE support for HSS. Highlighting, completion, hints and diagnostics all read the same
 * two sources of truth: the scanner in [HssLexer] and the property schema behind
 * [UiLanguageCatalog], so the editor never disagrees with the compiler.
 */
object HssScriptingAnalyzer : ScriptingAnalyzer {
    override fun highlight(name: String, text: String, offset: Int): List<TextLine> {
        val model = HssDocumentModel(text)
        val spans = HssLexer(text, model.keyframeNames).tokenize()
        return buildTextLines(text, spans, hssInlayHints(model))
    }

    override fun lightweightHighlightLine(name: String, line: String): TextLine {
        val spans = HssLexer(line, initialDepth = lineDepthGuess(line)).tokenize()
        return buildTextLines(line, spans, emptyList()).firstOrNull() ?: TextLine(emptyList(), ArrayList())
    }

    override fun completions(name: String, text: String, offset: Int, sink: CompletionSink) {
        sink.emit(hssCompletions(text, offset))
    }

    override fun diagnostic(name: String, text: String): List<Diagnostic> = hssDiagnostics(text)

    /**
     * Jumps from a resource location to the file it names, and from an animation name to
     * the `@keyframes` block that defines it.
     */
    override fun definition(name: String, text: String, offset: Int): DefinitionLocation? {
        val caret = offset.coerceIn(0, text.length)
        hssLocationAt(text, caret)?.let { location ->
            ResourceLocationTargets.definition(location)?.let { return it }
        }
        val word = wordAt(text, caret) ?: return null
        val keyframes = HssDocumentModel(text).keyframesNamed(word) ?: return null
        return DefinitionLocation(name, keyframes)
    }

    private fun wordAt(text: String, caret: Int): String? {
        var start = caret
        while (start > 0 && text[start - 1].isKeyframeNameChar()) start--
        var end = caret
        while (end < text.length && text[end].isKeyframeNameChar()) end++
        return text.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun Char.isKeyframeNameChar(): Boolean = isLetterOrDigit() || this == '-' || this == '_'

    /**
     * A standalone line has no block context, so a `property: value` shaped line is scanned
     * as a declaration and everything else as a selector.
     */
    private fun lineDepthGuess(line: String): Int {
        val trimmed = line.trim()
        val declarationShaped = ':' in trimmed && '{' !in trimmed && !trimmed.startsWith('@')
        return if (declarationShaped) 1 else 0
    }
}
