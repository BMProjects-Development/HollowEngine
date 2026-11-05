package ru.hollowhorizon.hollowengine.common.scripting.ide

interface ScriptingAnalyzer {
    fun highlight(name: String, text: String, offset: Int): List<TextLine>

    /** Ranges to highlight for the symbol/bracket at [offset]; cheap compared to [highlight]. */
    fun occurrences(name: String, text: String, offset: Int): List<OccurrenceRange> = emptyList()

    fun lightweightHighlightLine(name: String, line: String): TextLine {
        return TextLine(listOf(line to SpanStyle(TokenType.DEFAULT, italic = false, bold = false, highlight = false)), ArrayList())
    }

    /**
     * Streams completions to [sink] rather than returning them.
     */
    fun completions(name: String, text: String, offset: Int, sink: CompletionSink) = Unit

    /** Whether [format] does anything here; the editor hides its reformat action when it does not. */
    val canFormat: Boolean get() = false

    /**
     * Reformats the whole file and returns the new text, or null when the language has no formatter
     * or the text is already formatted.
     */
    fun format(name: String, text: String): String? = null

    fun definition(name: String, text: String, offset: Int): DefinitionLocation? = null
    fun structure(name: String, text: String): EditorStructure = EditorStructure()

    /**
     * Where the navigable gutter marker at [offset] leads. Resolved only on click: the targets may
     * live in library sources or need decompiling, which is too slow for every analysis pass.
     */
    fun gutterTargets(name: String, text: String, offset: Int): List<GutterNavigationTarget> = emptyList()

    /**
     * Text of a read-only document this language produces on demand, such as a decompiled class, so a
     * tab restored from the saved layout can be filled again. Null when [path] is not such a document.
     */
    fun documentText(path: String): String? = null

    /**
     * Alt+Enter actions at the caret, such as import suggestions.
     */
    fun intentionActions(name: String, text: String, offset: Int): List<DiagnosticFix> = emptyList()

    /**
     * Which of [types], fully qualified class names as scripts see them, an expression typed at [offset]
     * may have; used by completions that insert a whole expression. Null when [offset] is not where an
     * expression starts. A language that cannot tell accepts every type.
     */
    fun acceptedExpressionTypes(name: String, text: String, offset: Int, types: List<String>): List<String>? = types

    /** How the Kotlin [text], statements or an expression, reads on its own. Null when the language cannot parse Kotlin. */
    fun kotlinSyntax(text: String): KotlinSyntax? = null

    /** The properties of the file whose names start with [values] and [places]; those that do not compile are left out. */
    fun propertyTypes(name: String, text: String, values: String, places: String? = null): KotlinPropertyTypes =
        KotlinPropertyTypes()

    /** Lets go of what is kept for the file [name], which nothing has open any more. */
    fun forget(name: String) = Unit

    /** The name at [offset] that [rename] can change, or null when it does not name a declaration of the project. */
    fun renameTarget(name: String, text: String, offset: Int): RenameTarget? = null

    /**
     * What changes, through every script of the project, once the declaration named at [offset] is called [newName]:
     * the declaration itself, the places that refer to it and the imports that name it.
     *
     * @param documents the texts of files open in the editor, read in place of what is on disk.
     */
    fun rename(
        name: String,
        text: String,
        offset: Int,
        newName: String,
        documents: Map<String, String>,
    ): List<FileRename> = emptyList()

    fun signatureHelp(name: String, text: String, offset: Int): SignatureHelp? = null
    fun hover(name: String, text: String, offset: Int): HoverInfo? = null
    fun diagnostic(name: String, text: String): List<Diagnostic>

    /**
     * Classes and top-level functions and properties on the script classpath whose name matches
     * [query]: a simple name, part of one, or a name qualified by part of its package. Closest first.
     */
    fun searchSymbols(query: String, limit: Int): List<SymbolMatch> = emptyList()

    /**
     * Source of the class, member or top-level declaration [qualifiedName] names, read from a sources
     * jar when there is one and decompiled otherwise; [DefinitionLocation.offset] points at the name.
     */
    fun symbolSource(qualifiedName: String): DefinitionLocation? = null
}

/**
 * What analysis says of named properties.
 *
 * @param types what each of them is, as Kotlin writes it with packages.
 * @param fits by value, the places that can hold it. Null does not count: links unwrap what can be null.
 */
data class KotlinPropertyTypes(
    val types: Map<String, String> = emptyMap(),
    val fits: Map<String, Set<String>> = emptyMap(),
)

/**
 * What a parser made of a snippet of Kotlin.
 *
 * @param valid whether it parsed with no errors.
 * @param arguments what is between the commas, when the snippet is one call; an argument not typed yet is empty.
 * @param names the names it uses from outside, without its locals and the members of other things.
 * @param tokens what can be colored by the look of it, with no analysis.
 */
data class KotlinSyntax(
    val valid: Boolean,
    val arguments: List<String>? = null,
    val names: List<KotlinName> = emptyList(),
    val tokens: List<KotlinToken> = emptyList(),
)

/** A token of a snippet, at the end-exclusive range [start] to [end]. */
data class KotlinToken(val start: Int, val end: Int, val type: TokenType)

/** A name a snippet uses, at the end-exclusive range [start] to [end]. */
data class KotlinName(val name: String, val start: Int, val end: Int)

enum class SymbolKind { CLASS, ANNOTATION, FUNCTION, PROPERTY }

/** A declaration found by [ScriptingAnalyzer.searchSymbols]; [signature] is empty for classes. */
data class SymbolMatch(
    val kind: SymbolKind,
    val qualifiedName: String,
    val signature: String = "",
)

