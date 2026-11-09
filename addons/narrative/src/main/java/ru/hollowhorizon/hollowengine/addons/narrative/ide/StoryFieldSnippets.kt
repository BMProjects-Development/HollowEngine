package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLiterals
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import java.math.BigDecimal
import java.math.RoundingMode

private const val MenuIcon = "hollowengine:textures/gui/icons/graph/arrow.svg"
private const val SnippetStylesheet = "hollowengine-narrative:ui/styles/story-graph.hss"
private const val LookReach = 96.0
private const val Decimals = 2

/** The arrow beside a field, with a menu of what can be put in it at once, [items] made when it opens. */
@Composable
internal fun StorySnippetsButton(id: String, items: () -> List<UiDropdownItem>) {
    var anchor by remember { mutableStateOf(UiRect.Zero) }
    var open by remember { mutableStateOf(false) }
    Image(
        MenuIcon,
        id = "$id-snippets",
        tags = listOf("st-row-button", "st-snippets"),
        modifier = Modifier.style(SnippetStylesheet).input(hoverable = true, clickable = true).onShown { anchor = it }
            .tooltipOnHover(storyLang("snippets.open"))
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) open = true
                event.consume()
            },
    )
    if (open) ContextMenu("$id-snippets-popup", anchor, items(), onExpandedChange = { open = it })
}

/**
 * What can be put in a vector written by [helper] at once: where the author stands and looks, where the player of the
 * story will be, and zero.
 *
 * @param separated whether the first of them starts a part of a menu of its own.
 */
internal fun vectorItems(helper: String, write: (String) -> Unit, separated: Boolean = false): List<UiDropdownItem> {
    val player = Minecraft.getInstance().player
    fun place(name: String, at: Vec3?, separator: Boolean = false) =
        UiDropdownItem(storyLang("vector.$name"), enabled = at != null, separatorBefore = separator) {
            at?.let { write(written(it, helper)) }
        }
    val look = player?.pick(LookReach, 0f, false)?.takeIf { it.type != HitResult.Type.MISS }?.location
    val live = if (helper == StoryLiterals.BLOCK) "storyPlayer().blockPosition()" else "storyPlayer().position()"
    return listOf(
        place("position", player?.position(), separated),
        place("look", look),
        UiDropdownItem(storyLang("vector.story_player"), separatorBefore = true) { write(live) },
        UiDropdownItem(storyLang("vector.zero")) { write(StoryLiterals.vector(listOf("0", "0", "0"), helper)) },
    )
}

/** The Kotlin of the vector [at], whole blocks for [StoryLiterals.BLOCK]. */
private fun written(at: Vec3, helper: String): String = StoryLiterals.vector(listOf(at.x, at.y, at.z).map { value ->
    if (helper == StoryLiterals.BLOCK) Math.floor(value).toInt().toString()
    else BigDecimal(value).setScale(Decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}, helper)
