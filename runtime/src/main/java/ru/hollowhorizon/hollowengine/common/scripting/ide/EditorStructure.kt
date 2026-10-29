package ru.hollowhorizon.hollowengine.common.scripting.ide

/** Document offsets are always offsets in the original, unfolded editor text. */
data class EditorStructure(
    val folds: List<CodeFold> = emptyList(),
    val markers: List<GutterMarker> = emptyList(),
    val runTargets: List<ScriptRunTarget> = emptyList(),
)

/** [prefixLength] leaves an initial header visible, for example the `import` keyword. */
data class CodeFold(
    val start: Int,
    val end: Int,
    val placeholder: String = "{ … }",
    val prefixLength: Int = 0,
    val collapsedByDefault: Boolean = false,
)

/**
 * A [navigable] marker leads somewhere through [ScriptingAnalyzer.gutterTargets]; [icon] names its picture when
 * it shares one with another kind.
 */
enum class GutterMarkerKind(val navigable: Boolean, val icon: String? = null) {
    OVERRIDE(true),
    COROUTINE(false),
    EVENT(false),
    STAGE(false),
    CHECKPOINT(false),
    ACTION(false),
    ACTION_CALL(false, "action"),
    REWIND(true),
    REWIND_START(false, "rewind"),
    TEMPORARY(true),
    TEMPORARY_OPEN(false, "temporary"),
}

data class GutterNavigationTarget(val label: String, val location: DefinitionLocation)

/**
 * [args] fill in the tooltip of the kind, such as the name of a checkpoint. [color] tints the icon, as ARGB: the
 * checkpoints and the calls that lead to each share one.
 */
data class GutterMarker(val offset: Int, val kind: GutterMarkerKind, val args: List<String> = emptyList(), val color: Int? = null)

enum class ScriptRunKind { PLAIN, NODE }

data class ScriptRunTarget(val offset: Int, val kind: ScriptRunKind, val state: String? = null)
