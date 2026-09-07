package ru.hollowhorizon.hollowengine.api.extensions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine

/**
 * Named place the engine reads contributions from, and addons write them to.
 */
class ExtensionPoint<T : Any> internal constructor(val id: ResourceLocation) : Iterable<T> {
    @Volatile
    private var entries: List<Entry<T>> = emptyList()

    @Volatile
    private var listeners: List<() -> Unit> = emptyList()

    @Volatile
    private var values: List<T> = emptyList()

    @Volatile
    var generation: Int = 0
        private set

    val extensions: List<T> get() = values

    override fun iterator(): Iterator<T> = values.iterator()

    @Synchronized
    fun register(key: ResourceLocation, extension: T): ExtensionHandle {
        entries = entries.filterNot { it.key == key } + Entry(key, extension)
        changed()
        return ExtensionHandle { unregister(key) }
    }

    @Synchronized
    fun unregister(key: ResourceLocation): Boolean {
        val remaining = entries.filterNot { it.key == key }
        if (remaining.size == entries.size) return false

        entries = remaining
        changed()
        return true
    }

    fun find(key: ResourceLocation): T? = entries.firstOrNull { it.key == key }?.value

    @Synchronized
    fun onChange(listener: () -> Unit): ExtensionHandle {
        listeners = listeners + listener
        return ExtensionHandle { removeListener(listener) }
    }

    @Synchronized
    private fun removeListener(listener: () -> Unit) {
        listeners = listeners - listener
    }

    private fun changed() {
        values = entries.map { it.value }
        generation++
        listeners.forEach { listener ->
            runCatching(listener).onFailure {
                HollowEngine.LOGGER.error("Extension point '{}' listener failed", id, it)
            }
        }
    }

    private class Entry<T>(val key: ResourceLocation, val value: T)
}

/**
 * Undoes one registration. Addons close their handles when unloading; the engine's own registrations
 * live as long as the game works.
 */
fun interface ExtensionHandle : AutoCloseable {
    override fun close()
}

fun ExtensionHandle.closeWith(scope: CoroutineScope): ExtensionHandle {
    scope.coroutineContext.job.invokeOnCompletion { close() }
    return this
}

/**
 * Every extension point known to the engine, by id.
 */
object ExtensionPoints {
    private val points = HashMap<ResourceLocation, ExtensionPoint<*>>()

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T : Any> create(id: ResourceLocation): ExtensionPoint<T> =
        points.getOrPut(id) { ExtensionPoint<T>(id) } as ExtensionPoint<T>
}
