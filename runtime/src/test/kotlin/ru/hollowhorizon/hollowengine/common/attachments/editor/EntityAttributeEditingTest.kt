package ru.hollowhorizon.hollowengine.common.attachments.editor

import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeState
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
    fun `catalog includes required attributes without creating instances`() {
        val attributes = state()
        assertEquals(setOf(id(Attributes.MAX_HEALTH), id(Attributes.MOVEMENT_SPEED)), EntityAttributeEditing.read(attributes).keys)
        assertEquals(0.23, attributes.baseValue(Attributes.MOVEMENT_SPEED))
        assertTrue(attributes.additionalAttributes().isEmpty())
    }

    @Test
    fun `reset uses entity type defaults and preserves permanent and transient modifiers`() {
        val attributes = state()
        val speed = attributes.getOrCreate(Attributes.MOVEMENT_SPEED)
        val permanent = AttributeModifier(location("permanent"), 0.1, AttributeModifier.Operation.ADD_VALUE)
        val transient = AttributeModifier(location("transient"), 0.2, AttributeModifier.Operation.ADD_VALUE)
        speed.addPermanentModifier(permanent)
        speed.addTransientModifier(transient)
        EntityAttributeEditing.apply(attributes, mapOf(id(Attributes.MOVEMENT_SPEED) to 2.0))
        assertEquals(2.0, speed.baseValue)

        EntityAttributeEditing.apply(attributes, EntityAttributeEditing.defaults(attributes))

        assertEquals(0.23, speed.baseValue)
        assertEquals(0.53, speed.value, 0.000001)
        assertEquals(permanent, speed.getModifier(permanent.id()))
        assertEquals(transient, speed.getModifier(transient.id()))
    }

    @Test
    fun `registry attributes absent from the entity type can be added and reset`() {
        val attributes = state()
        assertFalse(attributes.hasAttribute(Attributes.ATTACK_DAMAGE))
        EntityAttributeEditing.apply(attributes, mapOf(id(Attributes.ATTACK_DAMAGE) to 10.0))
        assertTrue(attributes.hasAttribute(Attributes.ATTACK_DAMAGE))
        assertEquals(10.0, attributes.baseValue(Attributes.ATTACK_DAMAGE))

        EntityAttributeEditing.apply(attributes, EntityAttributeEditing.defaults(attributes))
        assertEquals(Attributes.ATTACK_DAMAGE.value().defaultValue, attributes.baseValue(Attributes.ATTACK_DAMAGE))
        assertEquals(setOf(Attributes.ATTACK_DAMAGE), attributes.additionalAttributes())
    }

    @Test
    fun `removing an added attribute cannot remove the type's required attributes`() {
        val attributes = state()
        attributes.getOrCreate(Attributes.ATTACK_DAMAGE)
        val removed = EntityAttributeEditing.replace(attributes, emptyMap())
        assertEquals(setOf(id(Attributes.ATTACK_DAMAGE)), removed)
        assertFalse(attributes.hasAttribute(Attributes.ATTACK_DAMAGE))
        assertTrue(attributes.hasAttribute(Attributes.MAX_HEALTH))
        assertEquals(0.23, attributes.baseValue(Attributes.MOVEMENT_SPEED))
    }

    @Test
    fun `invalid attributes and nonfinite values cannot corrupt the map`() {
        val attributes = state()
        EntityAttributeEditing.apply(attributes, mapOf(
            "invalid ID" to 1.0,
            "missing:attribute" to 1.0,
            id(Attributes.ATTACK_DAMAGE) to Double.NaN,
            id(Attributes.MOVEMENT_SPEED) to Double.NaN,
            id(Attributes.MAX_HEALTH) to Double.POSITIVE_INFINITY,
        ))
        assertFalse(attributes.hasAttribute(Attributes.ATTACK_DAMAGE))
        assertEquals(0.23, attributes.baseValue(Attributes.MOVEMENT_SPEED))
        assertEquals(20.0, attributes.baseValue(Attributes.MAX_HEALTH))
    }

    @Test
    fun `out of range base values are clamped before saving`() {
        val attributes = state()
        EntityAttributeEditing.apply(attributes, mapOf(id(Attributes.MAX_HEALTH) to -20.0))
        assertEquals(1.0, EntityAttributeEditing.read(attributes).getValue(id(Attributes.MAX_HEALTH)))
    }

    @Test
    fun `unqualified registry names retain an added attribute during replacement`() {
        val attributes = state()
        attributes.getOrCreate(Attributes.ATTACK_DAMAGE)
        val key = id(Attributes.ATTACK_DAMAGE).removePrefix("minecraft:")
        assertTrue(EntityAttributeEditing.replace(attributes, mapOf(key to 8.0)).isEmpty())
        assertEquals(8.0, attributes.baseValue(Attributes.ATTACK_DAMAGE))
    }

    private fun state(): MutableAttributeState = MutableAttributeState(
        AttributeSupplier.builder().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.23).build(),
        linkedMapOf(), {}, linkedSetOf(), linkedSetOf(),
    )

    private fun id(attribute: Holder<Attribute>): String = BuiltInRegistries.ATTRIBUTE.getKey(attribute.value()).toString()
    private fun location(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath("test", path)
}
