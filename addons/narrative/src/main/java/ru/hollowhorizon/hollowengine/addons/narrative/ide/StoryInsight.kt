package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinFragmentAnalysis
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinFragmentContext
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinFragmentInsight
import ru.hollowhorizon.hollowengine.client.ui.ide.kotlinTokenHighlighter
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinTypeInfo

/** The colors analysis gave the Kotlin [text] of a field. */
class StoryFieldColors(val text: String, val highlights: List<UiTextHighlight>) {
    /**
     * How far into the analyzed [text] the [shown] text of its field starts, or null when the field shows something
     * else by now. A constant is shown without its package, `Mood.CALM` of `a.b.Mood.CALM`, and colored as its tail.
     */
    fun lead(shown: String): Int? {
        if (shown == text) return 0
        val lead = text.length - shown.length
        return lead.takeIf { shown.isNotEmpty() && lead > 0 && text.endsWith(shown) && text[lead - 1] == '.' }
    }
}

/**
 * What the Kotlin compiler says of a story.
 *
 * @param checks what is wrong with its nodes.
 * @param fields what it says of its fields, where they are written.
 * @param types what the outputs worked out on the spot are, by node and pin, as Kotlin writes them with classes in full.
 * @param fixed what each pin of a generic block is, by node and pin, once the type parameters it turns on are fixed.
 * @param written the type the compiler reads each type written in the story or by a command as, `Int` as `kotlin.Int`.
 * @param relations for each type the analysis saw, the ones among them a value of it goes into once it is not null.
 * @param revision moves on with each analysis.
 */
class StoryAnalysis(
    val checks: List<StoryCheck> = emptyList(),
    val fields: Map<StoryFieldKey, List<UiTextDiagnostic>> = emptyMap(),
    val colors: Map<StoryFieldKey, StoryFieldColors> = emptyMap(),
    val types: Map<Pair<String, String>, String> = emptyMap(),
    val fixed: Map<Pair<String, String>, String> = emptyMap(),
    val written: Map<String, String> = emptyMap(),
    val relations: Map<String, Set<String>> = emptyMap(),
    val revision: Long = 0L,
    val details: Map<String, KotlinTypeInfo> = emptyMap(),
) {
    /** The type [text] stands for when it says what fits it: one the analysis knows, other than `Any`. */
    fun known(text: String): String? = typeOf(text)?.takeUnless { it == ANY }

    /** Whether Kotlin lets a value of the type [from] go into a place of the type [into]; what it has not told, it lets. */
    fun takes(from: String, into: String): Boolean = fits(from, into) != false

    /**
     * Whether Kotlin lets a value of the type [from] go into a place of the type [into], subtypes and aliases as it
     * reads them. A value that can be null fits too, as links unwrap it. Null when the analysis cannot tell one of the two.
     */
    fun fits(from: String, into: String): Boolean? {
        val value = typeOf(from) ?: return null
        val place = typeOf(into) ?: return null
        return relations[value]?.contains(place)
    }

    /** What the compiler reads the type written as [text] as: one written in the story, or one it saw written in full. */
    fun typeOf(text: String): String? = written[text] ?: text.takeIf { it in relations }

    fun info(text: String?): KotlinTypeInfo? = text?.let { details[typeOf(it) ?: it] }

    fun pinType(text: String?): StoryPinType = StoryPinType.ofName(text, info(text))

    fun fieldForm(text: String?): StoryFieldForm = StoryFieldForm.ofName(text, info(text))

    /** Shared by existing wires and the menu for adding a node to a wire. */
    fun accepts(output: StoryPinType, from: String?, input: StoryPinType, into: String?): Boolean {
        if (output == StoryPinType.FLOW || input == StoryPinType.FLOW) return output == input
        if (!output.converts && from != null && into != null) fits(from, into)?.let { return it }
        return output.fits(input)
    }

    private companion object {
        const val ANY = "kotlin.Any"
    }
}

/**
 * The Kotlin analysis of stories. A story has one file, the script it compiles to with [StoryFieldChecks] added, and
 * all that fields and pins need comes out of the analysis of it. Fields keep their highlighter and completions for as
 * long as the story is open: a new highlighter would make them start coloring from nothing.
 */
object StoryInsight {
    private const val SCRIPT = ".kts"
    private const val RAW = "\"\"\""
    private const val IMPORT = "import "
    private const val DECLARATION = "var"
    private const val BODY_START = "\nsuspend fun storyField() {\n"
    private const val BODY_END = "\n}\n"
    private const val TYPE_START = "\nval storyType: "
    private const val TYPE_END = "\n"
    private val tokens = kotlinTokenHighlighter { StoryKotlin.syntax(it)?.tokens.orEmpty() }

    class FieldInsight(val highlighter: UiSyntaxHighlighter, val completions: UiCompletionContributor)

