package ru.hollowhorizon.hollowengine.client.ui

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.client.ui.UiItem.Companion.Empty
import ru.hollowhorizon.hollowengine.common.utils.areStacksEqual

/**
 * An [ItemStack] carried through the style system.
 *
 * The cascade compares property values with `equals` to decide whether a style, and the render command
 * derived from it, actually changed. [ItemStack] inherits identity equality, and a slot hands out a fresh
 * copy of its contents on every recomposition, so storing the stack raw would report a change every frame
 * and defeat that caching. This wraps it with value equality over item, components and count.
 *
 * The stack is copied on construction: callers routinely pass a live inventory stack, and the render
 * thread must never observe the game thread mutating it mid-draw.
 */
class UiItem(stack: ItemStack) {
    val stack: ItemStack = if (stack.isEmpty) ItemStack.EMPTY else stack.copy()

    val isEmpty: Boolean get() = stack.isEmpty

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiItem) return false
        return stack.count == other.stack.count && stack.areStacksEqual(other.stack)
    }

    // Deliberately excludes components: equal items always hash equal, which is all the contract
    // requires, and hashing a component map on every style comparison is not worth the collisions
    // it would avoid.
    override fun hashCode(): Int = 31 * stack.item.hashCode() + stack.count

    override fun toString(): String = "UiItem($stack)"

    companion object {
        val Empty = UiItem(ItemStack.EMPTY)

        /**
         * Resolves an item id such as `minecraft:diamond` into a single-item stack, or [Empty] when
         * the id is malformed or unregistered. Resolution happens here rather than in the renderer,
         * so a bad id costs one lookup instead of one per frame.
         */
        fun of(id: String): UiItem {
            val location = ResourceLocation.tryParse(id) ?: return Empty
            val item = BuiltInRegistries.ITEM.getOptional(location).orElse(null) ?: return Empty
            return UiItem(ItemStack(item))
        }
    }
}
