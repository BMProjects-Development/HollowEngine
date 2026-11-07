package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import androidx.compose.runtime.Composable
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorState
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.client.slots.SlotIcons
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

private const val SLOT_ICON = "hollowengine:textures/gui/icons/box.svg"
private const val ROW = 9
private const val HOTBAR = 0
private const val MAIN = 9
private const val ARMOR = 36
private const val OFFHAND = 40

/** Opens the picture of the inventory that picks the slot [field] holds. */
@Composable
internal fun SlotButton(state: StoryEditorState, id: String, field: StoryRowField) {
    Image(
        SLOT_ICON,
        id = "st-slot-$id",
        tags = listOf("st-row-button"),
        modifier = Modifier.input(hoverable = true, clickable = true).tooltipOnHover(storyLang("slot.pick"))
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) state.slotField = field
                event.consume()
            },
    )
}

/**
 * The inventory of a player laid out as the game shows it, armor and the second hand above the three rows of it and
 * the hotbar below; a slot shows what the player of the editor holds there and writes its number into [field].
 */
@Composable
internal fun StorySlotDialog(state: StoryEditorState, field: StoryRowField) {
    val picked = field.value.trim().toIntOrNull()
    val inventory = Minecraft.getInstance().player?.inventory

    @Composable
    fun Slot(index: Int, placeholder: String? = null) {
        val stack = inventory?.getItem(index)?.takeUnless { it.isEmpty }
        Box(
            id = "story-slot-$index",
            tags = listOfNotNull("story-slot", "selected".takeIf { index == picked }),
            modifier = Modifier.input(hoverable = true, clickable = true)
                .tooltipOnHover(stack?.let { "$index: ${it.hoverName.string}" } ?: index.toString()).onPress { event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        field.write(index.toString())
                        state.slotField = null
                    }
                    event.consume()
                },
        ) {
            if (stack != null) {
                Item(stack, tags = listOf("story-slot-item"))
            } else if (placeholder != null) {
                Image(placeholder, tags = listOf("story-slot-item", "story-slot-placeholder"))
            }
        }
    }

    @Composable
    fun Slots(range: IntRange) = Row(tags = listOf("story-slot-row")) { range.forEach { Slot(it) } }

    Popup(
        anchorBounds = LocalUiViewport.current,
        alignment = UiPopupAlignment.Centered,
        id = "story-slot-dialog",
        tags = listOf("dropdown-popup", "story-slot-dialog"),
        layer = 100,
        modal = true,
        onDismiss = { state.slotField = null },
    ) {
        Column(tags = listOf("story-slot-grid")) {
            Text(storyLang("slot.equipment"), tags = listOf("story-slot-title"))
            Row(tags = listOf("story-slot-row")) {
                (ARMOR + 3 downTo ARMOR).zip(SlotIcons.Armor).forEach { (index, icon) -> Slot(index, icon) }
                Box(tags = listOf("story-slot-gap"))
                Slot(OFFHAND, SlotIcons.SHIELD)
            }
            Text(storyLang("slot.inventory"), tags = listOf("story-slot-title"))
            (0 until 3).forEach { row -> Slots(MAIN + row * ROW until MAIN + (row + 1) * ROW) }
            Text(storyLang("slot.hotbar"), tags = listOf("story-slot-title"))
            Slots(HOTBAR until HOTBAR + ROW)
        }
    }
}
