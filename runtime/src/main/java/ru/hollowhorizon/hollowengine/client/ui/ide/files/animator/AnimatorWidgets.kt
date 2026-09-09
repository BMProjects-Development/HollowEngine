package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiSyntaxHighlighter
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

internal const val AnimatorStylesheet = "hollowengine:ui/styles/animator-editor.hss"

/**
 * A number that springs towards its target instead of jumping there.
 */
internal class SpringFloat(initial: Float) {
    var value by mutableStateOf(initial)
        private set

    var target: Float = initial

    private var velocity = 0f
    private var lastFrame = 0L

    /** Puts the value there at once, for changes the pointer is already animating by hand. */
    fun snapTo(next: Float) {
        target = next
        value = next
        velocity = 0f
    }

    fun advance(frameNanos: Long) {
        val previous = lastFrame
        lastFrame = frameNanos
        if (previous == 0L) return

        val delta = target - value
        if (kotlin.math.abs(delta) < 0.05f && kotlin.math.abs(velocity) < 0.05f) {
            value = target
            velocity = 0f
            return
        }

        val dt = ((frameNanos - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
        velocity += (delta * 300f - velocity * 32f) * dt
        value += velocity * dt
    }
}

@Composable
internal fun AnimatorButton(
    label: String,
    modifier: Modifier = Modifier,
    color: UiColor = AnimatorColors.Text,
    onClick: () -> Unit,
) {
    Box(
        mode = UiBoxMode.STACK,
        tags = listOf("animator-button"),
        modifier = modifier
            .input(hoverable = true, clickable = true)
            .onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    ) {
        Text(label, tags = listOf("animator-button-label"), modifier = Modifier.foreground(color))
    }
}

@Composable
internal fun AnimatorIconButton(
    icon: String,
    tooltip: String,
    size: Float = 16f,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Image(
        icon,
        tags = if (active) listOf("animator-icon-button", "active") else listOf("animator-icon-button"),
        modifier = modifier
            .size((size + 6f).px, (size + 6f).px)
            .input(hoverable = true, clickable = true)
            .tooltipOnHover(tooltip)
            .onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    )
}

/**
 * A row of choices that wraps by itself.
 */
@Composable
internal fun AnimatorPillFlow(content: HollowUiContent) {
    Layout(
        content = content,
        modifier = Modifier.size(100.percent, UiLength.Fit).gap(3.px).lineSpacing(3f).textWrap(),
        measurePolicy = UiMeasurePolicies.InlineFlow,
    )
}

/** One choice out of a small set, the shape the editor uses instead of a dropdown. */
@Composable
internal fun AnimatorPill(label: String, active: Boolean, onClick: () -> Unit) {
    InlineWidget(
        id = "animator-pill-$label",
        tags = listOf("animator-pill") + if (active) listOf("active") else emptyList(),
        modifier = Modifier
            .input(hoverable = true, clickable = true)
            .onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    ) {
        Text(label, tags = listOf("animator-pill-label"))
    }
}

internal const val FieldHeight = 22f

@Composable
internal fun <T> PillRows(
    values: List<T>,
    current: T,
    label: (T) -> String,
    onChange: (T) -> Unit,
) {
    AnimatorPillFlow {
        values.forEach { value ->
            AnimatorPill(label(value), value == current) { onChange(value) }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.size(100.percent).gap(4.px)) {
        Text(title, modifier = Modifier.fontSize(10f).foreground(AnimatorColors.Accent))
        Box(modifier = Modifier.size(100.percent, 1.px).background(AnimatorColors.Border))
        content()
    }
}

@Composable
internal fun Label(text: String) {
    Text(text, modifier = Modifier.fontSize(9f).foreground(AnimatorColors.Muted))
}

@Composable
internal fun Readonly(label: String, value: String) {
    Row(modifier = Modifier.size(100.percent, 16.px).gap(6.px).alignItems(vertical = UiAlign.CENTER)) {
        Text(label, modifier = Modifier.fontSize(9f).foreground(AnimatorColors.Muted).grow(1f))
        Text(value, modifier = Modifier.fontSize(9f).foreground(AnimatorColors.Text))
    }
}

@Composable
internal fun TextRow(
    label: String,
    value: String,
    completions: UiCompletionContributor? = null,
    highlighter: UiSyntaxHighlighter? = null,
    diagnostics: List<UiTextDiagnostic> = emptyList(),
    filter: UiTextInputFilter = UiTextInputFilter.ANY,
    onChange: (String) -> Unit,
) {
    Label(label)
    TextField(
        value = value,
        filter = filter,
        completionContributor = completions,
        syntaxHighlighter = highlighter,
        diagnostics = diagnostics,
        fontSize = 9f,
        onChange = onChange,
        modifier = Modifier
            .size(100.percent, FieldHeight.px)
            .background(AnimatorColors.Canvas)
            .border(1.px, AnimatorColors.Border, 3f)
            .borderRadius(3f)
            .padding(4.px),
    )
}

@Composable
internal fun NameRow(label: String, value: String, onCommit: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    TextRow(label, draft) { next ->
        draft = next
        val trimmed = next.trim()
        if (trimmed.isNotEmpty() && trimmed != value) onCommit(trimmed)
    }
}

@Composable
internal fun ExpressionField(label: String, value: String, onChange: (String) -> Unit) =
    TextRow(
        label = label,
        value = value,
        completions = AnimationExpressionCompletions,
        highlighter = AnimationExpressionHighlighter,
        diagnostics = animationExpressionDiagnostics(value),
        onChange = onChange,
    )

@Composable
internal fun IntField(label: String, value: Int, onChange: (Int) -> Unit) =
    TextRow(label, value.toString(), filter = UiTextInputFilter.INTEGER) { text ->
        text.toIntOrNull()?.let(onChange)
    }

@Composable
internal fun FloatField(label: String, value: Float, onChange: (Float) -> Unit) =
    TextRow(label, value.toString(), filter = UiTextInputFilter.DECIMAL) { text ->
        text.toFloatOrNull()?.let(onChange)
    }

@Composable
internal fun Hint(text: String) {
    Text(text, modifier = Modifier.fontSize(9f).foreground(AnimatorColors.Muted))
}
