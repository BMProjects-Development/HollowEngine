package ru.hollowhorizon.hollowengine.client.ui.ide

import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.ide.files.EditorLanguageService
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeLanguageService
import ru.hollowhorizon.hollowengine.client.ui.ide.files.PlainEditorLanguageService
import ru.hollowhorizon.hollowengine.client.ui.ide.files.pathExtension
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonExtension
import ru.hollowhorizon.hollowengine.common.events.client.IdeCompletionEvent
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

internal class HollowIdeEditorSession(
    private val path: String,
    private val onUpdated: () -> Unit,
) {
    private val languageService = languageServiceForPath(path)
    private val analysisDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HollowEngine-ScriptingAnalysis-${path.substringAfterLast('/')}").apply {
            isDaemon = true
            priority = (Thread.NORM_PRIORITY - 2).coerceAtLeast(Thread.MIN_PRIORITY)
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + analysisDispatcher)
    private val analysisRevision = AtomicLong()
    private val completionRevision = AtomicLong()
    @Volatile
    private var requestedAnalysis: AnalysisKey? = null
    @Volatile
    private var requestedCompletion: CompletionKey? = null
    @Volatile
    private var requestedOccurrence: OccurrenceKey? = null
    @Volatile
    private var analysisJob: Job? = null
    @Volatile
    private var completionJob: Job? = null
    @Volatile
    private var definitionJob: Job? = null
    @Volatile
    private var formatJob: Job? = null
    @Volatile
    private var occurrenceJob: Job? = null
    @Volatile
    private var snapshot = EditorAnalysisSnapshot.Empty
    @Volatile
    private var completionSnapshot = CompletionSnapshot.Empty
    @Volatile
    private var occurrenceSnapshot = OccurrenceSnapshot.Empty
    private val publishedRevision = AtomicLong()
    private val codeInsight = HollowIdeCodeInsightSession(
        path = path,
        scope = scope,
        currentAnalyzer = ::currentAnalyzer,
        publish = ::publishCodeInsight,
        reportFailure = ::reportAnalysisFailure,
    )

    val revision: Long get() = publishedRevision.get()

    private val textAnalyzer = object : UiDeferredTextAnalyzer {
        override fun highlight(text: String, caret: Int): List<UiTextHighlight> {
            return analysisFromSnapshot(text, caret, exactOnly = false)?.highlights.orEmpty()
        }

        override fun cachedAnalysis(text: String, caret: Int): UiTextAnalysis? {
            return analysisFromSnapshot(text, caret, exactOnly = true)
        }

        override fun deferredAnalysis(text: String, caret: Int): UiTextAnalysis? {
            return analysisFromSnapshot(text, caret, exactOnly = false)
        }

        override fun hints(text: String): List<UiInlayHint> = inlayHints(text)

        private fun analysisFromSnapshot(text: String, caret: Int, exactOnly: Boolean): UiTextAnalysis? {
            val analyzer = currentAnalyzer()
            val current = snapshot
            return when {
                current.matchesText(text, analyzer) -> UiTextAnalysis(
                    highlights = withOccurrences(text, caret, analyzer, current.highlights),
                    inlayHints = current.inlayHints,
                    exact = true,
                )
                current.hasText -> {
                    requestAnalysis(text, analyzer)
                    if (exactOnly) {
                        null
                    } else {
                        UiTextAnalysis(
                            highlights = shiftTextHighlightsThroughEdit(current.text, text, current.highlights),
                            inlayHints = current.inlayHintsForEditedText(text),
                            exact = false,
                        )
                    }
                }
                else -> {
                    requestAnalysis(text, analyzer)
                    null
                }
            }
        }

        private fun withOccurrences(
            text: String,
            caret: Int,
            analyzer: ScriptingAnalyzer,
            base: List<UiTextHighlight>,
        ): List<UiTextHighlight> {
            if (caret == UiNoCaretOffset) return base
            val current = occurrenceSnapshot
            if (current.matches(text, caret, analyzer)) {
                return overlayOccurrences(text.length, base, current.ranges)
            }
            requestOccurrences(text, caret, analyzer)
            return base
        }
    }
    val highlighter: UiCaretAwareSyntaxHighlighter = textAnalyzer

    val completions: UiCompletionContributor = UiCompletionContributor { context ->
        val analyzer = currentAnalyzer()
        requestCompletions(context.text, context.caret, analyzer)
        val items = completionSnapshot.takeIf { it.matches(context.text, context.caret, analyzer) }?.items.orEmpty()
        val candidates = (items + ideContextCompletions(path, context)).toMutableList()
        IdeCompletionEvent.post(IdeCompletionEvent(path, context.text, context.caret, candidates)).items.toList()
    }
    val signatures: UiSignatureHelpProvider get() = codeInsight.signatures
    val hover: UiHoverInfoProvider get() = codeInsight.hover
    private var intentionJob: Job? = null
    val intentions = UiIntentionActionsProvider { context, publish ->
        intentionJob?.cancel()
        intentionJob = scope.launch {
            val actions = runCatching {
                currentAnalyzer().intentionActions(path, context.text, context.caret)
            }.getOrElse { failure ->
                reportAnalysisFailure("intentions", failure)
                emptyList()
            }.map { fix ->
                UiTextQuickFix(
                    fix.title.lang(*fix.titleArgs.toTypedArray()),
                    fix.edits.map { UiTextEdit(it.start, it.end, it.replacement) },
                )
            }
            ensureActive()
            Minecraft.getInstance().execute {
                if (scope.isActive) {
                    publish(actions)
                    onUpdated()
                }
            }
        }
    }

    fun inlayHints(text: String): List<UiInlayHint> {
        val analyzer = currentAnalyzer()
        val current = snapshot
        if (!current.matchesText(text, analyzer)) {
            requestAnalysis(text, analyzer)
        }
        return when {
            current.matchesText(text, analyzer) -> current.inlayHints
            current.hasText -> current.inlayHintsForEditedText(text)
            else -> emptyList()
        }
    }

    fun diagnostics(text: String): List<UiTextDiagnostic> {
        val analyzer = currentAnalyzer()
        val current = snapshot
        if (!current.matchesText(text, analyzer)) {
            requestAnalysis(text, analyzer)
        }
        return when {
            current.matchesText(text, analyzer) -> current.diagnostics
            current.hasText -> current.diagnosticsForEditedText(text)
            else -> emptyList()
        }
    }

    val canFormat: Boolean get() = currentAnalyzer().canFormat

    /**
     * Reformats off the render thread and hands the result back on it. `null` means the language
     * has no formatter or the text was already formatted.
     */
    fun format(text: String, onFormatted: (String?) -> Unit) {
        val analyzer = currentAnalyzer()
        formatJob?.cancel()
        formatJob = scope.launch {
            val formatted = runCatching { analyzer.format(path, text) }.getOrElse { failure ->
                reportAnalysisFailure("format", failure)
                null
            }
            Minecraft.getInstance().execute {
                onFormatted(formatted)
            }
        }
    }

    fun resolveDefinition(text: String, caret: Int, onResolved: (DefinitionLocation?) -> Unit) {
        val analyzer = currentAnalyzer()
        val safeCaret = caret.coerceIn(0, text.length)
        definitionJob?.cancel()
        definitionJob = scope.launch {
            val definition = runCatching {
                analyzer.definition(path, text, safeCaret)
            }.getOrNull()
            Minecraft.getInstance().execute {
                onResolved(definition)
            }
        }
    }

    fun requestAnalysis(text: String, caret: Int) {
        requestAnalysis(text, currentAnalyzer())
    }

    fun close() {
        scope.cancel()
        analysisDispatcher.close()
    }

    private fun requestAnalysis(text: String, analyzer: ScriptingAnalyzer) {
        val key = AnalysisKey(text.hashCode(), text.length, analyzer)
        if (requestedAnalysis == key) return
        requestedAnalysis = key
        val requestRevision = analysisRevision.incrementAndGet()
        analysisJob?.cancel()
        analysisJob = scope.launch {
            delay(EditorAnalysisDebounceMillis.milliseconds)
            ensureActive()
            val lineStarts = lineStarts(text)
            val lines = runCatching { analyzer.highlight(path, text, UiNoCaretOffset) }.getOrElse { failure ->
                reportAnalysisFailure("highlight", failure)
                UnavailableKotlinScriptingAnalyzer.highlight(path, text, UiNoCaretOffset)
            }
            val diagnostics = runCatching {
                analyzer.diagnostic(path, text).map { diagnostic -> diagnostic.toUi(text, lineStarts) }
            }.getOrElse { failure ->
                reportAnalysisFailure("diagnostic", failure)
                emptyList()
            }
            val next = EditorAnalysisSnapshot(
                text = text,
                textHash = key.textHash,
                textLength = key.textLength,
                analyzer = analyzer,
                highlights = lines.toHighlights(text, lineStarts),
                inlayHints = lines.toInlayHints(lineStarts, text.length),
                diagnostics = diagnostics,
            )
            publishAnalysisIfCurrent(requestRevision) {
                snapshot = next
            }
        }
    }

    private fun requestOccurrences(text: String, caret: Int, analyzer: ScriptingAnalyzer) {
        val key = OccurrenceKey(text.hashCode(), text.length, caret.coerceIn(0, text.length), analyzer)
        if (requestedOccurrence == key) return
        requestedOccurrence = key
        occurrenceJob?.cancel()
        occurrenceJob = scope.launch {
            val ranges = runCatching { analyzer.occurrences(path, text, key.caret) }.getOrElse { failure ->
                reportAnalysisFailure("occurrences", failure)
                emptyList()
            }
            ensureActive()
            if (requestedOccurrence != key) return@launch
            occurrenceSnapshot = OccurrenceSnapshot(key.textHash, key.textLength, key.caret, analyzer, ranges)
            publishedRevision.incrementAndGet()
            Minecraft.getInstance().execute(onUpdated)
        }
    }

    private fun requestCompletions(text: String, caret: Int, analyzer: ScriptingAnalyzer) {
        val key = CompletionKey(text.hashCode(), text.length, caret.coerceIn(0, text.length), analyzer)
        if (requestedCompletion == key) return
        requestedCompletion = key
        val requestRevision = completionRevision.incrementAndGet()
        completionJob?.cancel()
        completionJob = scope.launch {
            val collected = ArrayList<UiTextCompletion>()
            var publishedAt = 0L
            var publishedSize = -1

            fun publish() {
                publishedAt = System.nanoTime()
                publishedSize = collected.size
                val batch = collected.toList()
                publishCompletionIfCurrent(requestRevision) {
                    completionSnapshot = CompletionSnapshot(key.textHash, key.textLength, key.caret, analyzer, batch)
                }
            }

            runCatching {
                analyzer.completions(path, text, key.caret, CompletionSink { items ->
                    if (requestRevision < completionRevision.get() || !isActive) return@CompletionSink false
                    items.mapTo(collected, CompletionItem::toUi)
                    if (System.nanoTime() - publishedAt >= CompletionPublishIntervalNanos) publish()
                    true
                })
            }.onFailure { failure ->
                reportAnalysisFailure("completions", failure)
            }
            if (publishedSize != collected.size) publish()
        }
    }

    private fun publishAnalysisIfCurrent(requestRevision: Long, update: () -> Unit) {
        if (requestRevision < analysisRevision.get()) return
        update()
        publishedRevision.incrementAndGet()
        Minecraft.getInstance().execute(onUpdated)
    }

    private fun publishCompletionIfCurrent(requestRevision: Long, update: () -> Unit) {
        if (requestRevision < completionRevision.get()) return
        update()
        publishedRevision.incrementAndGet()
        Minecraft.getInstance().execute(onUpdated)
    }

    private fun publishCodeInsight() {
        publishedRevision.incrementAndGet()
        Minecraft.getInstance().execute(onUpdated)
    }

    private fun reportAnalysisFailure(stage: String, failure: Throwable) {
        if (failure is CancellationException) return
        val key = "$stage:${failure::class.qualifiedName}:${failure.message}"
        if (!reportedFailures.add(key)) return
        HollowEngine.LOGGER.error("Scripting analysis ({}) failed for '{}'", stage, path, failure)
    }

    private val reportedFailures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun currentAnalyzer(): ScriptingAnalyzer {
        return languageService.analyzer
    }
}

