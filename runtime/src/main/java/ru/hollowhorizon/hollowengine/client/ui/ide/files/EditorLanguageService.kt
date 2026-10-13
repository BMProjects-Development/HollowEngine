package ru.hollowhorizon.hollowengine.client.ui.ide.files

import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.story.StoryScriptingAnalyzer
import ru.hollowhorizon.hollowengine.common.scripting.ide.ui.HssScriptingAnalyzer

interface EditorLanguageService {
    val analyzer: ScriptingAnalyzer
}

fun EditorLanguageService(extension: String): EditorLanguageService {
    return when (extension) {
        "kt", "kts" -> KotlinEditorLanguageService
        "java" -> JavaEditorLanguageService
        "json" -> JsonEditorLanguageService
        "hss" -> HssEditorLanguageService
        "story" -> StoryEditorLanguageService
        else -> error("Unsupported language: $extension")
    }
}

object KotlinEditorLanguageService : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = ScriptingEnvironment.currentOrNull()?.analyzer ?: UnavailableKotlinScriptingAnalyzer
}

object JavaEditorLanguageService : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = ScriptingEnvironment.currentOrNull()?.analyzer?.javaAnalyzer ?: JavaScriptingAnalyzer
}

object PlainEditorLanguageService : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = PlainTextScriptingAnalyzer
}

object JsonEditorLanguageService : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = JsonScriptingAnalyzer
}

object HssEditorLanguageService : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = HssScriptingAnalyzer
}

object StoryEditorLanguageService : EditorLanguageService {
    override val analyzer: ScriptingAnalyzer
        get() = StoryScriptingAnalyzer
}

/**
 * A language an addon contributes to the editor through `registerIdeLanguage`. It takes precedence
 * over the built-in languages for the paths it [matches].
 */
class HollowIdeLanguageService(
    val id: String,
    private val matcher: (path: String) -> Boolean,
    private val analyzerProvider: () -> ScriptingAnalyzer,
) : EditorLanguageService {
    init {
        require(id.isNotBlank()) { "IDE language ID cannot be blank" }
    }

    override val analyzer: ScriptingAnalyzer
        get() = analyzerProvider()

    fun matches(path: String): Boolean = matcher(path)

    companion object {
        fun extensions(
            id: String,
            extensions: Collection<String>,
            analyzer: () -> ScriptingAnalyzer,
        ): HollowIdeLanguageService {
            val normalized = extensions.map { it.trim().removePrefix(".").lowercase() }
                .filter(String::isNotBlank)
                .distinct()
            require(normalized.isNotEmpty()) { "At least one language extension is required" }
            return HollowIdeLanguageService(
                id = id,
                matcher = { path -> pathExtension(path) in normalized },
                analyzerProvider = analyzer,
            )
        }
    }
}

internal fun pathExtension(path: String): String {
    val fileName = path.substringBefore('?').substringBefore('#').substringAfterLast('/')
    return fileName.substringAfterLast('.', "").lowercase()
}
