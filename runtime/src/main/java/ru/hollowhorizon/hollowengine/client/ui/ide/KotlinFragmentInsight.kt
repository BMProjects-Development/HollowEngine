package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContext
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiInlineStyle
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiSyntaxHighlighter
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextHighlight
import ru.hollowhorizon.hollowengine.client.ui.widgets.withColor
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinPropertyTypes
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinToken

/** What surrounds a fragment of Kotlin in the file it belongs to. */
data class KotlinFragmentContext(val before: String, val after: String)

/**
 * What analysis made of a whole file.
 *
 * @param properties what the properties it was asked about turned out to be.
 */
class KotlinFragmentAnalysis(
    val highlights: List<UiTextHighlight>,
    val diagnostics: List<UiTextDiagnostic>,
    val properties: KotlinPropertyTypes,
)

/** Colors text by what a parser made of it, which any number of fields can do at once. */
fun kotlinTokenHighlighter(tokens: (String) -> List<KotlinToken>) = UiSyntaxHighlighter { text ->
    tokens(text).map { UiTextHighlight(it.start, it.end, UiInlineStyle().withColor(it.type.toUiColor())) }
}

/**
 * Analyzes a file for fields holding Kotlin outside of an editor tab, keeping one analysis at a time.
 *
 * @param path the name analysis knows the text by; its extension picks the language.
 * @param typedProperties the start of the names of the properties to tell the types of.
 * @param placeProperties the start of the names of the properties to tell whether those fit.
 */
class KotlinFragmentInsight(path: String, typedProperties: String? = null, placeProperties: String? = null) : AutoCloseable {
    private var revision by mutableLongStateOf(0L)
    private val session: HollowIdeEditorSession = HollowIdeEditorSession(path, typedProperties, placeProperties) { revision = session.revision }

    /** Moves on with analysis, for telling a field to read it again. */
    val version: Long get() = revision

    /** What analysis made of the file [text], as soon as it has it. */
    fun analyzed(text: String): KotlinFragmentAnalysis? {
        revision
        return session.exactAnalysis(text)
    }

    /**
     * Completes a field holding a part of the file [context] gives.
     *
     * @param onImport adds a name to the imports, which a field that holds a part of the file has no place for.
     */
    fun completions(context: () -> KotlinFragmentContext, onImport: (String) -> Unit) = object : UiCompletionContributor {
        override val triggerCharacters: String get() = "."

        override fun complete(field: UiCompletionContext): List<UiTextCompletion> {
            val around = context()
            val text = around.before + field.text + around.after
            return session.completions.complete(field.copy(text = text, caret = around.before.length + field.caret)).map { item ->
                val name = item.importFqName ?: return@map item
                item.copy(importFqName = null, onAccept = { onImport(name) })
            }
        }
    }

    /** Ends analysis; what was found stays readable, and nothing new gets found. */
    override fun close() = session.close()
}
