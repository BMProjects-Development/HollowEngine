package ru.hollowhorizon.hollowengine.client.slots

import androidx.compose.runtime.*
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.common.slots.HOTBAR_SIZE
import ru.hollowhorizon.hollowengine.common.slots.PlayerSlotZones
import ru.hollowhorizon.hollowengine.common.ui.UiScope

/**
 * The slot session the enclosing [Slots] host bound, and the input controller shared by its slots.
 * Slots read both, which is why they must sit inside a host.
 */
private class SlotContext(val session: ClientSlotSession, val interaction: SlotInteraction)

private val LocalSlots = compositionLocalOf<SlotContext?> { null }

/**
 * Hosts the slots of the server session driving this UI.
 *
 * The wrapper earns its place by owning everything that is not about one slot: it is the focus scope that
 * receives `Q` while a text field elsewhere keeps typing focus, the surface whose empty area counts as
 * "outside the window" for dropping the cursor stack, and the layer the carried stack is drawn on.
 *
 * It fills the surface and centers [content], the shape a Minecraft container screen already has. Presses
 * that land on a slot are consumed there and never reach the host, so anything the host does see happened
 * outside the window.
 */
@Composable
fun UiScope.Slots(
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    dropOutside: Boolean = true,
    content: @Composable () -> Unit,
) {
    val session = sessionId?.let { ClientSlots[it] } ?: return
    val interaction = remember(session) { SlotInteraction(session) }
    val context = remember(session, interaction) { SlotContext(session, interaction) }

    // The vanilla tooltip is drawn outside composition, from the screen's post-render hook, so it needs
    // to be told which host is currently on screen.
    DisposableEffect(interaction) {
        SlotTooltips.bind(interaction)
        onDispose { SlotTooltips.unbind(interaction) }
    }

    Box(
        id = id,
        tags = tags,
        modifier = Modifier
            .size(UiLength.Fill, UiLength.Fill)
            .alignItems(UiAlign.CENTER, UiAlign.CENTER)
            .focusScope()
            .input(hoverable = true, clickable = true)
            .onKeyInput { key ->
                if (key.key != GLFW.GLFW_KEY_Q || key.repeat) return@onKeyInput
                interaction.dropHovered(all = key.control)
                key.consume()
            }
            .onPress { event -> if (dropOutside) interaction.dropOutside(event.button) }
            // A drag can end with the pointer outside every slot; the gesture still has to be closed out.
            .onRelease { interaction.release() }
            .then(modifier ?: Modifier),
    ) {
        CompositionLocalProvider(LocalSlots provides context) {
            // A window of its own so a press on the panel's own background stays a no-op rather than
            // reading as a press outside it.
            Box(modifier = Modifier.input(hoverable = true, clickable = true)) { content() }
            CarriedStack()
        }
    }
}

/** The outlines vanilla draws in an empty slot that holds one kind of thing, such as a helmet or a shield. */
object SlotIcons {
    const val HEAD = "minecraft:textures/item/empty_armor_slot_helmet.png"
    const val CHEST = "minecraft:textures/item/empty_armor_slot_chestplate.png"
    const val LEGS = "minecraft:textures/item/empty_armor_slot_leggings.png"
    const val FEET = "minecraft:textures/item/empty_armor_slot_boots.png"
    const val SHIELD = "minecraft:textures/item/empty_armor_slot_shield.png"

    /** Armor from the head down, as an armor zone lays it out. */
    val Armor = listOf(HEAD, CHEST, LEGS, FEET)

    /** Armor, then the main hand and the off hand, as the equipment of an entity lays it out. */
    val Equipment = Armor + listOf(null, SHIELD)
}

/**
 * One slot of [zone].
 *
 * An ordinary `Box` holding an [Item], so padding, size and hover effects are all reachable through HSS on
 * the `slot` and `slot-item` tags. [content] replaces the look while keeping the behavior; [placeholder] is the
 * texture shown while the slot is empty, one of [SlotIcons] for a slot of armor.
 */
@Composable
fun Slot(
    zone: String,
    index: Int,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    size: UiLength = DefaultSlotSize,
    placeholder: String? = null,
    content: (@Composable (ItemStack) -> Unit)? = null,
) {
    val context = LocalSlots.current ?: return
    val flat = context.session.layout.flatIndex(zone, index)
    if (flat < 0) return
    val interaction = context.interaction
    val stack = context.session[flat]

    DisposableEffect(flat) {
        onDispose { interaction.forget(flat) }
    }

    Box(
        id = id,
        tags = listOf("slot", "slot-$zone") + tags,
        modifier = Modifier
            .size(size, size)
            .alignItems(UiAlign.CENTER, UiAlign.CENTER)
            .input(hoverable = true, clickable = true, draggable = true)
            .onPlaced { rect -> interaction.place(flat, rect) }
            .onEnter { interaction.enter(flat) }
            .onExit { interaction.exit(flat) }
            .onPress { event -> interaction.press(flat, event.button, event.modifiers and GLFW.GLFW_MOD_SHIFT != 0) }
            .onDrag { event -> interaction.drag(event.x, event.y) }
            .onRelease { interaction.release() }
            .then(modifier ?: Modifier),
    ) {
        if (content != null) content(stack) else SlotContents(stack, placeholder)
    }
}

/**
 * Every slot of [zone] laid out in rows of [columns].
 *
 * A thin wrapper over [Slot]: the zone's size comes from the layout the server sent, so a screen never
 * restates how many slots an inventory has. [placeholders] are those of the slots by index.
 */
