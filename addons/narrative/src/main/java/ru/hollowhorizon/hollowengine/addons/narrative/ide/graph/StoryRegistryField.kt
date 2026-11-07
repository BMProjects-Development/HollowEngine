package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import androidx.compose.runtime.Composable
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPinType
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang
import ru.hollowhorizon.hollowengine.addons.narrative.items.StoryItemSyntax
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphEditorController
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphMenu
import ru.hollowhorizon.hollowengine.client.ui.input
import ru.hollowhorizon.hollowengine.client.ui.onPress
import ru.hollowhorizon.hollowengine.client.ui.widgets.RegistryCompletions
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

/** A registry a field names an entry of: its value is written as the commands of the game take it. */
internal enum class StoryRegistry {
    ITEM, BLOCK;

    /** The entries by id with the stack that shows each, sorted by id. */
    fun entries(): List<Pair<String, ItemStack>> = when (this) {
        ITEM -> BuiltInRegistries.ITEM.entrySet().map { (key, item) -> key.location().toString() to ItemStack(item) }
        BLOCK -> BuiltInRegistries.BLOCK.entrySet()
            .map { (key, block) -> key.location().toString() to ItemStack(block.asItem()) }
    }.sortedBy { it.first }

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
        param.registry?.let(ResourceLocation::tryParse)?.let { RegistryCompletions.of(it, param.tags) } ?: of(
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
 * Picks the value of [field] out of what is at hand, for [registry]: the item held or one of the inventory, the block
 * looked at, or any entry of the registry by name.
 */
@Composable
internal fun RegistryButton(
    controller: GraphEditorController,
    id: String,
    registry: StoryRegistry,
    field: StoryRowField,
) {
    Image(
        PICK_ICON,
        id = "st-registry-$id",
        tags = listOf("st-row-button"),
        modifier = Modifier.input(hoverable = true, clickable = true).tooltipOnHover(storyLang("registry.pick"))
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    controller.menu = GraphMenu(event.x, event.y, registryItems(registry, field.write), search = true)
                }
                event.consume()
            },
    )
}

private fun registryItems(registry: StoryRegistry, write: (String) -> Unit): List<UiDropdownItem> {
    val minecraft = Minecraft.getInstance()
    val player = minecraft.player
    val registries = minecraft.level?.registryAccess()
    val near = buildList {
        val held = player?.mainHandItem?.takeUnless { it.isEmpty }
        when (registry) {
            StoryRegistry.ITEM -> {
                if (held != null && registries != null) {
                    add(UiDropdownItem(storyLang("registry.held"), item = held) {
                        write(
                            StoryItemSyntax.of(
                                held,
                                registries
                            )
                        )
                    })
                }
                val stacks = player?.inventory?.items.orEmpty().filterNot { it.isEmpty }
                if (stacks.isNotEmpty() && registries != null) {
                    val written = stacks.associateBy { StoryItemSyntax.of(it, registries) }
                    add(UiDropdownItem(storyLang("registry.inventory"), children = written.map { (syntax, stack) ->
                        UiDropdownItem(stack.hoverName.string, item = stack, shortcut = syntax) { write(syntax) }
                    }))
                }
            }

            StoryRegistry.BLOCK -> {
                val hit = minecraft.hitResult as? BlockHitResult
                val looked =
                    hit?.takeIf { it.type == HitResult.Type.BLOCK }?.let { minecraft.level?.getBlockState(it.blockPos) }
                if (looked != null) {
                    add(UiDropdownItem(storyLang("registry.looked"), item = ItemStack(looked.block.asItem())) {
                        write(StoryItemSyntax.of(looked))
                    })
                }
                val block = (held?.item as? BlockItem)?.block
                if (block != null) {
                    add(
                        UiDropdownItem(
                            storyLang("registry.held"),
                            item = held
                        ) { write(StoryItemSyntax.of(block.defaultBlockState())) })
                }
            }
        }
    }
    val all = registry.entries().groupBy { it.first.substringBefore(':') }.map { (namespace, entries) ->
        UiDropdownItem(namespace, children = entries.map { (id, stack) ->
            UiDropdownItem(stack.hoverName.string, item = stack, shortcut = id) { write(id) }
        })
    }
    return near + all.mapIndexed { index, item -> item.copy(separatorBefore = index == 0 && near.isNotEmpty()) }
}

private const val PICK_ICON = "hollowengine:textures/gui/icons/search.svg"
