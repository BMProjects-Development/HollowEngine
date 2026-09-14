package ru.hollowhorizon.hollowengine.common.events.registry

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.StartupEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

class RegisterEntityRenderersEvent(private val consumer: (EntityType<out Entity>, EntityRendererProvider<Entity>) -> Unit) :
    ClientEvent, StartupEvent {
    companion object : EventHandler<RegisterEntityRenderersEvent>()

    fun <T : Entity> registerEntity(entity: EntityType<out T>, provider: EntityRendererProvider<T>) {
        consumer(entity, provider as EntityRendererProvider<Entity>)
    }
}

class RegisterBlockEntityRenderersEvent(private val consumer: (BlockEntityType<out BlockEntity>, BlockEntityRendererProvider<BlockEntity>) -> Unit) :
    ClientEvent, StartupEvent {
    companion object : EventHandler<RegisterBlockEntityRenderersEvent>()

    fun <T : BlockEntity> registerEntity(entity: BlockEntityType<out T>, provider: BlockEntityRendererProvider<T>) {
        consumer(entity, provider as BlockEntityRendererProvider<BlockEntity>)
    }
}
