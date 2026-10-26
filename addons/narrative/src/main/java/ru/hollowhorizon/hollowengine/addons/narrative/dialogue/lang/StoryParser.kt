package ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryBool
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryString

data class StoryParseResult(
    val cst: StoryFileCst,
    val diagnostics: StoryDiagnostics,
)

/**
 * Parses `.story` text into a lossless [StoryFileCst]. Parsing never throws: lines that cannot be
 * understood become [StoryLineKind.Broken] and a diagnostic, so the IDE can still show (and round-trip)
 * the file.
 */
class StoryParser(private val source: String) {
    private val diagnostics = StoryDiagnostics()

    companion object {
        /** A bare numeric argument, sign and duration suffix included: `-171`, `2.5`, `600ms`. */
        private val BARE_NUMBER = Regex("""-?\d+(?:\.\d+)?(?:ms|s|sec|min|h)?""")

        fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

        fun parse(source: String): StoryParseResult = StoryParser(source).run()
    }

    fun run(): StoryParseResult {
        val lines = mutableListOf<StoryLine>()
        var offset = 0
        var index = 0
        while (offset <= source.length) {
            val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
            val hasNewline = lineEnd < source.length
            val rawWithCr = source.substring(offset, lineEnd)
            val hasCr = rawWithCr.endsWith('\r')
            val raw = if (hasCr) rawWithCr.dropLast(1) else rawWithCr
            val eol = when {
                !hasNewline -> ""
                hasCr -> "\r\n"
                else -> "\n"
            }
            if (!hasNewline && raw.isEmpty() && lines.isNotEmpty()) break

            lines += parseLine(index, offset, raw, eol)
            index++
            if (!hasNewline) break
            offset = lineEnd + 1
        }
        return StoryParseResult(StoryFileCst(source, lines), diagnostics)
    }

