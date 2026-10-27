package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryEdits
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryLineKind
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.inspector.NumberRow
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter

/**
 * How the rows of a command look when its action names this widget with `@EditorWidget`.
 *
 * @param tag the style tag of its rows, in place of the one every command has.
 * @param row the fields of its row.
 * @param inspector what the inspector shows for it.
 */
internal class StoryCommandWidget(
    val tag: String,
    val row: @Composable (scope: StoryRowScope, row: StoryRow, call: StoryLineKind.FuncCall) -> Unit,
    val inspector: @Composable (state: StoryListState, model: StoryListModel, call: StoryLineKind.FuncCall, line: Int) -> Unit,
)

/** The widgets commands can be drawn with, by the id their actions name them with. */
internal object StoryCommandWidgets {
    const val WAIT = "hollowengine-narrative:wait"

    private val widgets = HashMap<String, StoryCommandWidget>()

    init {
        register(WAIT, StoryCommandWidget("wait", { scope, row, call -> WaitRow(scope, row, call) }, ::WaitInspector))
    }

    fun register(id: String, widget: StoryCommandWidget) {
        widgets[id] = widget
    }

    /** The widget of [call], or null for a command drawn as a field per parameter. */
    fun of(call: StoryLineKind.FuncCall): StoryCommandWidget? = signature(call)?.widget?.let(widgets::get)
}

@Composable
private fun WaitRow(scope: StoryRowScope, row: StoryRow, wait: StoryLineKind.FuncCall) {
    val model = scope.model
    val written = wait.args.firstOrNull()?.let { model.source(it.expr.span) }.orEmpty()
    val seconds = WaitDuration.seconds(written)
    if (seconds == null) {
        Field(scope, row, "arg-0", written, "2s", width = 80f) { StoryEdits.setPositional(model.text, row.line, 0, it) }
        Box(modifier = Modifier.grow(1f))
        return
    }
    Field(
        scope,
        row,
        "arg-0",
        WaitDuration.write(seconds).removeSuffix("s"),
        "1",
        width = 54f,
        filter = UiTextInputFilter.DECIMAL
    ) {
        val value = it.toFloatOrNull() ?: return@Field null
        StoryEdits.setPositional(model.text, row.line, 0, WaitDuration.write(value))
    }
    Text(storyLang("wait.unit"), tags = listOf("story-muted"), modifier = Modifier.grow(1f))
}

@Composable
private fun WaitInspector(state: StoryListState, model: StoryListModel, wait: StoryLineKind.FuncCall, line: Int) {
    val written = wait.args.firstOrNull()?.let { model.source(it.expr.span) }.orEmpty()
    val seconds = WaitDuration.seconds(written) ?: return StatementRow(state, model, line)
    NumberRow(storyLang("wait.duration"), seconds, id = "story-inspector-wait", min = 0f, step = 0.1f) {
        state.write(StoryEdits.setPositional(model.text, line, 0, WaitDuration.write(it)))
    }
}
