package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.inspector.TextRow

internal const val AnimatorStylesheet = "hollowengine:ui/styles/animator-editor.hss"

/**
 * Field that holding an animation expression.
 */
@Composable
internal fun ExpressionField(label: String, value: String, id: String? = null, onChange: (String) -> Unit) = TextRow(
    label = label,
    value = value,
    id = id,
    completions = AnimationExpressionEditing.completions,
    highlighter = AnimationExpressionEditing.highlighter,
    diagnostics = AnimationExpressionEditing.diagnostics(value),
    onChange = onChange,
)
