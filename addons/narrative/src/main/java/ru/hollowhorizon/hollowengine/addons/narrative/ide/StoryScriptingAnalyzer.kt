package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.scene.ScenePlayer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.StoryString
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.ui.text.FormattedTextParser
import ru.hollowhorizon.hollowengine.common.utils.expressions.Ast

/**
 * IDE support for `.story`. Everything here runs through the same parser and compiler the engine
 * plays stories with, so a file the editor calls clean is a file that loads, and the function names
 * it offers are the ones actually registered.
 */
object StoryScriptingAnalyzer : ScriptingAnalyzer {
    override fun highlight(name: String, text: String, offset: Int): List<TextLine> =
        buildTextLines(text, StoryHighlighter.spans(text, catalog()), StoryInlays.hints(text, catalog()))

    override fun lightweightHighlightLine(name: String, line: String): TextLine =
        buildTextLines(line, StoryHighlighter.spans(line, catalog()), emptyList()).firstOrNull()
            ?: TextLine(emptyList(), ArrayList())

    override fun diagnostic(name: String, text: String): List<Diagnostic> {
        val result = StoryCompiler.compile(name, text, catalog())
        val offsets = LineOffsets(text)
        val compilerDiagnostics = result.diagnostics.map { diagnostic ->
            Diagnostic(
                range = Range(offsets.position(diagnostic.span.start), offsets.position(diagnostic.span.end)),
                severity = when (diagnostic.severity) {
                    StorySeverity.ERROR -> Severity.ERROR
                    StorySeverity.WARNING -> Severity.WARNING
                },
                message = diagnostic.message,
            )
        }
        val formattingDiagnostics = StoryParser.parse(text).cst.lines.flatMap { line ->
            line.kind.formattedTemplates().flatMap { template ->
                val source = text.substring(template.span.start, template.span.end)
                FormattedTextParser.parse(source).diagnostics.map { diagnostic ->
                    val start = template.span.start + diagnostic.offset
                    Diagnostic(
                        range = Range(
                            offsets.position(start),
                            offsets.position(start + diagnostic.length),
                        ),
                        severity = Severity.WARNING,
                        message = diagnostic.message,
                    )
                }
            }
        }
        return compilerDiagnostics + formattingDiagnostics
    }

    override fun completions(name: String, text: String, offset: Int, sink: CompletionSink) {
        sink.emit(storyCompletions(text, offset.coerceIn(0, text.length), catalog()))
    }

    override fun occurrences(name: String, text: String, offset: Int): List<OccurrenceRange> {
        val caret = offset.coerceIn(0, text.length)
        val parsed = StoryParser.parse(text)

        labelAt(parsed.cst, caret)?.let { label -> return labelOccurrences(parsed.cst, label) }
        variableAt(parsed.cst, caret)?.let { variable -> return variableOccurrences(parsed.cst, variable) }
        return emptyList()
    }

    override fun definition(name: String, text: String, offset: Int): DefinitionLocation? {
        val caret = offset.coerceIn(0, text.length)
        val parsed = StoryParser.parse(text)

        for (line in parsed.cst.lines) {
            val target = when (val kind = line.kind) {
                is StoryLineKind.Jump -> kind.target
                is StoryLineKind.Call -> kind.target
                else -> null
            } ?: continue
            if (caret < target.span.start || caret > target.span.end) continue

            val address = target.address
            if (address == null) {
                val label = target.label ?: return null
                return labelOffset(text, label)?.let { DefinitionLocation(name, it) }
            }
            return storyDefinition(address, target.label)
        }

        val literal = stringLiteralAt(text, caret) ?: return null
        return ResourceLocationTargets.definition(literal)
    }

    internal fun catalog(): StoryFunctionCatalog =
        if (ActionCommands.names.isEmpty()) StoryFunctionCatalog.PERMISSIVE else ActionCommands

    private fun storyDefinition(address: String, label: String?): DefinitionLocation? {
        val artifacts = runCatching { ScriptRegistry.artifacts(address) }.getOrNull() ?: return null
        val file = artifacts.sourceFile?.takeIf { it.isFile } ?: return null
        val path = ScriptRegistry.display(artifacts.id)
        val offset = label?.let { labelOffset(runCatching { file.readText() }.getOrDefault(""), it) } ?: 0
        return DefinitionLocation(path, offset)
    }

