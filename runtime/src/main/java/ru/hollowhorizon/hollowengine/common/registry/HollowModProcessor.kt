package ru.hollowhorizon.hollowengine.common.registry

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.serializerOrNull
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.*
import ru.hollowhorizon.hollowengine.api.utils.Polymorphic
import ru.hollowhorizon.hollowengine.common.config.Config
import ru.hollowhorizon.hollowengine.common.config.ConfigName
import ru.hollowhorizon.hollowengine.common.events.*
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterReloadListenersEvent
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.registry.system.RegistryManager
import ru.hollowhorizon.hollowengine.common.utils.JavaHacks
import ru.hollowhorizon.hollowengine.common.utils.Side
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBT_TAGS
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.network.CommonNetworkManager.registerPacket
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

@OptIn(InternalSerializationApi::class)
object HollowModProcessor {
    init {
        val handles = MethodHandles.lookup()

        registerMethodHandler<SubscribeEvent> { method, _ ->
            val listener = if (method.isStatic()) {
                handles.createStaticEventListener(method)
            } else {
                val obj = method.declaringClass.kotlin.objectInstance ?: return@registerMethodHandler
                handles.createEventListener(method, obj)
            }

            var isClientOnly = ClientEvent::class.java.isAssignableFrom(method.parameterTypes[0])
            isClientOnly = isClientOnly || method.declaringClass.hasAnnotationNamed(ClientOnly::class)
            isClientOnly = isClientOnly || method.isAnnotationPresent(ClientOnly::class.java)

            if (!isClientOnly || isPhysicalClient) {
                EventHandler.get(method.parameterTypes[0].kotlin as KClass<Event>).register(listener)
            }
        }

        AnnotationProcessorEvent.post(AnnotationProcessorEvent(getAnnotatedClasses, getSubTypes, getAnnotatedMethods))

        registerClassHandler<ConfigName> { type, _ ->
            (type.kotlin.objectInstance as Config).initialize()
        }

        registerClassHandler<HollowPacketHandler> { type, _ ->
            if (HollowPacket::class.java.isAssignableFrom(type)) registerPacket(type as Class<HollowPacket>)
            else HollowEngine.LOGGER.warn("Unsupported packet: ${type.simpleName}")
        }

        registerClassHandler<Polymorphic> { type, annotation ->
            NBT_TAGS.computeIfAbsent(annotation.baseClass) { ArrayList() }.add(type.kotlin)
        }

        registerClassHandler<Init> { type, _ ->
            type.kotlin.objectInstance ?: throw IllegalArgumentException("${type.simpleName} must be an object!")
        }
        registerClassHandler<ReloadListener> { type, listener ->
            // Must precede objectInstance: resolving INSTANCE loads client-only field types.
            if (listener.side == Side.CLIENT && !isPhysicalClient) return@registerClassHandler

            val instance = type.kotlin.objectInstance as ResourceManagerReloadListener

            when (listener.side) {
                Side.CLIENT -> {
                    RegisterReloadListenersEvent.Client.register {
                        it.register(instance)
                    }
                }

                Side.SERVER -> {
                    RegisterReloadListenersEvent.Server.register {
                        it.register(instance)
                    }
                }

                Side.BOTH -> {
                    RegisterReloadListenersEvent.Server.register {
                        it.register(instance)
                    }
                    if (isPhysicalClient) RegisterReloadListenersEvent.Client.register {
                        it.register(instance)
                    }
                }
            }
        }
        registerClassHandler<Registerable> { type, _ ->
            val component = type.kotlin
            val serializer = component.serializerOrNull() ?: error("${type.simpleName} must be serializable!")
            val key = component.findAnnotation<SerialName>()?.value?.rl
                ?: error("@SerialName not found for class ${type.simpleName}")

            ComponentDescriptorRegistry.register(
                ComponentDescriptor(
                    id = key,
                    value = component,
                    serializer = JavaHacks.forceCast(serializer),
                    persistencePolicy = if (type.hasAnnotationNamed(LooseOnDeath::class)) {
                        ComponentPersistencePolicy.LOOSE_ON_DEATH
                    } else {
                        ComponentPersistencePolicy.PERSIST
                    },
                    syncPolicy = if (type.hasAnnotationNamed(Syncable::class)) {
                        ComponentSyncPolicy.SYNC
                    } else {
                        ComponentSyncPolicy.NONE
                    },
                )
            )
        }
        registerMethodHandler<Init> { method, _ ->
            if (method.isStatic()) {
                method.invoke(null)
            } else {
                val obj = method.declaringClass.kotlin.objectInstance
                    ?: throw IllegalArgumentException("${method.declaringClass.simpleName} must be an object!")
                method.invoke(obj)
            }
        }

        RegistryManager.bakeAll()
        RegistryManager.freezeAll()

        registerClassInitializers<HollowRegistry>()
    }

    private inline fun <reified T : Annotation> registerClassHandler(noinline task: (Class<*>, T) -> Unit) {
        getAnnotatedClasses(T::class.java).forEach {
            val annotation = it.getAnnotation(T::class.java)
            task(it, annotation)
        }
    }

    private inline fun <reified T> registerClassInitializers() {
        getSubTypes(T::class.java).forEach {
            it.kotlin.objectInstance?.apply {
                HollowEngine.LOGGER.info("Registering initializer: ${it.simpleName}")
            }
        }
    }

    private inline fun <reified T : Annotation> registerMethodHandler(noinline task: (Method, T) -> Unit) {
        getAnnotatedMethods(T::class.java).forEach {
            val annotation = it.getAnnotation(T::class.java)
            task(it, annotation)
        }
    }
}

lateinit var getAnnotatedClasses: (Class<*>) -> Set<Class<*>>
lateinit var getSubTypes: (Class<*>) -> Set<Class<*>>
lateinit var getAnnotatedMethods: (Class<*>) -> Set<Method>

private fun Method.isStatic(): Boolean {
    return Modifier.isStatic(this.modifiers)
}

private fun Class<*>.hasAnnotationNamed(annotationClass: KClass<out Annotation>): Boolean {
    val expectedName = annotationClass.java.name
    return declaredAnnotations.any { it.annotationClass.java.name == expectedName }
}
