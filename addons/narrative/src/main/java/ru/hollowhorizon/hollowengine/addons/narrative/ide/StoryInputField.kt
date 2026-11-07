package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFieldForm
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryRowField
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.DefaultUiFontSize
import ru.hollowhorizon.hollowengine.client.ui.widgets.*

/**
 * How a place draws a [StoryInputField].
 *
 * @param tag the style of the field.
 * @param numberTag the style of a number to drag, or none of its own.
 * @param codeTag the style it adds while the field holds Kotlin as it is.
 * @param fontSize the size of its text, or the default.
 * @param lines whether a field of several lines shows them all.
 */
internal class StoryFieldLook(
    val tag: String,
    val numberTag: String?,
    val codeTag: String?,
    val fontSize: Float?,
    val lines: Boolean,
) {
    companion object {
        val GRAPH = StoryFieldLook("gn-field", "gn-field", "gn-expression", null, true)
        val INSPECTOR = StoryFieldLook("insp-input", null, null, 9f, false)
    }
}

/**
 * The field of a value, which works the way its type is easiest to work with and always takes Kotlin. A number is
 * dragged while it is one, a click types into it as into any other field, and arithmetic typed into it, `20 * 60`,
 * turns into the number it comes to. A flag gets a checkbox while it is `true` or `false`. Values the field knows of,
 * and positions at hand for a vector, are a press of the arrow beside it away.
 *
 * @param label the name of the value, which a dragged number shows inside it and other fields before them.
 * @param height the height of a field of one line; [linesHeight] that of a field of several.
 * @param modifier what places the field.
 */
@Composable
internal fun StoryInputField(
    id: String,
    field: StoryRowField,
    insight: UiCodeInsight,
    readOnly: Boolean,
    problem: String?,
    wrong: Boolean,
    look: StoryFieldLook,
    label: String? = null,
    labelTags: List<String> = emptyList(),
    height: Float = DEFAULT_HEIGHT,
    linesHeight: Float = height,
    modifier: Modifier = Modifier,
) {
    val form = field.form
    val current by rememberUpdatedState(field)
    val focus = LocalUiFocusRequester.current
    var typing by remember(id) { mutableStateOf(false) }

    /** What was typed last, which a key pressed before the field has written it reads. */
    val typed = remember(id) { arrayOfNulls<String>(1) }

    /**
     * The number a drag has got to. Every write parses the story anew, so a drag writes once, as it ends, the way a
     * drag of nodes does; the field shows the number meanwhile.
     */
    val dragged = remember(id) { arrayOfNulls<Double>(1) }
    val dragging = remember(id) { booleanArrayOf(false) }
    val shown = field.value.ifBlank { field.placeholder }
    val state = (if (wrong) "error" else "warning").takeIf { problem != null }
    val tags = listOfNotNull(look.tag, state)
    val number = (form as? StoryFieldForm.Numeric)?.takeIf { !readOnly && !typing }?.read(shown.ifBlank { "0" })
    if (form is StoryFieldForm.Numeric && number != null) {
        NumberField(
            number,
            id = "$id-number",
            label = label,
            whole = form.whole,
            tags = listOfNotNull(look.numberTag, state),
            modifier = modifier.size(0.px, height.px).grow(1f).tooltipOnHover(problem.orEmpty()),
            onGestureStart = {
                dragging[0] = true
                dragged[0] = null
            },
            onGestureEnd = {
                dragging[0] = false
                dragged[0]?.let { current.write(form.write(it)) }
                dragged[0] = null
            },
            onEdit = {
                typing = true
                focus.request(id)
            },
        ) { next -> if (dragging[0]) dragged[0] = next else current.write(form.write(next)) }
        return
    }
    label?.let { Text(it, tags = labelTags) }
    if (form == StoryFieldForm.Flag && !readOnly && shown in FLAGS) Checkbox(
        checked = shown == TRUE,
        onCheckedChange = { field.write(it.toString()) },
        id = "$id-flag",
        tags = listOf("st-flag"),
    )
    val lines = look.lines && field.lines > 1

    fun settle() {
        val numeric = current.form as? StoryFieldForm.Numeric ?: return
        val text = typed[0] ?: current.value
        val number = numeric.read(text) ?: UiArithmetic.evaluate(text) ?: return
        numeric.write(number).takeIf { it != text }?.let(current.write)
    }
    TextField(
        value = field.value,
        onChange = {
            typed[0] = it
            current.write(it)
        },
        mode = if (lines) UiTextFieldMode.MULTI_LINE else UiTextFieldMode.SINGLE_LINE,
        readOnly = readOnly,
        placeholder = field.placeholder,
        insight = insight.copy(diagnosticTooltips = false),
        fontSize = look.fontSize ?: DefaultUiFontSize,
        id = id,
        tags = tags + listOfNotNull(look.codeTag.takeIf { field.code && form != StoryFieldForm.Template }),
        modifier = modifier.size(0.px, (if (lines) linesHeight else height).px).grow(1f)
            .tooltipOnHover(problem.orEmpty()).onFocus {
                typed[0] = null
                typing = true
            }.onUnfocus {
                typing = false
                settle()
            }.onKeyInput { input ->
                if (input.key == GLFW.GLFW_KEY_ENTER || input.key == GLFW.GLFW_KEY_KP_ENTER) settle()
            },
    )
    val vector = field.vector
    if ((field.choices.isNotEmpty() || vector != null) && !readOnly) StorySnippetsButton(id) {
        field.choices.map { value ->
            UiDropdownItem(value, mark = UiDropdownMark.RADIO, checked = value == field.value) { field.write(value) }
        } + vector?.let { vectorItems(it, field.write, separated = field.choices.isNotEmpty()) }.orEmpty()
    }
}

private const val DEFAULT_HEIGHT = 20f
private const val TRUE = "true"
private val FLAGS = setOf("", TRUE, "false")
