package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLiterals
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphEditorController
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

private const val CodeIcon = "hollowengine:textures/gui/icons/code_editor.svg"
private const val ArrowIcon = "hollowengine:textures/gui/icons/arrow.svg"

/** Opens [field] of [owner] in the full editor. */
@Composable
internal fun CodeEditorButton(state: StoryEditorState, owner: String, title: String, field: StoryRowField) {
    Image(
        CodeIcon,
        id = "st-code-$owner-${field.key}",
        tags = listOf("st-row-button"),
        modifier = Modifier.input(hoverable = true, clickable = true).tooltipOnHover(storyLang("code.open"))
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    state.codeField = StoryCodeField(StoryFieldKey(owner, field.key), title, field.value, field.write)
                }
                event.consume()
            },
    )
}

/** Picks which of the known values the list of [field] holds, staying open for as many as are wanted. */
@Composable
internal fun PicksButton(controller: GraphEditorController, id: String, field: StoryRowField) {
    fun open(x: Float, y: Float, held: List<String>) {
        controller.menu = GraphMenu(x, y, field.picks.map { value ->
            UiDropdownItem(value, mark = UiMenuParts.check(value in held), closeOnClick = false) {
                val next = if (value in held) held - value else held + value
                field.write(StoryLiterals.list(next))
                open(x, y, next)
            }
        })
    }
    Box(
        id = "st-picks-$id",
        tags = listOf("st-picks"),
        modifier = Modifier.input(hoverable = true, clickable = true).tooltipOnHover(storyLang("picks.open"))
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) open(
                    event.x,
                    event.y,
                    StoryLiterals.listed(field.value)
                )
                event.consume()
            },
    ) {
        Image(ArrowIcon, tags = listOf("st-picks-arrow"))
    }
}

/** Shows the color of [field] and opens the picker of it. */
@Composable
internal fun ColorButton(state: StoryEditorState, id: String, field: StoryRowField) {
    Box(
        id = "st-color-$id",
        tags = listOf("st-color"),
        modifier = Modifier.background(storyColor(field.value)).input(hoverable = true, clickable = true)
            .tooltipOnHover(storyLang("color.pick")).onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) state.colorField = field
                event.consume()
            },
    )
}