@Composable
fun SlotGrid(
    zone: String,
    columns: Int,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    gap: UiLength = DefaultSlotGap,
    slotSize: UiLength = DefaultSlotSize,
    range: IntRange? = null,
    placeholders: List<String?> = emptyList(),
) {
    require(columns > 0) { "A slot grid needs at least one column" }
    val context = LocalSlots.current ?: return
    val zoneLayout = context.session.layout.zone(zone) ?: return
    val indices = (range ?: 0 until zoneLayout.size).filter { it in 0 until zoneLayout.size }
    if (indices.isEmpty()) return

    Column(
        id = id,
        tags = listOf("slot-grid", "slot-grid-$zone") + tags,
        modifier = Modifier.gap(gap).then(modifier ?: Modifier),
    ) {
        indices.chunked(columns).forEach { row ->
            Row(modifier = Modifier.gap(gap)) {
                row.forEach { index -> Slot(zone, index, size = slotSize, placeholder = placeholders.getOrNull(index)) }
            }
        }
    }
}

/**
 * The player's own inventory in its familiar shape: three rows, a wider gap, then the hotbar, plus armor
 * and off-hand columns when the server declared those zones.
 *
 * The storage zone is a single zone on the server (see
 * [ru.hollowhorizon.hollowengine.common.slots.playerZones]) because quick-move should treat it as one.
 * The split into rows is purely visual, so this draws ranges of that zone rather than asking for separate
 * ones.
 */
@Composable
fun PlayerInventory(
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    gap: UiLength = DefaultSlotGap,
    slotSize: UiLength = DefaultSlotSize,
    rowGap: UiLength = DefaultHotbarGap,
) {
    val context = LocalSlots.current ?: return
    val layout = context.session.layout
    val storage = layout.zone(PlayerSlotZones.STORAGE)

    Row(
        id = id,
        tags = listOf("player-inventory") + tags,
        modifier = Modifier.gap(gap).alignItems(UiAlign.START, UiAlign.START).then(modifier ?: Modifier),
    ) {
        if (layout.zone(PlayerSlotZones.ARMOR) != null) {
            SlotGrid(PlayerSlotZones.ARMOR, columns = 1, gap = gap, slotSize = slotSize, placeholders = SlotIcons.Armor)
        }
        if (storage != null) {
            Column(modifier = Modifier.gap(rowGap)) {
                SlotGrid(
                    PlayerSlotZones.STORAGE, columns = HOTBAR_SIZE, gap = gap, slotSize = slotSize,
                    range = HOTBAR_SIZE until storage.size,
                )
                SlotGrid(
                    PlayerSlotZones.STORAGE, columns = HOTBAR_SIZE, gap = gap, slotSize = slotSize,
                    range = 0 until HOTBAR_SIZE,
                )
            }
        }
        if (layout.zone(PlayerSlotZones.OFFHAND) != null) {
            SlotGrid(
                PlayerSlotZones.OFFHAND, columns = 1, gap = gap, slotSize = slotSize, placeholders = listOf(SlotIcons.SHIELD),
            )
        }
    }
}

/**
 * The item itself. Count, durability and cooldown are not nodes: the renderer draws vanilla's own item
 * decorations, so an item here carries the same badges it does in a vanilla slot.
 */
@Composable
private fun SlotContents(stack: ItemStack, placeholder: String?) {
    if (stack.isEmpty) {
        if (placeholder != null) {
            Image(placeholder, tags = listOf("slot-placeholder"), modifier = Modifier.size(ItemSize, ItemSize).inputTransparent())
        }
        return
    }
    Item(stack, tags = listOf("slot-item"), modifier = Modifier.size(ItemSize, ItemSize).inputTransparent())
}

/**
 * The stack on the cursor, following the pointer above everything else.
 *
 * An overlay rather than a child of the window: a positioned child still counts toward the size of a parent that
 * fits its content, so a stack dragged past the edge of the slots would stretch them. Input-transparent, or it
 * would shadow the slot under the pointer and make every click miss.
 */
@Composable
private fun CarriedStack() {
    val context = LocalSlots.current ?: return
    val carried = context.session.carried
    if (carried.isEmpty) return
    val pointer = LocalPointer.current
    if (!pointer.isKnown) return

    Popup(
        anchorBounds = UiRect(pointer.x, pointer.y, 0f, 0f),
        alignment = CarriedAlignment,
        layer = OverlayLayer,
        tags = listOf("slot-carried"),
        modifier = Modifier
            .size(DefaultSlotSize, DefaultSlotSize)
            .alignItems(UiAlign.CENTER, UiAlign.CENTER)
            .inputTransparent(),
        dismissOnOutside = false,
        animated = false,
    ) {
        Item(carried, tags = listOf("slot-item"), modifier = Modifier.size(ItemSize, ItemSize).inputTransparent())
    }
}

private const val CarriedHalfSize = 9f
private val CarriedAlignment = UiPopupAlignment(
    anchorVertical = UiAlign.START,
    offsetX = -CarriedHalfSize,
    offsetY = -CarriedHalfSize,
)
private val DefaultSlotSize: UiLength = 18.px
private val DefaultSlotGap: UiLength = 2.px
private val DefaultHotbarGap: UiLength = 6.px
private val ItemSize: UiLength = 16.px
