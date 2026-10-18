package ru.hollowhorizon.hollowengine.common.attachments.api

import java.util.*

/** An owned runtime resource whose validity follows the entity's current components. */
interface RuntimeAttachment : AutoCloseable {
    val isValid: Boolean
}

/**
 * State that hangs off an entity for as long as that entity exists, created on demand and keyed by
 * whatever the caller likes.
 */
class RuntimeAttachments : AutoCloseable {
    private val values = LinkedHashMap<Any, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrPut(key: Any, create: () -> T): T = values.getOrPut(key, create) as T

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrNull(key: Any): T? = values[key] as T?

    fun remove(key: Any) {
        discard(setOf(key))
    }

    /** Drops everything whose key is not in [keys]; for callers that rebuild their set every frame. */
    fun retain(keys: Set<Any>) {
        discard(values.keys.filterTo(HashSet()) { it !in keys })
    }

    fun pruneInvalid() {
        discard(values.filterValues { it is RuntimeAttachment && !it.isValid }.keys)
    }

    override fun close() = discard(values.keys.toSet())

    private fun discard(keys: Set<Any>) {
        val removed = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        keys.forEach { key -> values.remove(key)?.let(removed::add) }
        values.values.forEach(removed::remove)
        var failure: Exception? = null
        removed.filterIsInstance<AutoCloseable>().forEach { resource ->
            try {
                resource.close()
            } catch (error: Exception) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    val isEmpty: Boolean get() = values.isEmpty()
}
