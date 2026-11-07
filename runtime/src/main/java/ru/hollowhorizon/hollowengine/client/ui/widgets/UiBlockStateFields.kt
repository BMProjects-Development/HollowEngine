package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.utils.lang

@Composable
fun BlockStateFields(id: String, syntax: String, onChange: (String) -> Unit) {
    val state = remember(syntax) { BlockStates.parse(syntax) } ?: return
    Row(tags = listOf("block-state-header"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
        Box(modifier = Modifier.size(HeaderItemSize.px, HeaderItemSize.px).item(ItemStack(state.block.asItem())))
        Text(state.block.name.string, tags = listOf("block-state-title"))
    }
    if (state.properties.isEmpty()) {
        Text("hollowengine.gui.registry_picker.no_states".lang, tags = listOf("block-state-empty"))
        return
    }
    state.properties.forEach { property -> PropertyField("$id-${property.name}", state, property, onChange) }
}

@Composable
private fun <T : Comparable<T>> PropertyField(
    id: String,
    state: BlockState,
    property: Property<T>,
    onChange: (String) -> Unit,
) {
    var open by remember(id) { mutableStateOf(false) }
    val current = state.getValue(property)
    Row(tags = listOf("block-state-field"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
        Text(property.name, tags = listOf("block-state-name"))
        UiDropdown(
            id = id,
            label = property.getName(current),
            expanded = open,
            onExpandedChange = { open = it },
            items = property.possibleValues.map { value ->
                UiDropdownItem(property.getName(value), mark = UiMenuParts.radio(value == current)) {
                    onChange(BlockStates.write(state.setValue(property, value)))
                }
            },
            tags = listOf("block-state-value"),
        )
    }
}

/** Block states in the syntax of commands. */
object BlockStates {
    fun parse(syntax: String): BlockState? = runCatching {
        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), syntax, false).blockState()
    }.getOrNull()

    fun write(state: BlockState): String = BlockStateParser.serialize(state)
}

private const val HeaderItemSize = 16f