    private fun labelOffset(text: String, label: String): Int? =
        StoryParser.parse(text).cst.lines.firstOrNull { (it.kind as? StoryLineKind.Label)?.name == label }
            ?.let { (it.kind as StoryLineKind.Label).nameSpan.start }

    private fun stringLiteralAt(text: String, caret: Int): String? {
        var start = caret
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '"') start--
        var end = caret
        while (end < text.length && !text[end].isWhitespace() && text[end] != '"') end++
        return text.substring(start, end).takeIf { it.isNotEmpty() }
    }
}

private fun StoryLineKind.formattedTemplates(): List<TextTemplate> = when (this) {
    is StoryLineKind.Dialogue -> listOf(text)
    is StoryLineKind.Choice -> listOf(text)
    else -> emptyList()
}

/** The label named at [caret], whether that is its declaration or a jump/call that reaches it. */
private fun labelAt(cst: StoryFileCst, caret: Int): String? {
    for (line in cst.lines) {
        when (val kind = line.kind) {
            is StoryLineKind.Label -> if (caret in kind.nameSpan.start..kind.nameSpan.end) return kind.name

            is StoryLineKind.Jump -> targetLabelAt(kind.target, caret)?.let { return it }
            is StoryLineKind.Call -> targetLabelAt(kind.target, caret)?.let { return it }
            else -> Unit
        }
    }
    return null
}

/** Only local targets take part: `file.story#Label` names a label of another file. */
private fun targetLabelAt(target: StoryRef, caret: Int): String? {
    if (target.address != null || target.label == null) return null
    return target.label.takeIf { caret in target.span.start..target.span.end }
}

private fun labelOccurrences(cst: StoryFileCst, label: String): List<OccurrenceRange> {
    val ranges = ArrayList<OccurrenceRange>()
    for (line in cst.lines) {
        when (val kind = line.kind) {
            is StoryLineKind.Label -> if (kind.name == label) ranges += OccurrenceRange(
                kind.nameSpan.start, kind.nameSpan.end
            )

            is StoryLineKind.Jump -> targetRange(kind.target, label)?.let { ranges += it }
            is StoryLineKind.Call -> targetRange(kind.target, label)?.let { ranges += it }
            else -> Unit
        }
    }
    return ranges.sortedBy { it.start }
}

private fun targetRange(target: StoryRef, label: String): OccurrenceRange? {
    if (target.address != null || target.label != label) return null
    // Highlight the label part only, leaving the `#` alone.
    return OccurrenceRange(target.span.end - label.length, target.span.end)
}

/** The variable named at [caret], in a `@set`, an expression or a `{…}` interpolation. */
private fun variableAt(cst: StoryFileCst, caret: Int): String? {
    for (line in cst.lines) {
        val kind = line.kind
        if (kind is StoryLineKind.Set && caret in kind.variableSpan.start..kind.variableSpan.end) {
            return kind.variable
        }
        for (reference in variableReferences(kind)) {
            if (caret in reference.span.start..reference.span.end) return reference.name
        }
    }
    return null
}

private fun variableOccurrences(cst: StoryFileCst, variable: String): List<OccurrenceRange> {
    val ranges = ArrayList<OccurrenceRange>()
    for (line in cst.lines) {
        val kind = line.kind
        if (kind is StoryLineKind.Set && kind.variable == variable) {
            ranges += OccurrenceRange(kind.variableSpan.start, kind.variableSpan.end)
        }
        variableReferences(kind).filter { it.name == variable }
            .forEach { ranges += OccurrenceRange(it.span.start, it.span.end) }
    }
    return ranges.sortedBy { it.start }
}

private fun variableReferences(kind: StoryLineKind): List<Ast.Name> {
    val found = ArrayList<Ast.Name>()
    fun collect(expr: StoryExpression) {
        found += expr.names()
    }

    fun collect(template: TextTemplate) {
        template.parts.forEach { part ->
            when (part) {
                is TextPart.Interpolation -> collect(part.expr)
                is TextPart.Literal -> Unit
            }
        }
    }

    when (kind) {
        is StoryLineKind.If -> collect(kind.condition)
        is StoryLineKind.ElseIf -> collect(kind.condition)
        is StoryLineKind.While -> collect(kind.condition)
        is StoryLineKind.Set -> collect(kind.value)
        is StoryLineKind.Dialogue -> {
            kind.speakerExpr?.let { collect(it) }
            collect(kind.text)
        }

        is StoryLineKind.Command -> collect(kind.text)
        is StoryLineKind.Choice -> {
            collect(kind.text)
            kind.args.forEach { collect(it.expr) }
        }

        is StoryLineKind.FuncCall -> kind.args.forEach { collect(it.expr) }
        is StoryLineKind.Async -> kind.inline?.args?.forEach { collect(it.expr) }
        else -> Unit
    }
    return found
}