    private fun parseLine(index: Int, offset: Int, raw: String, eol: String): StoryLine {
        val indentLength = raw.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) raw.length else it }
        val indent = raw.substring(0, indentLength)
        val commentStart = findCommentStart(raw, indentLength)
        val contentEndRaw = commentStart ?: raw.length
        var contentEnd = contentEndRaw
        while (contentEnd > indentLength && (raw[contentEnd - 1] == ' ' || raw[contentEnd - 1] == '\t')) contentEnd--

        val kind: StoryLineKind = if (indentLength >= contentEnd) {
            if (commentStart != null) StoryLineKind.CommentOnly else StoryLineKind.Blank
        } else {
            try {
                parseContent(index, offset, raw, indentLength, contentEnd)
            } catch (e: StoryParseException) {
                diagnostics.error(e.message ?: "Parse error", e.span)
                StoryLineKind.Broken(e.message ?: "Parse error")
            }
        }
        return StoryLine(index, offset, raw, eol, indent, kind, commentStart)
    }

    /**
     * Finds a `//` comment outside double quotes, either at the start of content or preceded by
     * whitespace. `\/` escapes a slash in text lines.
     */
    private fun findCommentStart(raw: String, from: Int): Int? {
        var inQuotes = false
        var i = from
        while (i < raw.length - 1) {
            when (val c = raw[i]) {
                '\\' -> i++ // skip escaped char
                '"' -> inQuotes = !inQuotes
                '/' -> if (!inQuotes && raw[i + 1] == '/' &&
                    (i == from || raw[i - 1] == ' ' || raw[i - 1] == '\t')
                ) return i

                else -> {}
            }
            i++
        }
        return null
    }

    private fun parseContent(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        return when (raw[start]) {
            '#' -> parseLabel(line, offset, raw, start, end)
            '@' -> parseCommand(line, offset, raw, start, end)
            else -> parseDialogue(line, offset, raw, start, end)
        }
    }

    private fun span(offset: Int, line: Int, from: Int, to: Int) = StorySpan(offset + from, offset + to, line)

    private fun parseLabel(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        var nameStart = start + 1
        while (nameStart < end && raw[nameStart] == ' ') nameStart++
        val name = raw.substring(nameStart, end)
        if (name.isEmpty()) {
            throw StoryParseException("Label name is empty", span(offset, line, start, end))
        }
        if (name.any { it == ' ' || it == '\t' }) {
            throw StoryParseException(
                "Label name must be a single word (jump targets cannot contain spaces)",
                span(offset, line, nameStart, end),
            )
        }
        return StoryLineKind.Label(name, span(offset, line, nameStart, end))
    }

    private fun parseCommand(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        var i = start + 1
        val nameStart = i
        while (i < end && isNameChar(raw[i])) i++
        val name = raw.substring(nameStart, i)
        if (name.isEmpty()) {
            throw StoryParseException("Expected command name after '@'", span(offset, line, start, start + 1))
        }
        val nameSpan = span(offset, line, nameStart, i)
        while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
        val tailStart = i

        fun expr(): StoryExpression {
            if (tailStart >= end) {
                throw StoryParseException("'@$name' expects a condition", nameSpan)
            }
            return StoryExpressions.parse(raw, offset, line, tailStart, end)
        }

        fun requireEmptyTail() {
            if (tailStart < end) {
                throw StoryParseException("'@$name' takes no arguments", span(offset, line, tailStart, end))
            }
        }

        return when (name) {
            "if" -> StoryLineKind.If(expr())
            "else-if", "elseif" -> StoryLineKind.ElseIf(expr())
            "else" -> {
                requireEmptyTail()
                StoryLineKind.Else
            }

            "while" -> StoryLineKind.While(expr())
            "set" -> parseSet(line, offset, raw, tailStart, end, nameSpan)
            "jump" -> StoryLineKind.Jump(parseRef(line, offset, raw, tailStart, end, name))
            "call" -> StoryLineKind.Call(parseRef(line, offset, raw, tailStart, end, name))
            "return" -> {
                requireEmptyTail()
                StoryLineKind.Return
            }

            "choice" -> parseChoice(line, offset, raw, tailStart, end)
            "async" -> parseAsync(line, offset, raw, tailStart, end)
            "await" -> parseAwait(line, offset, raw, tailStart, end)
            "cancel" -> parseCancel(line, offset, raw, tailStart, end, nameSpan)
            "sync" -> {
                requireEmptyTail()
                StoryLineKind.Sync
            }

            "checkpoint" -> {
                val (checkpoint, checkpointSpan) = parseName(line, offset, raw, tailStart, end, name)
                    ?: throw StoryParseException("'@checkpoint' expects a name", nameSpan)
                StoryLineKind.Checkpoint(checkpoint, checkpointSpan)
            }

            "rewind" -> parseName(line, offset, raw, tailStart, end, name)
                .let { StoryLineKind.Rewind(it?.first, it?.second) }

            "temporary" -> parseTemporary(line, offset, raw, tailStart, end)

            "timeout" -> {
                if (tailStart >= end) {
                    throw StoryParseException("'@timeout' expects a time like 6s", nameSpan)
                }
                val (duration, next) = parseArgValue(line, offset, raw, tailStart, end, isCondition = false)
                if (next < end) {
                    throw StoryParseException("'@timeout' takes only the time", span(offset, line, next, end))
                }
                StoryLineKind.Timeout(duration)
            }

            "command" -> {
                if (tailStart >= end) {
                    throw StoryParseException("'@command' expects a vanilla command", nameSpan)
                }
                val template = parseTemplate(line, offset, raw, tailStart, end, allowInline = false)
                StoryLineKind.Command(template, span(offset, line, tailStart, end))
            }

            else -> StoryLineKind.FuncCall(name, nameSpan, parseArgs(line, offset, raw, tailStart, end))
        }
    }

    private fun parseSet(line: Int, offset: Int, raw: String, start: Int, end: Int, commandSpan: StorySpan): StoryLineKind {
        var i = start
        val nameStart = i
        while (i < end && StoryExpressions.isIdentPart(raw[i])) i++
        if (i == nameStart) {
            throw StoryParseException("'@set' expects a variable name", commandSpan)
        }
        val variable = raw.substring(nameStart, i)
        val variableSpan = span(offset, line, nameStart, i)
        while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
        if (i >= end || raw[i] != '=') {
            throw StoryParseException("'@set' expects '=' after the variable name", variableSpan)
        }
        i++
        val value = StoryExpressions.parse(raw, offset, line, i, end)
        return StoryLineKind.Set(variable, variableSpan, value)
    }

    private fun parseRef(line: Int, offset: Int, raw: String, start: Int, end: Int, command: String): StoryRef {
        if (start >= end) {
            throw StoryParseException("'@$command' expects a target like #Метка or file.story#Метка", span(offset, line, start, start))
        }
        var i = start
        while (i < end && raw[i] != ' ' && raw[i] != '\t') i++
        if (i < end) {
            throw StoryParseException("'@$command' target must be a single token", span(offset, line, i, end))
        }
        val token = raw.substring(start, i)
        val refSpan = span(offset, line, start, i)
        val hash = token.indexOf('#')
        val address = (if (hash < 0) token else token.substring(0, hash)).takeIf { it.isNotEmpty() }
        val label = if (hash < 0) null else token.substring(hash + 1)
        if (label != null && label.isEmpty()) {
            throw StoryParseException("Label after '#' is empty", refSpan)
        }
        if (address == null && label == null) {
            throw StoryParseException("Empty '@$command' target", refSpan)
        }
        return StoryRef(address, label, refSpan)
    }

    private fun parseChoice(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        if (start >= end || raw[start] != '"') {
            throw StoryParseException("'@choice' expects a quoted text first: @choice \"Текст\"", span(offset, line, start, minOf(start + 1, end)))
        }
        val closing = findClosingQuote(raw, start, end)
            ?: throw StoryParseException("Unterminated choice text", span(offset, line, start, end))
        val text = parseTemplate(line, offset, raw, start + 1, closing, allowInline = false)
        var i = closing + 1
        while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
        val args = parseArgs(line, offset, raw, i, end)
        return StoryLineKind.Choice(text, args)
    }

    private fun parseAsync(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        var i = start
        var trackName: String? = null
        if (raw.startsWith("name=", i)) {
            val valueStart = i + "name=".length
            var j = valueStart
            while (j < end && raw[j] != ' ' && raw[j] != '\t') j++
            trackName = raw.substring(valueStart, j)
            if (trackName.isEmpty()) {
                throw StoryParseException("Empty track name", span(offset, line, i, j))
            }
            i = j
            while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
        }
        if (i >= end) return StoryLineKind.Async(trackName, inline = null)

        if (raw[i] == '@') i++
        val nameStart = i
        while (i < end && isNameChar(raw[i])) i++
        val function = raw.substring(nameStart, i)
        if (function.isEmpty()) {
            throw StoryParseException("Expected a command after '@async'", span(offset, line, nameStart, end))
        }
        val functionSpan = span(offset, line, nameStart, i)
        while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
        val call = StoryLineKind.FuncCall(function, functionSpan, parseArgs(line, offset, raw, i, end))
        return StoryLineKind.Async(trackName, call)
    }

    private fun parseAwait(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        val names = mutableListOf<String>()
        var i = start
        while (i < end) {
            val tokenStart = i
            while (i < end && raw[i] != ' ' && raw[i] != '\t') i++
            names += raw.substring(tokenStart, i)
            while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
        }
        return StoryLineKind.Await(names)
    }

    /** A single word after `@command`, or null when nothing follows. */
    private fun parseName(line: Int, offset: Int, raw: String, start: Int, end: Int, command: String): Pair<String, StorySpan>? {
        if (start >= end) return null
        var i = start
        while (i < end && raw[i] != ' ' && raw[i] != '\t') i++
        if (i < end) {
            throw StoryParseException("'@$command' expects a single name", span(offset, line, i, end))
        }
        return raw.substring(start, i) to span(offset, line, start, i)
    }

    private fun parseTemporary(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        if (start >= end) return StoryLineKind.Temporary(null, null)
        val prefix = "until="
        if (!raw.startsWith(prefix, start)) {
            throw StoryParseException("'@temporary' takes only until=checkpoint", span(offset, line, start, end))
        }
        val (until, untilSpan) = parseName(line, offset, raw, start + prefix.length, end, "temporary")
            ?: throw StoryParseException("'until=' expects a checkpoint name", span(offset, line, start, end))
        return StoryLineKind.Temporary(until, untilSpan)
    }

    private fun parseCancel(line: Int, offset: Int, raw: String, start: Int, end: Int, commandSpan: StorySpan): StoryLineKind {
        if (start >= end) {
            throw StoryParseException("'@cancel' expects a track name", commandSpan)
        }
        var i = start
        while (i < end && raw[i] != ' ' && raw[i] != '\t') i++
        if (i < end) {
            throw StoryParseException("'@cancel' expects a single track name", span(offset, line, i, end))
        }
        return StoryLineKind.Cancel(raw.substring(start, i), span(offset, line, start, i))
    }

    private fun parseArgs(line: Int, offset: Int, raw: String, start: Int, end: Int): List<StoryArg> {
        val args = mutableListOf<StoryArg>()
        var i = start
        while (i < end) {
            while (i < end && (raw[i] == ' ' || raw[i] == '\t')) i++
            if (i >= end) break
            val argStart = i

            var name: String? = null
            var nameSpan: StorySpan? = null
            if (StoryExpressions.isIdentStart(raw[i])) {
                var j = i
                while (j < end && StoryExpressions.isIdentPart(raw[j])) j++
                if (j < end && raw[j] == '=' && j + 1 <= end) {
                    name = raw.substring(i, j)
                    nameSpan = span(offset, line, i, j)
                    i = j + 1
                }
            }

            val (expr, next) = parseArgValue(line, offset, raw, i, end, isCondition = name == "if")
            i = next
            args += StoryArg(name, nameSpan, expr, span(offset, line, argStart, i))
        }
        return args
    }


    private fun parseArgList(line: Int, offset: Int, raw: String, open: Int, close: Int): StoryExpression {
        val items = mutableListOf<StoryExpression>()
        var i = open + 1
        while (i < close) {
            while (i < close && (raw[i] == ' ' || raw[i] == '\t' || raw[i] == ',')) i++
            if (i >= close) break
            val (item, next) = parseArgValue(line, offset, raw, i, close, isCondition = false, insideList = true)
            items += item
            i = next
            while (i < close && (raw[i] == ' ' || raw[i] == '\t')) i++
            if (i < close && raw[i] == ',') i++
        }
        return StoryExpressions.list(items, span(offset, line, open, close + 1))
    }

    private fun parseArgValue(
        line: Int,
        offset: Int,
        raw: String,
        start: Int,
        end: Int,
        isCondition: Boolean,
        insideList: Boolean = false,
    ): Pair<StoryExpression, Int> {
        if (start >= end) {
            throw StoryParseException("Expected a value", span(offset, line, start, start))
        }
        when (raw[start]) {
            '"' -> {
                val closing = findClosingQuote(raw, start, end)
                    ?: throw StoryParseException("Unterminated string", span(offset, line, start, end))
                return if (isCondition) {
                    StoryExpressions.parse(raw, offset, line, start + 1, closing) to closing + 1
                } else {
                    val value = unescape(raw.substring(start + 1, closing))
                    StoryExpressions.literal(StoryString(value), span(offset, line, start, closing + 1)) to closing + 1
                }
            }

            '{' -> {
                val closing = findClosingBracket(raw, start, end, '{', '}')
                    ?: throw StoryParseException("Unterminated '{'", span(offset, line, start, end))
                val expr = StoryExpressions.parse(raw, offset, line, start + 1, closing)
                return expr to closing + 1
            }

            '[' -> {
                val closing = findClosingBracket(raw, start, end, '[', ']')
                    ?: throw StoryParseException("Unterminated '['", span(offset, line, start, end))
                if (isCondition) {
                    return StoryExpressions.parse(raw, offset, line, start, closing + 1) to closing + 1
                }
                return parseArgList(line, offset, raw, start, closing) to closing + 1
            }
        }

        var i = start
        while (i < end && raw[i] != ' ' && raw[i] != '\t' && !(insideList && raw[i] == ',')) i++
        val token = raw.substring(start, i)
        val tokenSpan = span(offset, line, start, i)
        if (isCondition) {
            return StoryExpressions.parse(raw, offset, line, start, i) to i
        }
        val expr = when {
            BARE_NUMBER.matches(token) -> StoryExpressions.parse(raw, offset, line, start, i)
            token == "true" -> StoryExpressions.literal(StoryBool(true), tokenSpan)
            token == "false" -> StoryExpressions.literal(StoryBool(false), tokenSpan)
            else -> StoryExpressions.literal(StoryString(token), tokenSpan)
        }
        return expr to i
    }

    private fun parseDialogue(line: Int, offset: Int, raw: String, start: Int, end: Int): StoryLineKind {
        var speaker: String? = null
        var speakerExpr: StoryExpression? = null
        var speakerSpan: StorySpan? = null
        var textStart = start

        if (raw[start] == '{') {
            val closing = findClosingBracket(raw, start, end, '{', '}')
            if (closing != null && closing + 1 < end && raw[closing + 1] == ':') {
                speakerExpr = StoryExpressions.parse(raw, offset, line, start + 1, closing)
                speakerSpan = span(offset, line, start, closing + 1)
                textStart = closing + 2
                while (textStart < end && (raw[textStart] == ' ' || raw[textStart] == '\t')) textStart++
            }
        }

        if (speakerExpr == null) {
            val colon = raw.indexOf(':', start)
            if (colon in (start + 1) until end) {
                val candidate = raw.substring(start, colon)
                if (candidate.all { isNameChar(it) }) {
                    speaker = candidate
                    speakerSpan = span(offset, line, start, colon)
                    textStart = colon + 1
                    while (textStart < end && (raw[textStart] == ' ' || raw[textStart] == '\t')) textStart++
                }
            }
        }

        val text = parseTemplate(line, offset, raw, textStart, end, allowInline = true)
        return StoryLineKind.Dialogue(speaker, speakerSpan, text, speakerExpr)
    }

    private fun parseTemplate(
        line: Int,
        offset: Int,
        raw: String,
        start: Int,
        end: Int,
        allowInline: Boolean,
    ): TextTemplate {
        val parts = mutableListOf<TextPart>()
        val literal = StringBuilder()
        var literalStart = start
        var i = start

        fun flushLiteral(upTo: Int) {
            if (literal.isNotEmpty()) {
                parts += TextPart.Literal(literal.toString(), span(offset, line, literalStart, upTo))
                literal.clear()
            }
        }

        while (i < end) {
            when (val c = raw[i]) {
                '\\' -> {
                    if (i + 1 < end) {
                        literal.append(
                            when (val esc = raw[i + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> esc
                            },
                        )
                        i += 2
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                '{' -> {
                    val closing = findClosingBracket(raw, i, end, '{', '}')
                        ?: throw StoryParseException("Unterminated '{' in text", span(offset, line, i, end))
                    flushLiteral(i)
                    val expr = StoryExpressions.parse(raw, offset, line, i + 1, closing)
                    parts += TextPart.Interpolation(expr, span(offset, line, i, closing + 1))
                    i = closing + 1
                    literalStart = i
                }

                '[' -> {
                    if (!allowInline) {
                        literal.append(c)
                        i++
                        continue
                    }
                    val closing = findClosingBracket(raw, i, end, '[', ']')
                        ?: throw StoryParseException("Unterminated '[' in text", span(offset, line, i, end))
                    flushLiteral(i)
                    val partSpan = span(offset, line, i, closing + 1)
                    var inner = i + 1
                    var innerEnd = closing
                    while (inner < innerEnd && (raw[inner] == ' ' || raw[inner] == '\t')) inner++
                    while (innerEnd > inner && (raw[innerEnd - 1] == ' ' || raw[innerEnd - 1] == '\t')) innerEnd--
                    if (innerEnd - inner == 1 && raw[inner] == '-') {
                        parts += TextPart.WaitInput(partSpan)
                    } else {
                        if (inner >= innerEnd) {
                            throw StoryParseException("Empty inline command", partSpan)
                        }
                        if (raw[inner] == '@') inner++
                        val nameStart = inner
                        while (inner < innerEnd && isNameChar(raw[inner])) inner++
                        val function = raw.substring(nameStart, inner)
                        if (function.isEmpty()) {
                            throw StoryParseException("Expected an inline command name", partSpan)
                        }
                        val functionSpan = span(offset, line, nameStart, inner)
                        while (inner < innerEnd && (raw[inner] == ' ' || raw[inner] == '\t')) inner++
                        val call = StoryLineKind.FuncCall(function, functionSpan, parseArgs(line, offset, raw, inner, innerEnd))
                        parts += TextPart.InlineCall(call, partSpan)
                    }
                    i = closing + 1
                    literalStart = i
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flushLiteral(end)
        return TextTemplate(parts, span(offset, line, start, end))
    }

    private fun findClosingQuote(raw: String, opening: Int, end: Int): Int? {
        var i = opening + 1
        while (i < end) {
            when (raw[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return null
    }

    private fun findClosingBracket(raw: String, opening: Int, end: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inQuotes = false
        var i = opening
        while (i < end) {
            when (raw[i]) {
                '\\' -> i++
                '"' -> inQuotes = !inQuotes
                open -> if (!inQuotes) depth++
                close -> if (!inQuotes) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun unescape(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                append(
                    when (val esc = text[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> esc
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