    /** What analysis has of the story in [state], which goes when the file closes. */
    class Session(val state: StoryEditorState, path: String) : AutoCloseable {
        val script = KotlinFragmentInsight(path + SCRIPT, StoryFieldChecks.PIN)
        val fields = HashMap<StoryFieldKey, FieldInsight>()
        var graph: StoryGraph? = null
        lateinit var story: GeneratedStory
        lateinit var checked: StoryCheckScript
        var found: KotlinFragmentAnalysis? = null

        // Field highlighters read this snapshot on Dispatchers.Default; graph analysis replaces it on the UI thread.
        @Volatile
        var analysis = StoryAnalysis()

        val types: UiCompletionContributor by lazy {
            script.completions({ KotlinFragmentContext(current().story.code + TYPE_START, TYPE_END) }) {
                addImport(
                    this, it
                )
            }
        }

        private var unknown: Set<StoryGenericKey> = emptySet()

        fun of(graph: StoryGraph) = apply {
            if (graph == this.graph) return@apply
            this.graph = graph
            story = StoryGraphCompiler.compile(graph, StoryActionFunctions)
            checked = StoryFieldChecks.script(graph, story.code, unknown)
        }

        fun inferred(unknown: Set<StoryGenericKey>): Boolean {
            val graph = graph ?: return false
            if (unknown == this.unknown) return false
            this.unknown = unknown
            checked = StoryFieldChecks.script(graph, story.code, unknown)
            return true
        }

        fun current() = of(state.root() ?: StoryGraph())

        override fun close() = script.close()
    }

    private fun session(state: StoryEditorState, graph: StoryGraph = state.root() ?: StoryGraph()): Session {
        val session = state.insight ?: Session(state, state.context?.file?.path.orEmpty()).also { state.insight = it }
        return session.of(graph)
    }

    private fun addImport(session: Session, name: String) =
        session.state.change { if (name in imports) this else copy(imports = imports + name) }

    /**
     * Colors [field] by what analysis made of it, or by the look of it until analysis gets to what it holds now; the
     * inside of a string, a [template], is looked at inside its quotes.
     */
    private fun colors(session: Session, field: StoryFieldKey, template: Boolean) = UiSyntaxHighlighter { text ->
        session.analysis.colors[field]?.let { colors ->
            colors.lead(text)?.let { lead -> part(colors.highlights, lead, lead + text.length) }
        } ?: if (template) templateColors(text) else tokens.highlight(text)
    }

    private fun templateColors(text: String): List<UiTextHighlight> {
        val framed = RAW + StoryTemplates.analyzed(text) + RAW
        return part(tokens.highlight(framed), RAW.length, RAW.length + text.length)
    }

    private fun part(highlights: List<UiTextHighlight>, start: Int, end: Int) =
        highlights.filter { it.end > start && it.start < end }
            .map { it.copy(start = it.start.coerceAtLeast(start) - start, end = it.end.coerceAtMost(end) - start) }

    /** Where the field completes: in its place in the script, or in a frame of its own while it cannot be there. */
    private fun context(session: Session, field: StoryFieldKey): KotlinFragmentContext {
        val checked = session.current().checked
        checked.span(field)
            ?.let { return KotlinFragmentContext(checked.text.substring(0, it.start), checked.text.substring(it.end)) }
        val frame = checked.frame(field)
        return KotlinFragmentContext(
            checked.text + BODY_START + frame?.before.orEmpty(), frame?.after.orEmpty() + BODY_END
        )
    }

    /** Colors the script a story generates, [code] being all of it. */
    fun script(state: StoryEditorState, code: String): UiCodeInsight {
        val session = session(state)
        val highlighter = UiSyntaxHighlighter { text ->
            session.script.analyzed(session.checked.text)?.let { part(it.highlights, 0, code.length) }
                ?: tokens.highlight(text)
        }
        return UiCodeInsight(highlighter = highlighter, revision = session.script.version)
    }

    /**
     * What a field of Kotlin holding [text] offers: colors, completion, and what is wrong with it.
     *
     * @param template whether the field holds the inside of a string.
     */
    fun field(state: StoryEditorState, field: StoryFieldKey, text: String, template: Boolean = false): UiCodeInsight {
        val session = session(state)
        val own = session.fields.getOrPut(field) {
            FieldInsight(
                colors(session, field, template),
                session.script.completions({ context(session, field) }) { addImport(session, it) })
        }
        val analysis = analysis(session)
        val lead = analysis.colors[field]?.lead(text) ?: 0
        return UiCodeInsight(
            highlighter = own.highlighter,
            completions = own.completions,
            diagnostics = analysis.fields[field].orEmpty().map { problem ->
                val start = (problem.start - lead).coerceIn(0, text.length)
                problem.copy(start = start, end = (problem.end - lead).coerceIn(start, text.length))
            },
            completionRevision = session.script.version,
            highlightRevision = analysis.revision,
        )
    }