private const val EditorAnalysisDebounceMillis = 180L
private const val CompletionPublishIntervalNanos = 60_000_000L

private data class AnalysisKey(
    val textHash: Int,
    val textLength: Int,
    val analyzer: ScriptingAnalyzer,
)

private data class OccurrenceKey(
    val textHash: Int,
    val textLength: Int,
    val caret: Int,
    val analyzer: ScriptingAnalyzer,
)

private data class OccurrenceSnapshot(
    val textHash: Int,
    val textLength: Int,
    val caret: Int,
    val analyzer: ScriptingAnalyzer,
    val ranges: List<OccurrenceRange>,
) {
    fun matches(text: String, offset: Int, analyzer: ScriptingAnalyzer): Boolean {
        return textHash == text.hashCode() &&
                textLength == text.length &&
                caret == offset.coerceIn(0, text.length) &&
                this.analyzer === analyzer
    }

    companion object {
        val Empty = OccurrenceSnapshot(0, -1, 0, UnavailableKotlinScriptingAnalyzer, emptyList())
    }
}

private data class CompletionKey(
    val textHash: Int,
    val textLength: Int,
    val caret: Int,
    val analyzer: ScriptingAnalyzer,
)

private data class CompletionSnapshot(
    val textHash: Int,
    val textLength: Int,
    val caret: Int,
    val analyzer: ScriptingAnalyzer,
    val items: List<UiTextCompletion>,
) {
    fun matches(text: String, offset: Int, analyzer: ScriptingAnalyzer): Boolean {
        return textHash == text.hashCode() &&
                textLength == text.length &&
                caret == offset.coerceIn(0, text.length) &&
                this.analyzer === analyzer
    }

    companion object {
        val Empty = CompletionSnapshot(0, -1, 0, UnavailableKotlinScriptingAnalyzer, emptyList())
    }
}

