package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParamTrait
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPinType
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.addons.narrative.items.StoryItemSyntax
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.onPress
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.lang

/** A registry a field names an entry of: its value is written as the commands of the game take it. */
enum class StoryRegistry {
    ITEM, BLOCK;

    /** Every entry, by id, with the stack that shows it. */
    fun all(): List<UiRegistryEntry> = when (this) {
        ITEM -> BuiltInRegistries.ITEM.entrySet()
            .map { (key, item) -> entry(key.location().toString(), ItemStack(item)) }

        BLOCK -> BuiltInRegistries.BLOCK.entrySet()
            .map { (key, block) -> entry(key.location().toString(), ItemStack(block.asItem())) }
    }.sortedBy { it.value }

    fun atHand(): List<UiRegistryEntry> {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return emptyList()
        val registries = minecraft.level?.registryAccess() ?: return emptyList()
        val carried = (listOf(player.mainHandItem) + player.inventory.items).filterNot { it.isEmpty }
        val entries = when (this) {
            ITEM -> carried.map { entry(StoryItemSyntax.of(it, registries), it) }
            BLOCK -> {
                val hit = minecraft.hitResult as? BlockHitResult
                val looked =
                    hit?.takeIf { it.type == HitResult.Type.BLOCK }?.let { minecraft.level?.getBlockState(it.blockPos) }
                        ?.let {
                            val stack = ItemStack(it.block.asItem())
                            UiRegistryEntry(
                                StoryItemSyntax.of(it),
                                "${storyLang("registry.looked")}: ${stack.hoverName.string}",
                                stack
                            )
                        }
                listOfNotNull(looked) + carried.mapNotNull { stack ->
                    val block = (stack.item as? BlockItem)?.block ?: return@mapNotNull null
                    entry(BuiltInRegistries.BLOCK.getKey(block).toString(), stack)
                }
            }
        }
        return entries.distinctBy { it.value }
    }

    private fun entry(value: String, stack: ItemStack) = UiRegistryEntry(value, stack.hoverName.string, stack)

    companion object {
        fun of(type: StoryPinType): StoryRegistry? = when (type) {
            StoryPinType.ITEM, StoryPinType.ITEM_FILTER -> ITEM
            StoryPinType.BLOCK -> BLOCK
            else -> null
        }
    }
}

/** What a field completes as it is typed: the ids of the registry its parameter names one of, or of its type. */
internal object StoryCompletions {
    fun of(param: StoryParam): UiCompletionContributor? =
        param.trait<StoryParamTrait.RegistryEntry>()?.let { entry ->
            ResourceLocation.tryParse(entry.registry)?.let { RegistryCompletions.of(it, entry.tags) }
        } ?: of(
            StoryPinType.of(param.type)
        )

    fun of(type: StoryPinType): UiCompletionContributor? = when (type) {
        StoryPinType.ITEM -> RegistryCompletions.plainItems
        StoryPinType.ITEM_FILTER -> RegistryCompletions.items
        StoryPinType.BLOCK -> RegistryCompletions.plainBlocks
        else -> null
    }
}

/**
 * Picks the value of [field] for [registry] in a window: out of what is at hand or out of the whole registry. A block
 * is set up with its state before it is taken.
 */
@Composable
internal fun RegistryButton(id: String, registry: StoryRegistry, field: StoryRowField) {
    var open by remember(id) { mutableStateOf(false) }
    Image(
        PICK_ICON,
        id = "st-registry-$id",
        tags = listOf("st-row-button"),
        modifier = Modifier.input(hoverable = true, clickable = true).tooltipOnHover(storyLang("registry.pick"))
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) open = true
                event.consume()
            },
    )
    if (!open) return
    val tabs = remember(registry) {
        listOf(
            UiRegistryTab("$PICKER_LANG.inventory".lang, registry.atHand()),
            UiRegistryTab("$PICKER_LANG.all".lang, registry.all()),
        )
    }
    RegistryPicker(
        id = "st-registry-picker-$id",
        title = storyLang("registry.pick"),
        tabs = tabs,
        onPick = field.write,
        onDismiss = { open = false },
        details = if (registry == StoryRegistry.BLOCK) {
            { value, write -> BlockStateFields("st-block-state-$id", value, write) }
        } else null,
    )
}

private const val PICK_ICON = "hollowengine:textures/gui/icons/search.svg"
private const val PICKER_LANG = "hollowengine.gui.registry_picker"