    /** The type the Kotlin of [field] has to be, when it has one. */
    fun expected(state: StoryEditorState, field: StoryFieldKey): String? {
        val session = session(state)
        return session.checked.span(field)?.type
            ?: session.analysis.fixed[field.owner to field.key]?.let(StoryPinType::shortName)
    }

    /** What a field holding a type of Kotlin offers. */
    fun type(state: StoryEditorState): UiCodeInsight {
        val session = session(state)
        return UiCodeInsight(completions = session.types, completionRevision = session.script.version)
    }

    /** What the Kotlin compiler says of the story in [state], the whole file [graph], as analysis gets to it. */
    fun analysis(state: StoryEditorState, graph: StoryGraph): StoryAnalysis = analysis(session(state, graph))

    private fun analysis(session: Session): StoryAnalysis {
        val found = session.script.analyzed(session.checked.text) ?: return session.analysis
        if (found !== session.found && take(session, found)) session.script.analyzed(session.checked.text)
        return session.analysis
    }

    /**
     * Takes what analysis [found] in the script of [session] as what the compiler says of the story.
     *
     * @return whether individual parameter evidence changed, requiring analysis of the updated constraints.
     */
    fun take(session: Session, found: KotlinFragmentAnalysis): Boolean {
        session.found = found
        session.analysis = analyzed(session, found)
        return session.inferred(session.checked.unknown(found.properties))
    }

    private fun analyzed(session: Session, found: KotlinFragmentAnalysis): StoryAnalysis {
        val checks = ArrayList<StoryCheck>()
        val fields = HashMap<StoryFieldKey, MutableList<UiTextDiagnostic>>()
        val checked = session.checked
        found.diagnostics.forEach { problem ->
            val error = problem.severity == UiTextDiagnosticSeverity.ERROR
            val span = checked.at(problem.start)
            val block = checked.block(problem.start)
            when {
                span != null -> {
                    ofField(problem, span)?.let { fields.getOrPut(span.field) { ArrayList() } += it }
                    if (error) checks += StoryCheck(
                        span.field.owner.takeUnless { it.startsWith("variable:") }, problem.message
                    )
                }

                error && block != null -> checks += StoryCheck(block.node, problem.message)
                error && problem.start < checked.length -> ofScript(session, problem)?.let { checks += it }
            }
        }
        val colors = checked.spans.associate { span ->
            span.field to StoryFieldColors(
                span.shown, part(found.highlights, span.start, span.end)
            )
        }
        val properties = found.properties
        val written =
            checked.written.entries.mapNotNull { (name, text) -> properties.types[name]?.let { text to it } }.toMap()
        val fixed = checked.fixed.entries.mapNotNull { (name, pin) -> properties.types[name]?.let { pin to it } }.toMap()
        return StoryAnalysis(
            checks, fields, colors, types(session, found), fixed, written, properties.fits, session.analysis.revision + 1,
            properties.details,
        )
    }

    /**
     * [problem] in the terms of the field of [span]. What goes around a field is not its author's, so a warning there
     * means nothing, and an error there is about the field as a whole.
     */
    private fun ofField(problem: UiTextDiagnostic, span: StoryFieldSpan): UiTextDiagnostic? {
        val length = span.end - span.start
        val inside = problem.start >= span.start && problem.start <= span.end
        return when {
            inside -> problem.copy(
                start = problem.start - span.start,
                end = (problem.end - span.start).coerceIn(problem.start - span.start, length),
                fixes = emptyList(),
            )

            problem.severity == UiTextDiagnosticSeverity.ERROR -> problem.copy(
                start = 0, end = length, fixes = emptyList()
            )

            else -> null
        }
    }

    /** The pins analysis told the types of; pins left out of this analysis keep theirs until they are back in it. */
    private fun types(session: Session, found: KotlinFragmentAnalysis): Map<Pair<String, String>, String> {
        val pins = session.checked.pins
        val named = found.properties.types.entries.mapNotNull { (name, type) ->
            pins[name]?.takeUnless { found.properties.details[type]?.containsNothing == true }?.let { it to type }
        }
        return session.analysis.types.filterKeys { it !in pins.values } + named
    }

    /** A problem of the generated script, which says little while the compiler of graphs has problems of its own. */
    private fun ofScript(session: Session, problem: UiTextDiagnostic): StoryCheck? {
        val code = session.checked.text
        val start = problem.start.coerceAtMost(code.length)
        val line = code.substring(0, start).count { it == '\n' } + 1
        val text = code.substring(code.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1, start)
        if (session.story.problems.any { !it.warning }) return null
        return session.story.nodeAt(line)?.let { StoryCheck(it, problem.message) } ?: StoryCheck(
            null, problem.message
        ).takeIf { text.startsWith(IMPORT) || text.trim() == DECLARATION }
    }
}
