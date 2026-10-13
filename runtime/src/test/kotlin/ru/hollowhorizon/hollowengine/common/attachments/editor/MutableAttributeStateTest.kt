package ru.hollowhorizon.hollowengine.common.attachments.editor

import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MutableAttributeStateTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `an added default-valued syncable attribute is immediately dirty`() {
        val fixture = Fixture()
        val instance = fixture.state.getOrCreate(Attributes.SCALE)
        assertSame(instance, fixture.state.getOrCreate(Attributes.SCALE))
        assertEquals(Attributes.SCALE.value().defaultValue, instance.baseValue)
        assertTrue(instance in fixture.sync)
        assertTrue(instance in fixture.updates)
    }

    @Test
    fun `removal clears pending packets and retains required attributes`() {
        val fixture = Fixture()
        val instance = fixture.state.getOrCreate(Attributes.SCALE)
        assertTrue(fixture.state.remove(Attributes.SCALE))
        assertFalse(instance in fixture.sync)
        assertFalse(instance in fixture.updates)
        assertFalse(fixture.state.hasAttribute(Attributes.SCALE))
        assertFalse(fixture.state.remove(Attributes.SCALE))
        assertFalse(fixture.state.remove(Attributes.MAX_HEALTH))
        assertEquals(20.0, fixture.state.baseValue(Attributes.MAX_HEALTH))
    }

    @Test
    fun `additional attribute values and permanent modifiers survive vanilla NBT`() {
        val fixture = Fixture()
        val added = fixture.state.getOrCreate(Attributes.SCALE)
        added.baseValue = 1.5
        val permanent = AttributeModifier(ResourceLocation.fromNamespaceAndPath("test", "permanent"), 0.25,
            AttributeModifier.Operation.ADD_VALUE)
        val transient = AttributeModifier(ResourceLocation.fromNamespaceAndPath("test", "transient"), 0.5,
            AttributeModifier.Operation.ADD_VALUE)
        added.addPermanentModifier(permanent)
        added.addTransientModifier(transient)
        val saved = ListTag().also { tag -> fixture.instances.values.forEach { tag.add(it.save()) } }

        val restored = Fixture()
        restored.state.registerSavedAttributes(saved)
        for (index in 0 until saved.size) {
            val tag = saved.getCompound(index)
            val holder = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.parse(tag.getString("id"))).orElseThrow()
            restored.state.getOrCreate(holder).load(tag)
        }
        val instance = restored.state.getOrCreate(Attributes.SCALE)
        assertEquals(1.5, instance.baseValue)
        assertEquals(permanent, instance.getModifier(permanent.id()))
        assertEquals(null, instance.getModifier(transient.id()))
        assertEquals(setOf(Attributes.SCALE), restored.state.additionalAttributes())
    }

    @Test
    fun `removed attributes stay absent from saved data`() {
        val fixture = Fixture()
        fixture.state.getOrCreate(Attributes.SCALE)
        fixture.state.remove(Attributes.SCALE)
        assertTrue(fixture.instances.isEmpty())
        val restored = Fixture()
        restored.state.registerSavedAttributes(ListTag())
        assertFalse(restored.state.hasAttribute(Attributes.SCALE))
    }

    @Test
    fun `unknown and malformed saved attributes are ignored`() {
        val fixture = Fixture()
        val saved = ListTag()
        listOf("missing:attribute", "Bad ID").forEach { id -> saved.add(CompoundTag().also { it.putString("id", id) }) }
        fixture.state.registerSavedAttributes(saved)
        assertTrue(fixture.instances.isEmpty())
    }

    private class Fixture {
        val instances = linkedMapOf<Holder<Attribute>, AttributeInstance>()
        val sync = linkedSetOf<AttributeInstance>()
        val updates = linkedSetOf<AttributeInstance>()
        val state = MutableAttributeState(AttributeSupplier.builder().add(Attributes.MAX_HEALTH, 20.0).build(), instances,
            { instance ->
                updates += instance
                if (instance.attribute.value().isClientSyncable) sync += instance
            }, sync, updates)
    }
}
