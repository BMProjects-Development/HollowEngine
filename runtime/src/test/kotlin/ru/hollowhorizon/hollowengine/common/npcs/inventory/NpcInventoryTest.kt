package ru.hollowhorizon.hollowengine.common.npcs.inventory

import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.hollowhorizon.hollowengine.common.npcs.items.*
import kotlin.test.*

class NpcInventoryTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `insert returns overflow without losing items`() {
        val inventory = NpcInventory(1)
        val remainder = inventory.insert(ItemStack(Items.APPLE, 70))

        assertEquals(64, inventory.contents().single().count)
        assertEquals(6, remainder.count)
    }

    @Test
    fun `component matching can be exact or item only`() {
        val namedApple = ItemStack(Items.APPLE).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal("Quest Apple"))
        }
        val plainApple = ItemStack(Items.APPLE)

        assertFalse(itemFilter(namedApple).matches(plainApple))
        assertTrue(itemFilter(namedApple, match = ItemMatchMode.ITEM_ONLY).matches(plainApple))
    }

    @Test
    fun `multiple requests do not consume the same items twice`() {
        val inventory = NpcInventory(2)
        inventory.insert(ItemStack(Items.APPLE, 5))
        val apples = itemFilter(ItemStack(Items.APPLE), match = ItemMatchMode.ITEM_ONLY)
        val fruit = anyOf(apples, itemFilter(ItemStack(Items.CARROT), match = ItemMatchMode.ITEM_ONLY))

        assertFalse(
            inventory.containsAll(
                listOf(
                    apples.request(5),
                    fruit.request(1),
                )
            )
        )
    }

    @Test
    fun `stack request derives quantity from its template`() {
        val request = itemRequest(ItemStack(Items.APPLE, 7), match = ItemMatchMode.ITEM_ONLY)

        assertEquals(7, request.count)
        assertTrue(request.filter.matches(ItemStack(Items.APPLE)))
    }

    @Test
    fun `inventory round trips item components through nbt`() {
        val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
        val inventory = NpcInventory(4)
        inventory.insert(ItemStack(Items.DIAMOND, 3).apply {
            set(DataComponents.CUSTOM_NAME, Component.literal("Stored"))
        })
        val tag = CompoundTag()
        inventory.save(tag, registries)

        val restored = NpcInventory(1)
        restored.load(tag, registries)

        assertEquals(4, restored.size)
        assertEquals(3, restored.contents().single { !it.isEmpty }.count)
        assertEquals("Stored", restored.contents().single { !it.isEmpty }.hoverName.string)
    }
}
