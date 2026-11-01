package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeInsight
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContext
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion

/** What surrounds a fragment of Kotlin in the file it belongs to. */
data class KotlinFragmentContext(val before: String, val after: String)

/**
 * Analyzes text outside of an editor tab, for fields holding Kotlin. It keeps one analysis at a time, so only give it
 * what the author is looking at: [completions] run for the focused field alone, [file] for one piece of text.
 *
 * @param path the name analysis knows the text by; its extension picks the language.
 */
class KotlinFragmentInsight(path: String) {
    private var revision by mutableLongStateOf(0L)
    private val session: HollowIdeEditorSession = HollowIdeEditorSession(path) { revision = session.revision }

    /** Highlights a whole file. */
    fun file(): UiCodeInsight = UiCodeInsight(highlighter = session.highlighter, revision = revision)

    /** Completes a field holding a part of the file [context] gives. */
    fun completions(context: () -> KotlinFragmentContext): UiCodeInsight {
        val completions = object : UiCompletionContributor {
            override val triggerCharacters: String get() = "."

            override fun complete(field: UiCompletionContext): List<UiTextCompletion> {
                val around = context()
                val text = around.before + field.text + around.after
                return session.completions.complete(field.copy(text = text, caret = around.before.length + field.caret))
            }
        }
        return UiCodeInsight(completions = completions, completionRevision = revision)
    }
}
