package ru.hollowhorizon.hollowengine.common.scripting

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.EventListener
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.createEventListener
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.utils.UnsafeTools
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

/**
 * Subscribes the `@SubscribeEvent` functions of a script instance for the lifetime of a scope. A broken
 * handler is logged and skipped.
 */
internal object ScriptEventHandlers {
    fun subscribe(id: ScriptId, script: Any, scope: CoroutineScope) {
        val scriptClass = script.javaClass
        val thread = Thread.currentThread()
        val classLoader = thread.contextClassLoader
        runCatching {
            try {
                val lookup = MethodHandles.privateLookupIn(scriptClass, UnsafeTools.lookup)
                thread.contextClassLoader = scriptClass.classLoader
                scriptClass.declaredMethods
                    .filter { method -> method.isAnnotationPresent(SubscribeEvent::class.java) }
                    .forEach { method -> subscribe(id, script, lookup, method, scope) }
            } finally {
                thread.contextClassLoader = classLoader
            }
        }.onFailure { error ->
            HollowEngine.LOGGER.error(
                "Failed to inspect script '{}' for @SubscribeEvent handlers",
                ScriptRegistry.display(id),
                error,
            )
        }
    }

    private fun subscribe(
        id: ScriptId,
        script: Any,
        lookup: MethodHandles.Lookup,
        method: Method,
        scope: CoroutineScope,
    ) {
        if (!hasValidSignature(id, method)) return

        runCatching {
            @Suppress("UNCHECKED_CAST")
            val handler = EventHandler.get(method.parameterTypes.single().kotlin as KClass<Event>)
            val listener = lookup.createEventListener(method, script).logging(id, method)
            handler.register(scope, listener)
        }.onFailure { error ->
            HollowEngine.LOGGER.error(
                "Failed to register @SubscribeEvent handler '{}' in script '{}': {}",
                method.name,
                ScriptRegistry.display(id),
                error.message,
                error,
            )
        }
    }

    private fun hasValidSignature(id: ScriptId, method: Method): Boolean {
        val problem = when {
            Modifier.isStatic(method.modifiers) -> "it must be an instance method"
            method.parameterCount != 1 -> "it declares ${method.parameterCount} parameters instead of exactly one"
            !Event::class.java.isAssignableFrom(method.parameterTypes.single()) ->
                "its parameter '${method.parameterTypes.single().name}' does not implement ${Event::class.java.name}"
            method.returnType != Void.TYPE -> "it returns '${method.returnType.name}' instead of Unit"
            else -> return true
        }

        HollowEngine.LOGGER.error(
            "Invalid @SubscribeEvent signature in script '{}', method '{}': {}. " +
                "Expected an instance method with exactly one ${Event::class.java.simpleName} parameter and a Unit return type. " +
                "Actual signature: {}",
            ScriptRegistry.display(id),
            method.name,
            problem,
            method.toGenericString(),
        )
        return false
    }

    private fun EventListener<Event>.logging(id: ScriptId, method: Method): EventListener<Event> {
        val delegate = this
        return object : EventListener<Event> {
            override val priority: Int = delegate.priority

            override fun invoke(event: Event) {
                runCatching { delegate(event) }
                    .onFailure { error ->
                        HollowEngine.LOGGER.error(
                            "Error in event handler '{}' of script '{}' while handling {}",
                            method.name,
                            ScriptRegistry.display(id),
                            event.javaClass.name,
                            error,
                        )
                    }
            }
        }
    }
}
