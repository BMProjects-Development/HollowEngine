package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.registry.system.MutableRegistry
import ru.hollowhorizon.hollowengine.common.registry.system.RegistryManager
import ru.hollowhorizon.hollowengine.common.registry.system.RegistryState
import ru.hollowhorizon.hollowengine.common.utils.nbt.TagModuleRevision
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

enum class ComponentPersistencePolicy {
    PERSIST,
    LOOSE_ON_DEATH,
}

enum class ComponentSyncPolicy {
    NONE,
    SYNC,
}

data class ComponentDescriptor<T : Any>(
    val id: ResourceLocation,
    val value: KClass<T>,
    val serializer: KSerializer<T>,
    val persistencePolicy: ComponentPersistencePolicy = ComponentPersistencePolicy.PERSIST,
    val syncPolicy: ComponentSyncPolicy = ComponentSyncPolicy.NONE,
    val defaultFactory: (() -> T)? = null,
) {
    fun create(): T = defaultFactory?.invoke() ?: value.createInstance()
}

object ComponentDescriptorRegistry :
    MutableRegistry<ComponentDescriptor<*>> by RegistryManager.create("hollowengine:component_descriptors".rl) {
    private val mutableRegistry: MutableRegistry<ComponentDescriptor<*>>
        get() = this as MutableRegistry<ComponentDescriptor<*>>

    private fun ensureRegisteringState() {
        when (state) {
            RegistryState.FROZEN -> {
                mutableRegistry.unfreeze()
                mutableRegistry.unbake()
            }
            RegistryState.BAKED -> mutableRegistry.unbake()
            RegistryState.CONSTRUCTING,
            RegistryState.REGISTERING,
            -> Unit
        }
    }

    fun register(descriptor: ComponentDescriptor<*>): ComponentDescriptor<*> {
        requireStructuralEquality(descriptor)
        ensureRegisteringState()
        mutableRegistry.register(descriptor.id) { descriptor }
        mutableRegistry.bake()
        TagModuleRevision.invalidate()
        return descriptor
    }

    /**
     * A synced component is compared against the last value the clients were told, so identity equality
     * makes every write look like a change and resends the component on every tick that touches it.
     */
    private fun requireStructuralEquality(descriptor: ComponentDescriptor<*>) {
        if (descriptor.syncPolicy != ComponentSyncPolicy.SYNC) return
        val equals = runCatching { descriptor.value.java.getMethod("equals", Any::class.java) }.getOrNull()
        if (equals != null && equals.declaringClass != Any::class.java) return

        error(
            "Component ${descriptor.id} is @Syncable but does not override equals(). " +
                    "Make ${descriptor.value.simpleName} a data class, or implement equals()/hashCode(), " +
                    "otherwise it is resent to every tracking client on every write."
        )
    }

    fun unregisterDescriptor(id: ResourceLocation): Boolean {
        ensureRegisteringState()
        val removed = mutableRegistry.unregister(id)
        mutableRegistry.bake()
        TagModuleRevision.invalidate()
        return removed
    }

    fun descriptorOrNull(id: ResourceLocation): ComponentDescriptor<*>? = getOrNull(id)

    fun descriptorOrNull(type: KClass<*>): ComponentDescriptor<*>? =
        firstOrNull { it.value.value == type }?.value

    fun idFor(type: KClass<*>): ResourceLocation? = descriptorOrNull(type)?.id

    fun isLooseOnDeath(component: Any): Boolean =
        descriptorOrNull(component::class)?.persistencePolicy == ComponentPersistencePolicy.LOOSE_ON_DEATH

    fun serializersModule(): SerializersModule = SerializersModule {
        polymorphic(Component::class) {
            ComponentDescriptorRegistry.map { it.value }.forEach { descriptor ->
                @Suppress("UNCHECKED_CAST")
                subclass(
                    descriptor.value as KClass<Component>,
                    descriptor.serializer as KSerializer<Component>
                )
            }
        }
    }
}
