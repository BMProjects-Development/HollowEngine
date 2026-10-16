package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.inspector.TextRow
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton

internal const val AnimatorStylesheet = "hollowengine:ui/styles/animator-editor.hss"

@Composable
internal fun AnimatorIconButton(
    icon: String,
    tooltip: String,
    size: Float = 16f,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = IconButton(icon, tooltip, active = active, iconSize = size, modifier = modifier, onClick = onClick)

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
