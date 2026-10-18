package ru.hollowhorizon.hollowengine.common.addons

import io.github.classgraph.ClassGraph
import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.common.events.*
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.network.HollowAddonPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import ru.hollowhorizon.hollowengine.network.HollowAddonPacketRegistry
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

internal object HollowAddonEventRegistrar {
    fun register(
        artifactFile: File,
        classLoader: ClassLoader,
        addonId: String,
        entrypoint: HollowAddonEntrypoint,
        scope: CoroutineScope,
        packagePrefix: String? = null,
    ) {
        val (annotatedListeners, annotatedPackets) = ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableMethodInfo()
            .disableNestedJarScanning()
            .ignoreParentClassLoaders()
            .overrideClassLoaders(classLoader)
            .overrideClasspath(artifactFile)
            .scan().use { scan ->
                scan.getClassesWithMethodAnnotation(SubscribeEvent::class.java.name).names.toList() to
                    scan.getClassesWithAnnotation(HollowPacketHandler::class.java.name).names.toList()
            }

        annotatedListeners.filter { packagePrefix == null || it.startsWith(packagePrefix) }.forEach { className ->
            val type = Class.forName(className, false, classLoader)
            registerType(type, entrypoint, scope)
        }

        annotatedPackets.filter { packagePrefix == null || it.startsWith(packagePrefix) }.forEach { className ->
            val type = Class.forName(className, false, classLoader)
            require(HollowAddonPacket::class.java.isAssignableFrom(type)) {
                "Addon packet must implement HollowAddonPacket: ${type.name}"
            }
            HollowAddonPacketRegistry.register(addonId, type.asSubclass(HollowAddonPacket::class.java))
        }
    }

    internal fun registerType(type: Class<*>, entrypoint: HollowAddonEntrypoint, scope: CoroutineScope) {
        if (!isPhysicalClient && type.isAnnotationPresent(ClientOnly::class.java)) return
        type.declaredMethods
            .filter { method -> method.isAnnotationPresent(SubscribeEvent::class.java) }
            .filter { method -> isPhysicalClient || !method.isAnnotationPresent(ClientOnly::class.java) }
            .filter(::hasRequiredMod)
            .forEach { method -> registerMethod(method, entrypoint, scope) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerMethod(
        method: Method,
        entrypoint: HollowAddonEntrypoint,
        scope: CoroutineScope,
    ) {
        require(Modifier.isPublic(method.modifiers)) {
            "@SubscribeEvent method must be public: $method"
        }
        require(Modifier.isPublic(method.declaringClass.modifiers)) {
            "@SubscribeEvent declaring class must be public: ${method.declaringClass.name}"
        }
        require(method.parameterCount == 1 && Event::class.java.isAssignableFrom(method.parameterTypes.single())) {
            "@SubscribeEvent method must accept exactly one Event: $method"
        }
        require(method.returnType == Void.TYPE) {
            "@SubscribeEvent method must return Unit/void: $method"
        }

        val lookup = MethodHandles.lookup()
        val listener = if (Modifier.isStatic(method.modifiers)) {
            lookup.createStaticEventListener(method)
        } else {
            val target = when (method.declaringClass) {
                entrypoint.javaClass -> entrypoint
                else -> method.declaringClass.kotlin.objectInstance
            } ?: throw IllegalArgumentException(
                "Non-static @SubscribeEvent method must be declared on the addon entrypoint or a Kotlin object: $method",
            )
            lookup.createEventListener(method, target)
        }
        val eventType = method.parameterTypes.single().kotlin as KClass<Event>
        EventHandler.get(eventType).register(scope, listener)
    }

    private fun hasRequiredMod(method: Method): Boolean = method
        .getAnnotation(RequireMod::class.java)
        ?.let { annotation -> ModList.isLoaded(annotation.modId) }
        ?: true
}
