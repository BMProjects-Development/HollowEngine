package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLiterals
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.DefaultUiFontSize
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import java.math.BigDecimal
import java.math.RoundingMode

private const val MenuIcon = "hollowengine:textures/gui/icons/graph/arrow.svg"
private const val VectorStylesheet = "hollowengine-narrative:ui/styles/story-graph.hss"
private const val LookReach = 96.0
private const val Decimals = 2
private const val BlockHelper = "blockPos"
private val Axes = listOf("X", "Y", "Z")

/**
 * A vector as one field per coordinate, with a menu that fills them from where the author stands and looks. A coordinate
 * can be any expression; a vector that is not made of coordinates shows as a single expression.
 *
 * @param expression the Kotlin of the vector, written by [helper].
 * @param fieldTags the style of its fields.
 * @param write gets the Kotlin of the vector.
 */
@Composable
internal fun StoryVectorField(
    id: String,
    expression: String,
    helper: String,
    readOnly: Boolean,
    problem: String?,
    fieldTags: List<String>,
    modifier: Modifier = Modifier,
    fontSize: Float? = null,
    write: (String) -> Unit,
) {
    val parts = StoryLiterals.vectorParts(expression, helper)
    val tags = fieldTags + listOfNotNull("error".takeIf { problem != null })
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    var open by remember { mutableStateOf(false) }

    @Composable
    fun Field(value: String, name: String, placeholder: String, change: (String) -> Unit) = TextField(
        value = value,
        onChange = change,
        readOnly = readOnly,
        placeholder = placeholder,
        fontSize = fontSize ?: DefaultUiFontSize,
        id = "$id-$name",
        tags = tags,
        modifier = Modifier.size(0.px, 20.px).grow(1f).tooltipOnHover(problem.orEmpty()),
    )

    Row(tags = listOf("st-vector"), modifier = modifier.style(VectorStylesheet).alignItems(vertical = UiAlign.CENTER)) {
        if (parts == null) Field(expression, "expression", "", write)
        else parts.forEachIndexed { index, part ->
            Field(part, Axes[index].lowercase(), Axes[index]) { typed ->
                write(StoryLiterals.vector(parts.mapIndexed { at, it -> if (at == index) typed else it }, helper))
            }
        }
        Image(
            MenuIcon,
            id = "$id-menu",
            tags = listOf("st-row-button", "st-vector-menu"),
            modifier = Modifier.input(hoverable = true, clickable = true).onShown { anchor = it }.onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !readOnly) open = true
                event.consume()
            },
        )
    }
    if (open) ContextMenu("$id-popup", anchor, vectorItems(helper, write), onExpandedChange = { open = it })
}

/** What can be put in a vector at once: where the author is now, or where the player of the story will be. */
private fun vectorItems(helper: String, write: (String) -> Unit): List<UiDropdownItem> {
    val player = Minecraft.getInstance().player
    fun place(name: String, at: () -> Vec3?) = UiDropdownItem(storyLang("vector.$name"), enabled = player != null) {
        at()?.let { write(StoryLiterals.vector(coordinates(it, helper), helper)) }
    }
    val live = if (helper == BlockHelper) "storyPlayer().blockPosition()" else "storyPlayer().position()"
    return listOf(
        place("position") { player?.position() },
        place("look") { player?.pick(LookReach, 0f, false)?.location },
        UiDropdownItem(storyLang("vector.story_player"), separatorBefore = true) { write(live) },
        UiDropdownItem(storyLang("vector.zero")) { write(StoryLiterals.vector(listOf("0", "0", "0"), helper)) },
    )
}

private fun coordinates(at: Vec3, helper: String): List<String> = listOf(at.x, at.y, at.z).map { value ->
    if (helper == BlockHelper) Math.floor(value).toInt().toString()
    else BigDecimal(value).setScale(Decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