private data class EditorAnalysisSnapshot(
    val text: String,
    val textHash: Int,
    val textLength: Int,
    val analyzer: ScriptingAnalyzer,
    val highlights: List<UiTextHighlight>,
    val inlayHints: List<UiInlayHint>,
    val diagnostics: List<UiTextDiagnostic>,
) {
    fun matchesText(text: String, analyzer: ScriptingAnalyzer): Boolean {
        return textHash == text.hashCode() && textLength == text.length && this.analyzer === analyzer
    }

    val hasText: Boolean get() = textLength >= 0

    fun inlayHintsForEditedText(editedText: String): List<UiInlayHint> {
        if (!hasText) return emptyList()
        return shiftTextInlayHintsThroughEdit(text, editedText, inlayHints)
    }

    fun diagnosticsForEditedText(editedText: String): List<UiTextDiagnostic> {
        if (!hasText) return emptyList()
        return shiftDiagnosticsForEditedText(text, editedText, diagnostics)
    }

    companion object {
        val Empty = EditorAnalysisSnapshot(
            "",
            0,
            -1,
            UnavailableKotlinScriptingAnalyzer,
            emptyList(),
            emptyList(),
            emptyList(),
        )
    }
}

/**
 * Moves a caret through a reformat, keeping it on the very character it was on.
 */
