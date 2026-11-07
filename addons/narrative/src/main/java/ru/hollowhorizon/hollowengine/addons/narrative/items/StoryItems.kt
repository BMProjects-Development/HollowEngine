package ru.hollowhorizon.hollowengine.addons.narrative.items

import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.item

/**
 * The stack [syntax] names, written as the `/give` command takes it: `minecraft:diamond_sword[damage=5]`. This is how
 * an item field of a story holds its value.
 */
fun itemOf(syntax: String, count: Int = 1): ItemStack = item(syntax, count)

/** The block state [syntax] names, written as `/setblock` takes it: `minecraft:oak_stairs[facing=north]`. */
fun blockOf(syntax: String): BlockState = runCatching {
    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), syntax, false).blockState()
}.getOrElse { throw StoryException("'$syntax' is not a block: ${it.message}") }

/** How the values of items and blocks are written back into a field: the syntax [itemOf] and [blockOf] read. */
object StoryItemSyntax {
    /** [stack] as [itemOf] reads it, with every component it changes; its count is not part of it. */
    fun of(stack: ItemStack, registries: HolderLookup.Provider): String {
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        val components = stack.componentsPatch.entrySet().mapNotNull { (type, value) ->
            val key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type) ?: return@mapNotNull null
            if (value.isEmpty) return@mapNotNull "!$key"
            @Suppress("UNCHECKED_CAST")
            val codec = (type as DataComponentType<Any>).codec() ?: return@mapNotNull null
            codec.encodeStart(ops, value.get()).result().map { "$key=$it" }.orElse(null)
        }
        return if (components.isEmpty()) id else components.joinToString(",", "$id[", "]")
    }

    /** [state] as [blockOf] reads it. */
    fun of(state: BlockState): String = BlockStateParser.serialize(state)
}