internal class LineOffsets(text: String) {
    private val starts = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    fun position(offset: Int): Position {
        val clamped = offset.coerceIn(0, Int.MAX_VALUE)
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= clamped) low = mid else high = mid - 1
        }
        return Position(low, clamped - starts[low])
    }
}

internal fun storyCompletions(
    text: String,
    caret: Int,
    catalog: StoryFunctionCatalog,
): List<CompletionItem> {
    val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val line = text.substring(lineStart, caret)

    return when (val context = storyCompletionContext(line, line.length)) {
        is StoryCompletionContext.None -> emptyList()

        is StoryCompletionContext.Command -> (StoryHighlighter.BUILTIN_COMMANDS + registeredNames(catalog)).distinct()
            .filter { completionMatches(context.typed, it) }.flatMap { command -> commandCompletions(command, catalog) }

        is StoryCompletionContext.Label -> labelCompletions(text, context.typed)
        is StoryCompletionContext.Checkpoint -> checkpointCompletions(text, context.typed)

        is StoryCompletionContext.Expression -> if (context.member) memberCompletions(context.typed) else variableCompletions(
            text, context.typed
        )

        is StoryCompletionContext.Argument -> if (context.command == "set") {
            variableCompletions(text, context.typed)
        } else {
            val values = valueCompletions(text, context, catalog)
            if (context.parameter != null) values
            else values + parameterNameCompletions(context.command, context.typed, catalog)
        }
    }
}

private fun registeredNames(catalog: StoryFunctionCatalog): List<String> = catalog.names.sorted()

private fun labelCompletions(text: String, typed: String): List<CompletionItem> =
    labelsOf(text).filter { completionMatches(typed, it) }.map { label ->
        declarationCompletionItem {
            show = "#$label"
            insert = "#$label"
            name = label
            tag = CompletionItemTag.CLASS
            tail = "label"
            wordChars = "#"
        }
    }

/** The checkpoints this file passes; those a Kotlin stage of the story passes are not known here. */
private fun checkpointCompletions(text: String, typed: String): List<CompletionItem> =
    StoryParser.parse(text).cst.lines.mapNotNull { (it.kind as? StoryLineKind.Checkpoint)?.name }.distinct()
        .filter { completionMatches(typed, it) }.map { checkpoint ->
            declarationCompletionItem {
                show = checkpoint
                name = checkpoint
                tag = CompletionItemTag.CLASS
                tail = "checkpoint"
                wordChars = "-"
            }
        }

private fun variableCompletions(text: String, typed: String): List<CompletionItem> =
    variablesOf(text).filter { it != typed && completionMatches(typed, it) }.map { variable ->
        declarationCompletionItem {
            show = variable
            name = variable
            tag = CompletionItemTag.LOCAL_VARIABLE
            tail = "variable"
        }
    }

private fun memberCompletions(typed: String): List<CompletionItem> =
    StoryDeclarations.types["value"]?.members?.allFields.orEmpty().flatMap { it.names }.distinct()
        .filter { completionMatches(typed, it) }.map { member ->
            declarationCompletionItem {
                show = member
                name = member
                tag = CompletionItemTag.PROPERTY
                tail = "member"
            }
        }

private fun parameterNameCompletions(
    command: String,
    typed: String,
    catalog: StoryFunctionCatalog,
): List<CompletionItem> =
    catalog.overloads(command).orEmpty().asSequence().flatMap(StoryFunctionSignature::params).map { it.name }.distinct()
        .filter { completionMatches(typed, it) }.map { parameter ->
            declarationCompletionItem {
                show = "$parameter="
                insert = "$parameter="
                name = parameter
                tag = CompletionItemTag.PROPERTY
                tail = "parameter"
                wordChars = "-"
            }
        }.toList()

