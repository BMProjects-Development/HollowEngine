package ru.hollowhorizon.hollowengine.addons.narrative.scene.lang

/** Half-open character range inside a story source, plus the 0-based line it starts on. */
data class StorySpan(val start: Int, val end: Int, val line: Int) {
    init {
        require(end >= start) { "Span end $end before start $start" }
    }
}

enum class StorySeverity { ERROR, WARNING }

data class StoryDiagnostic(
    val severity: StorySeverity,
    val message: String,
    val span: StorySpan,
) {
    override fun toString() = "${severity.name.lowercase()}@${span.line + 1}: $message"
}

class StoryDiagnostics {
    private val list = mutableListOf<StoryDiagnostic>()

    val all: List<StoryDiagnostic> get() = list
    val errors: List<StoryDiagnostic> get() = list.filter { it.severity == StorySeverity.ERROR }
    val hasErrors: Boolean get() = list.any { it.severity == StorySeverity.ERROR }

    fun error(message: String, span: StorySpan) {
        list += StoryDiagnostic(StorySeverity.ERROR, message, span)
    }

    fun warning(message: String, span: StorySpan) {
        list += StoryDiagnostic(StorySeverity.WARNING, message, span)
    }
}
