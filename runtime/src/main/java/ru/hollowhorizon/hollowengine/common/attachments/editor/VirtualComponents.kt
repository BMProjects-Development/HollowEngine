package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import kotlin.reflect.KClass

/**
 * A component that is only ever assembled for the editor: it reads straight off the entity and writes
 * straight back into it, and nothing of it is stored in the entity's [ru.hollowhorizon.hollowengine.common.attachments.api.ComponentStore] or saved to NBT.
 */
class VirtualComponentDescriptor<T : Any>(
    val id: ResourceLocation,
    val type: KClass<T>,
    val serializer: KSerializer<T>,
    val supports: (Entity) -> Boolean,
    private val reader: (Entity) -> T?,
    private val writer: (Entity, T) -> Unit,
) {
    fun read(entity: Entity): T? = if (supports(entity)) reader(entity) else null

    fun write(entity: Entity, value: Any): Boolean {
        if (!supports(entity) || !type.isInstance(value)) return false
        @Suppress("UNCHECKED_CAST") writer(entity, value as T)
        return true
    }
}

object VirtualComponentRegistry {
    private val descriptors = LinkedHashMap<ResourceLocation, VirtualComponentDescriptor<*>>()

    fun register(descriptor: VirtualComponentDescriptor<*>): VirtualComponentDescriptor<*> {
        descriptors[descriptor.id] = descriptor
        return descriptor
    }

    fun <T : Any> register(
        id: ResourceLocation,
        type: KClass<T>,
        serializer: KSerializer<T>,
        supports: (Entity) -> Boolean = { true },
        read: (Entity) -> T?,
        write: (Entity, T) -> Unit,
    ): VirtualComponentDescriptor<T> {
        val descriptor = VirtualComponentDescriptor(id, type, serializer, supports, read, write)
        register(descriptor)
        return descriptor
    }

    fun all(): List<VirtualComponentDescriptor<*>> = descriptors.values.toList()

    fun descriptor(id: ResourceLocation): VirtualComponentDescriptor<*>? = descriptors[id]

    fun descriptor(type: KClass<*>): VirtualComponentDescriptor<*>? = descriptors.values.firstOrNull { it.type == type }

    fun isVirtual(id: ResourceLocation): Boolean = id in descriptors

    fun isVirtual(component: Any): Boolean = descriptor(component::class) != null

    fun read(entity: Entity): List<Any> = descriptors.values.mapNotNull { it.read(entity) }

    fun apply(entity: Entity, component: Any): Boolean =
        descriptor(component::class)?.write(entity, component) ?: false

    @Suppress("UNCHECKED_CAST")
    fun registerSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(Any::class) {
            descriptors.values.forEach { descriptor ->
                subclass(
                    descriptor.type as KClass<Any>,
                    descriptor.serializer as KSerializer<Any>,
                )
            }
        }
    }

    fun serializersModule(): SerializersModule = SerializersModule { registerSerializers(this) }
}
