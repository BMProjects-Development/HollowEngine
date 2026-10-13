package ru.hollowhorizon.hollowengine.client.ui.entity

import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityAttributeCatalogTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `picker catalog includes every registered attribute rather than only NPC defaults`() {
        val catalog = entityAttributeCatalog()
        val id = BuiltInRegistries.ATTRIBUTE.getKey(Attributes.BLOCK_INTERACTION_RANGE.value()).toString()
        assertEquals(BuiltInRegistries.ATTRIBUTE.size(), catalog.size)
        assertTrue(id in catalog)
        assertEquals(Attributes.BLOCK_INTERACTION_RANGE.value().defaultValue, catalog.getValue(id).defaultValue)
    }

    @Test
    fun `missing translations show registry IDs rather than translation keys`() {
        val option = EntityAttributeOption("example:custom_attribute", "attribute.name.example.missing", 2.0)
        assertNull(option.translatedName)
        assertEquals("example:custom_attribute", option.displayName)
        assertTrue(option.matches("CUSTOM_ATTRIBUTE"))
    }
}