/**
 * Receives completions as they are produced. Returning `false` means the request is stale (the
 * caret moved on) and the analyzer should stop enumerating instead of finishing a scan nobody is
 * waiting for.
 */
fun interface CompletionSink {
    fun emit(items: List<CompletionItem>): Boolean
}

/** A name [ScriptingAnalyzer.rename] can change, at the end-exclusive range [start] to [end]. */
data class RenameTarget(val name: String, val start: Int, val end: Int)

/** The [edits] renaming makes in the file [path], whose text was [text] when they were worked out. */
data class FileRename(val path: String, val text: String, val edits: List<TextEdit>) {
    /** [text] with the [edits] made. */
    fun applied(): String {
        val builder = StringBuilder(text)
        edits.sortedByDescending { it.start }.forEach { builder.replace(it.start, it.end, it.replacement) }
        return builder.toString()
    }
}

data class OccurrenceRange(
    val start: Int,
    val end: Int,
)

/** A compiler-provided syntax color range inside a code-insight presentation. */
data class CodeInsightHighlight(
    val range: OccurrenceRange,
    val tokenType: TokenType,
)

data class DefinitionLocation(
    val path: String,
    val offset: Int = 0,
    val text: String? = null,
    val readOnly: Boolean = text != null,
)

/** Parameter information for the call containing the caret. Ranges use end-exclusive offsets. */
data class SignatureHelp(
    val anchor: Int,
    val signatures: List<CallableSignature>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
)

data class CallableSignature(
    val label: String,
    val parameters: List<OccurrenceRange>,
    val documentation: String? = null,
    val highlights: List<CodeInsightHighlight> = emptyList(),
    /** Portion shown by parameter info; hover still uses the complete [label]. */
    val presentation: OccurrenceRange = OccurrenceRange(0, label.length),
)

/** Symbol information shown after a delayed hover. */
data class HoverInfo(
    val start: Int,
    val end: Int,
    val signature: String,
    val documentation: String? = null,
    val highlights: List<CodeInsightHighlight> = emptyList(),
)

/**
 * What an inlay draws. Kinds are added here, not in the editor: the editor renders each
 * part with its own tags and lets the stylesheet decide how it looks.
 */
sealed interface InlayContent {
    data class Label(val text: String) : InlayContent

    data class Icon(val source: String) : InlayContent

    data class Swatch(val argb: Int) : InlayContent
}

/**
 * What clicking an inlay does. The editor treats the action as an opaque token and hands
 * [id] back to its host, so decoding lives here and nowhere else.
 */
sealed interface InlayAction {
    val id: String

    /** Opens the file a `namespace:path` literal points at. */
    data class OpenResource(val location: String) : InlayAction {
        override val id: String get() = OpenResourcePrefix + location
    }

    /**
     * Opens a color picker over the literal at `[start], [end])`. [literal] is the exact text the
     * hint was built from.
     */
    data class PickColor(val start: Int, val end: Int, val literal: String) : InlayAction {
        override val id: String get() = "$PickColorPrefix$start:$end:$literal"
    }

    companion object {
        private const val OpenResourcePrefix = "open-resource:"
        private const val PickColorPrefix = "pick-color:"

        fun decode(id: String): InlayAction? = when {
            id.startsWith(OpenResourcePrefix) -> OpenResource(id.removePrefix(OpenResourcePrefix))
            id.startsWith(PickColorPrefix) -> decodePickColor(id.removePrefix(PickColorPrefix))
            else -> null
        }

        private fun decodePickColor(payload: String): PickColor? {
            val start = payload.substringBefore(':').toIntOrNull() ?: return null
            val rest = payload.substringAfter(':', "")
            val end = rest.substringBefore(':').toIntOrNull() ?: return null
            val literal = rest.substringAfter(':', "")
            if (literal.isEmpty()) return null
            return PickColor(start, end, literal)
        }
    }
}

/** Style hooks the IDE puts on its hints; a stylesheet keys on them to tell hints apart. */
object InlayTags {
    const val TYPE = "type-hint"
    const val PARAMETER = "parameter-hint"
    const val ACTION = "action-hint"

    /** The action targets a file the project owns and can edit. */
    const val PROJECT_TARGET = "project-target"

    /** The action targets a game resource, which opens read-only. */
    const val RESOURCE_TARGET = "resource-target"
    const val COLOR = "color-hint"
}

/** Icons the IDE draws inside its hints. */
object InlayIcons {
    const val OPEN_RESOURCE = "hollowengine:textures/gui/icons/link.svg"
}

/**
 * A hint drawn inline in the editor at [index]. [tags] are style hooks the stylesheet can
 * key on (`parameter-hint`, `type-hint`, …), and an [action] turns the hint into a button.
 */
data class InlayHint(
    val index: Int,
    val content: List<InlayContent>,
    val tags: List<String> = emptyList(),
    val action: InlayAction? = null,
) {
    constructor(index: Int, text: String, tags: List<String> = emptyList()) :
            this(index, listOf(InlayContent.Label(text)), tags)

    /** The hint's plain-text form; empty for hints drawn as icons only. */
    val text: String
        get() = content.filterIsInstance<InlayContent.Label>().joinToString("") { it.text }
}

data class TextLine(val spans: List<Pair<String, SpanStyle>>, val hints: ArrayList<InlayHint>)

data class SpanStyle(
    val color: TokenType,
    val italic: Boolean,
    val bold: Boolean,
    val highlight: Boolean,
    val colorOverride: Int? = null,
)