internal fun mapCaretThroughFormat(original: String, formatted: String, caret: Int): Int {
    val position = caret.coerceIn(0, original.length)

    fun after(index: Int) = (codeCharOffset(formatted, codeCharsBefore(original, index)) + 1)
        .coerceIn(0, formatted.length)

    fun before(index: Int) = codeCharOffset(formatted, codeCharsBefore(original, index))
        .coerceIn(0, formatted.length)

    if (position > 0 && !original[position - 1].isWhitespace()) return after(position - 1)

    val lineEnd = original.indexOf('\n', position).let { if (it < 0) original.length else it }
    for (index in position until lineEnd) {
        if (original[index].isWhitespace()) continue
        return before(index)
    }

    val lineStart = original.lastIndexOf('\n', (position - 1).coerceAtLeast(0))
        .let { if (it < 0 || position == 0) 0 else it + 1 }
    for (index in position - 1 downTo lineStart) {
        if (original[index].isWhitespace()) continue
        return after(index)
    }

    val line = original.take(position).count { it == '\n' }
    return lineStartOffset(formatted.split('\n'), line).coerceIn(0, formatted.length)
}

private fun codeCharsBefore(text: String, offset: Int): Int {
    var count = 0
    for (index in 0 until offset) {
        if (!text[index].isWhitespace()) count++
    }
    return count
}

private fun codeCharOffset(text: String, index: Int): Int {
    var seen = 0
    for (offset in text.indices) {
        if (text[offset].isWhitespace()) continue
        if (seen == index) return offset
        seen++
    }
    return text.length
}

private fun lineStartOffset(lines: List<String>, lineIndex: Int): Int {
    var offset = 0
    for (index in 0 until lineIndex) offset += lines[index].length + 1
    return offset
}

