package ru.hollowhorizon.hollowengine.common.attachments.editor

import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityAttributeEditingTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `catalog includes only attributes supported by the entity`() {
        val attributes = AttributeMap(supplier())
        val values = EntityAttributeEditing.read(attributes)

        assertEquals(setOf(id(Attributes.MAX_HEALTH), id(Attributes.MOVEMENT_SPEED)), values.keys)
        assertEquals(0.23, values.getValue(id(Attributes.MOVEMENT_SPEED)))
        assertFalse(attributes.hasAttribute(Attributes.ATTACK_DAMAGE))
    }

    @Test
    fun `reset uses entity type defaults and preserves permanent and transient modifiers`() {
        val supplier = supplier()
        val attributes = AttributeMap(supplier)
        val speed = attributes.getInstance(Attributes.MOVEMENT_SPEED)!!
        val permanent = AttributeModifier(location("permanent"), 0.1, AttributeModifier.Operation.ADD_VALUE)
        val transient = AttributeModifier(location("transient"), 0.2, AttributeModifier.Operation.ADD_VALUE)
        speed.addPermanentModifier(permanent)
        speed.addTransientModifier(transient)
        EntityAttributeEditing.apply(attributes, mapOf(id(Attributes.MOVEMENT_SPEED) to 2.0))
        assertEquals(2.0, speed.baseValue)

        val defaults = EntityAttributeEditing.defaults(attributes, supplier)
        EntityAttributeEditing.apply(attributes, defaults)

        assertEquals(0.23, speed.baseValue)
        assertEquals(0.53, speed.value, 0.000001)
        assertEquals(permanent, speed.getModifier(permanent.id()))
        assertEquals(transient, speed.getModifier(transient.id()))
        val restored = AttributeMap(supplier)
        restored.load(attributes.save())
        assertEquals(0.23, restored.getBaseValue(Attributes.MOVEMENT_SPEED))
        assertTrue(restored.hasModifier(Attributes.MOVEMENT_SPEED, permanent.id()))
        assertFalse(restored.hasModifier(Attributes.MOVEMENT_SPEED, transient.id()))
    }

    @Test
    fun `invalid and unsupported attributes cannot corrupt the map`() {
        val attributes = AttributeMap(supplier())
        EntityAttributeEditing.apply(attributes, mapOf(
            "invalid ID" to 1.0,
            "missing:attribute" to 1.0,
            id(Attributes.ATTACK_DAMAGE) to 10.0,
            id(Attributes.MOVEMENT_SPEED) to Double.NaN,
            id(Attributes.MAX_HEALTH) to Double.POSITIVE_INFINITY,
        ))

        assertFalse(attributes.hasAttribute(Attributes.ATTACK_DAMAGE))
        assertEquals(0.23, attributes.getBaseValue(Attributes.MOVEMENT_SPEED))
        assertEquals(20.0, attributes.getBaseValue(Attributes.MAX_HEALTH))
    }

    @Test
    fun `out of range base values are clamped before saving`() {
        val attributes = AttributeMap(supplier())
        EntityAttributeEditing.apply(attributes, mapOf(id(Attributes.MAX_HEALTH) to -20.0))
        assertEquals(1.0, EntityAttributeEditing.read(attributes).getValue(id(Attributes.MAX_HEALTH)))
    }

    private fun supplier(): AttributeSupplier = AttributeSupplier.builder()
        .add(Attributes.MAX_HEALTH, 20.0)
        .add(Attributes.MOVEMENT_SPEED, 0.23)
        .build()

    private fun id(attribute: Holder<Attribute>): String =
        BuiltInRegistries.ATTRIBUTE.getKey(attribute.value()).toString()

    private fun location(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath("test", path)
}