private fun valueCompletions(
    text: String,
    context: StoryCompletionContext.Argument,
    catalog: StoryFunctionCatalog,
): List<CompletionItem> {
    val params = catalog.overloads(context.command).orEmpty().flatMap { it.params }
    val parameter = when (val name = context.parameter) {
        null -> params.getOrNull(context.positional)
        else -> params.firstOrNull { it.name == name }
    } ?: return emptyList()

    val values = when {
        parameter.suggestions.isNotEmpty() -> parameter.suggestions
        parameter.type == StoryType.ACTOR -> actorsOf(text)
        parameter.type == StoryType.BOOL -> listOf("true", "false")
        else -> return emptyList()
    }

    return values.filter { completionMatches(context.typed, it) }.map { value ->
        declarationCompletionItem {
            show = value
            name = value
            tag = if (parameter.type == StoryType.ACTOR) CompletionItemTag.CLASS else CompletionItemTag.PROPERTY
            tail = parameter.name
            wordChars = "-:"
        }
    }
}

private fun commandCompletions(command: String, catalog: StoryFunctionCatalog): List<CompletionItem> {
    val builtin = command in StoryHighlighter.BUILTIN_COMMANDS
    if (builtin) return listOf(commandCompletion(command, BUILTIN_USAGE[command].orEmpty(), builtin = true))

    val overloads = catalog.overloads(command).orEmpty()
    if (overloads.isEmpty()) return listOf(commandCompletion(command, "", builtin = false))
    return overloads.map { signature ->
        commandCompletion(command, signature.params.joinToString(" ") { it.display() }, builtin = false)
    }
}

private fun commandCompletion(command: String, params: String, builtin: Boolean): CompletionItem =
    declarationCompletionItem {
        show = "@$command"
        insert = "@$command "
        name = command
        tag = if (builtin) CompletionItemTag.KEYWORD else CompletionItemTag.FUNCTION
        wordChars = "@-"
        middle = if (params.isEmpty()) "" else " $params"
        tail = if (builtin) "built-in" else "function"
    }

private val BUILTIN_USAGE = mapOf(
    "if" to "condition",
    "else-if" to "condition",
    "else" to "",
    "while" to "condition",
    "set" to "variable = value",
    "jump" to "#label | file.story#label",
    "call" to "#label | file.story#label",
    "return" to "",
    "choice" to "\"text\" [id=… if=…]",
    "async" to "[name=…] [command]",
    "await" to "[track names]",
    "cancel" to "track name",
    "sync" to "",
    "command" to "vanilla command",
)

private fun labelsOf(text: String): List<String> =
    StoryParser.parse(text).cst.lines.mapNotNull { (it.kind as? StoryLineKind.Label)?.name }

private fun actorsOf(text: String): List<String> {
    val speakers =
        StoryParser.parse(text).cst.lines.mapNotNull { (it.kind as? StoryLineKind.Dialogue)?.speaker }.distinct()
    return (listOf(ScenePlayer.PLAYER_ACTOR) + speakers).distinct()
}

private fun variablesOf(text: String): List<String> {
    val names = LinkedHashSet<String>()
    for (line in StoryParser.parse(text).cst.lines) {
        val kind = line.kind
        if (kind is StoryLineKind.Set) names += kind.variable
        variableReferences(kind).forEach { names += it.name }
    }
    return names.toList()
}

internal object StoryInlays {
    fun hints(text: String, catalog: StoryFunctionCatalog): List<InlayHint> {
        val parsed = StoryParser.parse(text)
        val hints = ArrayList<InlayHint>()
        for (line in parsed.cst.lines) {
            val call = when (val kind = line.kind) {
                is StoryLineKind.FuncCall -> kind
                is StoryLineKind.Async -> kind.inline ?: continue
                else -> continue
            }
            val signature = catalog.overloads(call.function).orEmpty()
                .firstOrNull { it.params.size >= call.args.count { arg -> arg.name == null } }

            call.args.forEachIndexed { index, arg ->
                if (arg.name == null) {
                    signature?.params?.getOrNull(index)?.let { param ->
                        hints += InlayHint(arg.span.start, "${param.name}=", listOf(InlayTags.PARAMETER))
                    }
                }
                val literal = arg.expr.constant as? StoryString ?: return@forEachIndexed
                if (!ResourceLocationTargets.looksLikeLocation(literal.value)) return@forEachIndexed
                val target = ResourceLocationTargets.targetOf(literal.value) ?: return@forEachIndexed
                hints += InlayHint(
                    index = arg.span.end,
                    content = listOf(InlayContent.Icon(InlayIcons.OPEN_RESOURCE)),
                    tags = listOf(InlayTags.ACTION, target.tag),
                    action = InlayAction.OpenResource(literal.value),
                )
            }
        }
        return hints
    }
}
