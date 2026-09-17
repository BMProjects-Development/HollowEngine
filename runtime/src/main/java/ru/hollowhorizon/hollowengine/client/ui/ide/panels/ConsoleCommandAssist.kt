package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import com.mojang.brigadier.ParseResults
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContextBuilder
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.tree.LiteralCommandNode
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.*

/**
 * Completions and parse errors for the console's command input, taken from the client's command tree
 * the same way the chat screen does.
 */
internal class ConsoleCommandAssist(private val onUpdated: () -> Unit) {
    @Volatile
    private var requested: Request? = null

    @Volatile
    private var completions = CompletionSnapshot(Request("", -1), emptyList())

    @Volatile
    private var diagnostics = DiagnosticSnapshot("", emptyList())

    @Volatile
    private var colours = HighlightSnapshot("", emptyList())

    /** Colors the parsed parts of the command; anything not parsed yet simply stays plain. */
    val highlighter: UiSyntaxHighlighter = UiSyntaxHighlighter { text ->
        colours.takeIf { it.text == text }?.items.orEmpty()
    }

    val contributor: UiCompletionContributor = object : UiCompletionContributor {
        override fun complete(context: UiCompletionContext): List<UiTextCompletion> {
            val request = Request(context.text, context.caret.coerceIn(0, context.text.length))
            request(request)
            return completions.takeIf { it.request == request }?.items.orEmpty()
        }

        override val triggerCharacters: String = TriggerCharacters
    }

    fun diagnostics(text: String): List<UiTextDiagnostic> {
        val current = diagnostics
        if (current.text == text) return current.items
        if (requested?.text != text) request(Request(text, text.length))
        return emptyList()
    }

    private fun request(request: Request) {
        if (requested == request) return
        requested = request
        val minecraft = Minecraft.getInstance()
        minecraft.execute { parse(minecraft, request) }
    }

    private fun parse(minecraft: Minecraft, request: Request) {
        if (requested != request) return
        val connection = minecraft.connection
        if (connection == null || request.text.isBlank()) {
            publishParse(request.text, emptyList(), emptyList())
            publishCompletions(request, emptyList())
            return
        }
        val reader = StringReader(request.text)
        if (reader.canRead() && reader.peek() == '/') reader.skip()
        val dispatcher = connection.commands
        val parse = dispatcher.parse(reader, connection.suggestionsProvider)
        val problems = if (parse.reader.canRead()) {
            listOfNotNull(Commands.getParseException(parse)).map { error ->
                val start = error.cursor.takeIf { it >= 0 } ?: parse.reader.cursor
                UiTextDiagnostic(
                    start = start.coerceIn(0, request.text.length),
                    end = tokenEnd(request.text, start),
                    message = error.rawMessage.string,
                    severity = UiTextDiagnosticSeverity.ERROR,
                )
            }
        } else {
            emptyList()
        }
        publishParse(request.text, problems, parse.highlights(request.text))
        dispatcher.getCompletionSuggestions(parse, request.caret).thenAccept { suggestions ->
            minecraft.execute {
                if (requested == request) publishCompletions(request, suggestions.toCompletions(request))
            }
        }
    }

    private fun publishCompletions(request: Request, items: List<UiTextCompletion>) {
        completions = CompletionSnapshot(request, items)
        onUpdated()
    }

    private fun publishParse(text: String, problems: List<UiTextDiagnostic>, highlights: List<UiTextHighlight>) {
        diagnostics = DiagnosticSnapshot(text, problems)
        colours = HighlightSnapshot(text, highlights)
        onUpdated()
    }

    private fun ParseResults<*>.highlights(text: String): List<UiTextHighlight> {
        val items = ArrayList<UiTextHighlight>()
        if (text.startsWith('/')) items += UiTextHighlight(0, 1, LiteralStyle)
        var context: CommandContextBuilder<*>? = this.context
        while (context != null) {
            context.nodes.forEach { parsed ->
                val start = parsed.range.start.coerceIn(0, text.length)
                val end = parsed.range.end.coerceIn(start, text.length)
                if (start == end) return@forEach
                items += UiTextHighlight(
                    start,
                    end,
                    if (parsed.node is LiteralCommandNode<*>) LiteralStyle else ArgumentStyle,
                )
            }
            context = context.child
        }
        return items
    }

    private fun Suggestions.toCompletions(request: Request): List<UiTextCompletion> {
        val text = request.text
        val wordStart = wordStart(text, request.caret)
        return list.map { suggestion -> suggestion.toCompletion(text, wordStart, request.caret) }
    }

    private fun Suggestion.toCompletion(text: String, wordStart: Int, caret: Int): UiTextCompletion {
        val rangeStart = range.start.coerceIn(0, caret)
        val typedBeforeWord = (wordStart - rangeStart).coerceAtLeast(0)
        val filter = if (typedBeforeWord > 0 && this.text.regionMatches(
                0,
                text,
                rangeStart,
                typedBeforeWord,
                ignoreCase = true
            )
        ) {
            this.text.substring(typedBeforeWord.coerceAtMost(this.text.length))
        } else {
            this.text
        }
        return UiTextCompletion(
            label = this.text,
            insertText = this.text,
            detail = tooltip?.string.orEmpty(),
            wordChars = text.substring(rangeStart, caret).filterNot { it.isLetterOrDigit() || it == '_' }.toSet()
                .joinToString(""),
            filterText = filter.ifEmpty { this.text },
        )
    }

    private data class Request(val text: String, val caret: Int)

    private class CompletionSnapshot(val request: Request, val items: List<UiTextCompletion>)

    private class DiagnosticSnapshot(val text: String, val items: List<UiTextDiagnostic>)

    private class HighlightSnapshot(val text: String, val items: List<UiTextHighlight>)

    private companion object {
        val LiteralStyle = UiInlineStyle().withColor(UiColor.fromArgb(0xFF82D99B.toInt()))
        val ArgumentStyle = UiInlineStyle().withColor(UiColor.fromArgb(0xFFDCBE8C.toInt()))

        const val TriggerCharacters = " :@=[,/#~^"

        fun wordStart(text: String, caret: Int): Int {
            var start = caret.coerceIn(0, text.length)
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
            return start
        }

        fun tokenEnd(text: String, start: Int): Int {
            val from = start.coerceIn(0, text.length)
            val space = text.indexOf(' ', from)
            return if (space < 0 || space == from) text.length else space
        }
    }
}
