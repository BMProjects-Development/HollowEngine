package ru.hollowhorizon.hollowengine.common.events.registry

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import ru.hollowhorizon.hollowengine.common.events.StartupEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

class RegisterEntityAttributesEvent : StartupEvent {
    companion object : EventHandler<RegisterEntityAttributesEvent>()

    private val attributes = HashMap<EntityType<out LivingEntity>, AttributeSupplier>()

    fun register(entity: EntityType<out LivingEntity>, attributes: AttributeSupplier) {
        this.attributes[entity] = attributes
    }

    fun getAttributes() = HashMap(attributes)
}