internal fun overlayOccurrences(
    textLength: Int,
    highlights: List<UiTextHighlight>,
    ranges: List<OccurrenceRange>,
): List<UiTextHighlight> {
    val clean = ranges.mapNotNull { range ->
        val start = range.start.coerceIn(0, textLength)
        val end = range.end.coerceIn(start, textLength)
        if (start == end) null else OccurrenceRange(start, end)
    }.sortedBy { it.start }
    if (clean.isEmpty()) return highlights

    val result = ArrayList<UiTextHighlight>(highlights.size + clean.size * 2)
    for (highlight in highlights) {
        var cursor = highlight.start
        for (range in clean) {
            if (range.end <= cursor || range.start >= highlight.end) continue
            val start = maxOf(cursor, range.start)
            val end = minOf(highlight.end, range.end)
            if (cursor < start) result += highlight.copy(start = cursor, end = start)
            result += UiTextHighlight(start, end, highlight.style.withBackground(occurrenceBackground(highlight.style)))
            cursor = end
        }
        if (cursor < highlight.end) result += highlight.copy(start = cursor, end = highlight.end)
    }
    // Parts of ranges not covered by any highlight still need a background span.
    for (range in clean) {
        var cursor = range.start
        for (highlight in highlights) {
            if (highlight.end <= cursor) continue
            if (highlight.start >= range.end) break
            if (highlight.start > cursor) {
                result += UiTextHighlight(cursor, highlight.start, OccurrenceFallbackStyle)
            }
            cursor = maxOf(cursor, highlight.end)
            if (cursor >= range.end) break
        }
        if (cursor < range.end) result += UiTextHighlight(cursor, range.end, OccurrenceFallbackStyle)
    }
    result.sortWith(compareBy<UiTextHighlight> { it.start }.thenBy { it.end })
    return result
}

private fun occurrenceBackground(style: UiInlineStyle): UiColor {
    return style.color?.copy(alpha = OccurrenceBackgroundAlpha) ?: OccurrenceFallbackBackground
}

private const val OccurrenceBackgroundAlpha = 0.24f
private val OccurrenceFallbackBackground = UiColor(0.66f, 0.72f, 0.78f, OccurrenceBackgroundAlpha)
private val OccurrenceFallbackStyle = UiInlineStyle().withBackground(OccurrenceFallbackBackground)

internal fun shiftDiagnosticsForEditedText(
    originalText: String,
    editedText: String,
    diagnostics: List<UiTextDiagnostic>,
): List<UiTextDiagnostic> {
    if (diagnostics.isEmpty()) return emptyList()
    val commonPrefix = commonPrefixLength(originalText, editedText)
    val commonSuffix = commonSuffixLength(originalText, editedText, commonPrefix)
    val oldChangedEnd = originalText.length - commonSuffix
    val newChangedEnd = editedText.length - commonSuffix
    val delta = editedText.length - originalText.length
    val starts = lineStarts(editedText)

    fun mapOffset(offset: Int): Int {
        return when {
            offset < commonPrefix -> offset
            offset >= oldChangedEnd -> offset + delta
            else -> newChangedEnd
        }.coerceIn(0, editedText.length)
    }

    return diagnostics.map { diagnostic ->
        val start = mapOffset(diagnostic.start)
        val end = mapOffset(diagnostic.end).coerceAtLeast(start)
        val line = starts.binarySearch(start).let { index ->
            if (index >= 0) index else (-index - 2).coerceAtLeast(0)
        }
        diagnostic.copy(
            start = start,
            end = end,
            line = line + 1,
            column = start - starts.getOrElse(line) { 0 } + 1,
            fixes = emptyList(),
        )
    }
}

internal fun languageServiceForPath(path: String): EditorLanguageService {
    val contributed = HollowIdeExtensionPoints.LANGUAGES.registrations.firstOrNull { extension ->
        runCatching { extension.invoke { language -> language.matches(path) } }
            .onFailure { failure ->
                HollowEngine.LOGGER.error("IDE language extension '{}' failed while matching '{}'", extension.qualifiedId, path, failure)
            }
            .getOrDefault(false)
    }
    if (contributed != null) return ExtensionEditorLanguageService(contributed)
    return runCatching { EditorLanguageService(pathExtension(path)) }.getOrNull() ?: PlainEditorLanguageService
}

private class ExtensionEditorLanguageService(
    private val extension: HollowAddonExtension<HollowIdeLanguageService>,
) : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = extension.invoke(HollowIdeLanguageService::analyzer)
}

private fun List<TextLine>.toHighlights(text: String, lineStarts: List<Int>): List<UiTextHighlight> {
    return flatMapIndexed { lineIndex, line ->
        val lineStart = lineStarts.getOrElse(lineIndex) { text.length }
        var cursor = lineStart
        line.spans.mapNotNull { (segment, style) ->
            val start = cursor
            val end = (start + segment.length).coerceAtMost(text.length)
            cursor = end
            if (start == end) null else UiTextHighlight(start, end, style.toUi())
        }
    }
}

