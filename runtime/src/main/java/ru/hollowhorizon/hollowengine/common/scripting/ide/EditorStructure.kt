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

enum class GutterMarkerKind { OVERRIDE, COROUTINE, EVENT }

data class GutterNavigationTarget(val label: String, val location: DefinitionLocation)

data class GutterMarker(
    val offset: Int,
    val kind: GutterMarkerKind,
    val targets: List<GutterNavigationTarget> = emptyList(),
)

enum class ScriptRunKind { PLAIN, NODE }

data class ScriptRunTarget(val offset: Int, val kind: ScriptRunKind, val state: String? = null)