private fun List<TextLine>.toInlayHints(lineStarts: List<Int>, textLength: Int): List<UiInlayHint> {
    return flatMapIndexed { lineIndex, line ->
        val lineStart = lineStarts.getOrElse(lineIndex) { textLength }
        line.hints.map { hint ->
            UiInlayHint(
                offset = (lineStart + hint.index).coerceIn(0, textLength),
                content = hint.content.map { it.toUi() },
                tags = hint.tags,
                action = hint.action?.let { action ->
                    UiInlayAction(action.id, (action as? InlayAction.PickColor)?.let { it.start until it.end })
                },
            )
        }
    }
}

private fun InlayContent.toUi(): UiInlayContent = when (this) {
    is InlayContent.Label -> UiInlayContent.Label(text)
    is InlayContent.Icon -> UiInlayContent.Icon(source)
    is InlayContent.Swatch -> UiInlayContent.Swatch(argb)
}

private fun CompletionItem.toUi(): UiTextCompletion {
    val declaration = this as? CompletionItem.Declaration
    return UiTextCompletion(
        label = show,
        insertText = insert,
        detail = declaration?.middle.orEmpty(),
        tail = declaration?.tail.orEmpty(),
        icon = tag.completionIcon(),
        itemIcon = declaration?.itemIcon,
        caretOffset = (insert.length + moveCaret).coerceIn(0, insert.length),
        importFqName = declaration?.fqName?.takeIf { declaration.import },
        wordChars = wordChars,
        filterText = name,
        closeness = closeness,
    )
}

private fun CompletionItemTag.completionIcon(): String {
    val name = when (this) {
        CompletionItemTag.FUNCTION -> "method"
        CompletionItemTag.PROPERTY,
        CompletionItemTag.LOCAL_VARIABLE -> "variable"
        CompletionItemTag.CLASS -> "class"
        CompletionItemTag.KEYWORD -> "package"
    }
    return "hollowengine:textures/gui/icons/autocomplete_$name.svg"
}

private fun Diagnostic.toUi(text: String, lineStarts: List<Int>): UiTextDiagnostic {
    val start = offsetAt(lineStarts, range.start.line, range.start.column, text.length)
    val end = offsetAt(lineStarts, range.end.line, range.end.column, text.length).coerceAtLeast(start)
    return UiTextDiagnostic(
        start = start,
        end = end,
        message = message,
        severity = severity.toUi(),
        line = range.start.line + 1,
        column = range.start.column + 1,
        fixes = fixes.map { fix ->
            UiTextQuickFix(
                title = fix.title.lang(*fix.titleArgs.toTypedArray()),
                edits = fix.edits.map { UiTextEdit(it.start, it.end, it.replacement) },
            )
        },
    )
}

private fun Severity.toUi(): UiTextDiagnosticSeverity {
    return when (this) {
        Severity.ERROR,
        Severity.FATAL -> UiTextDiagnosticSeverity.ERROR

        Severity.WARNING -> UiTextDiagnosticSeverity.WARNING
        Severity.DEBUG,
        Severity.INFO -> UiTextDiagnosticSeverity.INFO
    }
}

private fun SpanStyle.toUi(): UiInlineStyle {
    val resolved = colorOverride?.let(UiColor::fromArgb) ?: color.toUiColor()
    var style = UiInlineStyle().withColor(resolved)
    if (highlight) style = style.withBackground(resolved.copy(alpha = 0.24f))
    if (bold) style = style.withBold()
    if (italic) style = style.withItalic()
    return style
}

private fun commonPrefixLength(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    for (index in 0 until limit) {
        if (left[index] != right[index]) return index
    }
    return limit
}

private fun commonSuffixLength(left: String, right: String, prefixLength: Int): Int {
    val limit = minOf(left.length, right.length) - prefixLength
    for (offset in 0 until limit) {
        if (left[left.lastIndex - offset] != right[right.lastIndex - offset]) return offset
    }
    return limit
}

internal fun TokenType.toUiColor(): UiColor = toKool().let { color ->
    UiColor(color.r, color.g, color.b, color.a)
}

private fun lineStarts(text: String): List<Int> {
    val starts = mutableListOf(0)
    text.forEachIndexed { index, char ->
        if (char == '\n') starts += index + 1
    }
    return starts
}

private fun offsetAt(lineStarts: List<Int>, line: Int, column: Int, textLength: Int): Int {
    val lineStart = lineStarts.getOrElse(line.coerceAtLeast(0)) { textLength }
    return (lineStart + column.coerceAtLeast(0)).coerceIn(0, textLength)
}
